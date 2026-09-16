package wgkernel

import (
	"context"
	"errors"
	"net"
	"net/netip"
	"os"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/mdlayher/genetlink"
	mdnetlink "github.com/mdlayher/netlink"
	"github.com/vishvananda/netlink"
	"golang.org/x/sys/unix"
)

type nativeKernel struct{}

// mdlayher/netlink captures NLDEBUG at package initialization and can otherwise
// log raw WireGuard key attributes. Never create a Generic Netlink connection
// when that mode was enabled, even if the variable is later cleared.
var debugConfiguredAtStartup = os.Getenv("NLDEBUG") != ""

func routeHandle(ctx context.Context) (*netlink.Handle, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	handle, err := netlink.NewHandle(unix.NETLINK_ROUTE)
	if err != nil {
		return nil, err
	}
	timeout := time.Second
	if deadline, ok := ctx.Deadline(); ok && time.Until(deadline) < timeout {
		timeout = time.Until(deadline)
	}
	if timeout < time.Microsecond {
		handle.Close()
		return nil, context.DeadlineExceeded
	}
	if err := handle.SetSocketTimeout(timeout); err != nil {
		handle.Close()
		return nil, err
	}
	return handle, nil
}

func wireGuardHandle(ctx context.Context) (*genetlink.Conn, uint16, error) {
	if debugConfiguredAtStartup || os.Getenv("NLDEBUG") != "" {
		return nil, 0, manager.ErrUnavailable
	}
	if err := ctx.Err(); err != nil {
		return nil, 0, err
	}
	connection, err := genetlink.Dial(nil)
	if err != nil {
		return nil, 0, err
	}
	deadline := time.Now().Add(2 * time.Second)
	if requested, ok := ctx.Deadline(); ok && requested.Before(deadline) {
		deadline = requested
	}
	if err := connection.SetDeadline(deadline); err != nil {
		connection.Close()
		return nil, 0, err
	}
	// A named GetFamily may autoload a module. Listing already registered
	// families never requests that action; absence is a deployment prerequisite.
	families, err := connection.ListFamilies()
	if err != nil {
		connection.Close()
		return nil, 0, err
	}
	for _, family := range families {
		if family.Name == "wireguard" && family.Version == wgVersion {
			return connection, family.ID, nil
		}
	}
	connection.Close()
	return nil, 0, manager.ErrUnavailable
}

func (nativeKernel) registered(ctx context.Context) error {
	connection, _, err := wireGuardHandle(ctx)
	if err == nil {
		connection.Close()
	}
	return err
}

func convertLink(item netlink.Link) link {
	attrs := item.Attrs()
	return link{index: attrs.Index, name: attrs.Name, alias: attrs.Alias, kind: item.Type(), up: attrs.Flags&net.FlagUp != 0}
}

func (nativeKernel) lookup(ctx context.Context, name string) (link, error) {
	handle, err := routeHandle(ctx)
	if err != nil {
		return link{}, err
	}
	defer handle.Close()
	item, err := handle.LinkByName(name)
	var missing netlink.LinkNotFoundError
	if errors.As(err, &missing) {
		return link{}, errNotFound
	}
	if err != nil {
		return link{}, err
	}
	return convertLink(item), nil
}

func (nativeKernel) snapshot(ctx context.Context) (snapshot, error) {
	handle, err := routeHandle(ctx)
	if err != nil {
		return snapshot{}, err
	}
	defer handle.Close()
	addresses, err := handle.AddrList(nil, netlink.FAMILY_ALL)
	if err != nil {
		return snapshot{}, err
	}
	routes, err := handle.RouteListFiltered(netlink.FAMILY_ALL, &netlink.Route{Table: unix.RT_TABLE_MAIN}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return snapshot{}, err
	}
	result := snapshot{}
	for _, address := range addresses {
		if address.IPNet != nil {
			prefix, err := prefixFromIPNet(address.IPNet)
			if err != nil {
				return snapshot{}, err
			}
			result.addresses = append(result.addresses, prefix)
		}
	}
	for _, item := range routes {
		if item.Dst == nil {
			continue
		}
		prefix, err := prefixFromIPNet(item.Dst)
		if err != nil {
			return snapshot{}, err
		}
		result.routes = append(result.routes, route{prefix: prefix, index: item.LinkIndex})
	}
	return result, nil
}

func prefixFromIPNet(value *net.IPNet) (netip.Prefix, error) {
	address, ok := netip.AddrFromSlice(value.IP)
	if !ok {
		return netip.Prefix{}, errMalformedDevice
	}
	address = address.Unmap()
	ones, bits := value.Mask.Size()
	if bits != address.BitLen() {
		return netip.Prefix{}, errMalformedDevice
	}
	return netip.PrefixFrom(address, ones), nil
}

func (nativeKernel) create(ctx context.Context, name, alias string, mtu int) (link, error) {
	handle, err := routeHandle(ctx)
	if err != nil {
		return link{}, err
	}
	defer handle.Close()
	item := &netlink.Wireguard{LinkAttrs: netlink.LinkAttrs{Name: name, Alias: alias, MTU: mtu}}
	if err := handle.LinkAdd(item); err != nil {
		return link{}, err
	}
	created, err := handle.LinkByName(name)
	if err != nil {
		return link{}, err
	}
	return convertLink(created), nil
}

func verifyLink(handle *netlink.Handle, expected link) (netlink.Link, error) {
	item, err := handle.LinkByIndex(expected.index)
	if err != nil {
		return nil, err
	}
	actual := convertLink(item)
	if actual.name != expected.name || actual.alias != expected.alias || actual.kind != "wireguard" {
		return nil, manager.ErrOwnershipMismatch
	}
	return item, nil
}

func (nativeKernel) configure(ctx context.Context, item link, data []byte) error {
	connection, family, err := wireGuardHandle(ctx)
	if err != nil {
		return err
	}
	defer connection.Close()
	_, err = connection.Execute(genetlink.Message{Header: genetlink.Header{Command: wgSetDevice, Version: wgVersion}, Data: data}, family, mdnetlink.Request|mdnetlink.Acknowledge)
	return err
}

func (nativeKernel) address(ctx context.Context, item link, prefix netip.Prefix) error {
	handle, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer handle.Close()
	actual, err := verifyLink(handle, item)
	if err != nil {
		return err
	}
	// NOPREFIXROUTE avoids implicit connected routes stealing unrelated traffic.
	return handle.AddrAdd(actual, &netlink.Addr{IPNet: &net.IPNet{IP: prefix.Addr().AsSlice(), Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen())}, Flags: unix.IFA_F_NOPREFIXROUTE})
}

func (nativeKernel) route(ctx context.Context, item link, prefix netip.Prefix) error {
	handle, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer handle.Close()
	if _, err := verifyLink(handle, item); err != nil {
		return err
	}
	return handle.RouteAdd(&netlink.Route{LinkIndex: item.index, Dst: &net.IPNet{IP: prefix.Masked().Addr().AsSlice(), Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen())}, Table: unix.RT_TABLE_MAIN, Protocol: unix.RTPROT_STATIC, Scope: netlink.SCOPE_LINK, Type: unix.RTN_UNICAST, Priority: 32760})
}

func (nativeKernel) up(ctx context.Context, item link) error {
	handle, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer handle.Close()
	actual, err := verifyLink(handle, item)
	if err != nil {
		return err
	}
	return handle.LinkSetUp(actual)
}

func (nativeKernel) peers(ctx context.Context, item link) ([]manager.PeerObservation, error) {
	connection, family, err := wireGuardHandle(ctx)
	if err != nil {
		return nil, err
	}
	defer connection.Close()
	encoder := mdnetlink.NewAttributeEncoder()
	encoder.Uint32(deviceIndex, uint32(item.index))
	data, err := encoder.Encode()
	if err != nil {
		return nil, err
	}
	messages, err := connection.Execute(genetlink.Message{Header: genetlink.Header{Command: wgGetDevice, Version: wgVersion}, Data: data}, family, mdnetlink.Request|mdnetlink.Dump)
	defer func() {
		for _, message := range messages {
			clear(message.Data)
		}
	}()
	if err != nil {
		return nil, err
	}
	return decodePeers(item.index, messages)
}

func (nativeKernel) remove(ctx context.Context, item link) error {
	handle, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer handle.Close()
	actual, err := verifyLink(handle, item)
	if err != nil {
		return err
	}
	return handle.LinkDel(actual)
}
