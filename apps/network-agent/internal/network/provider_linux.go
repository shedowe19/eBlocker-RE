package network

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"sort"
	"strings"
	"syscall"
)

// LinuxProvider reads the process network namespace without loading modules or
// changing interfaces, routes, rules, firewall state or tunnel configuration.
type LinuxProvider struct{}

func (LinuxProvider) Interfaces(ctx context.Context) ([]Interface, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	interfaces, err := net.Interfaces()
	if err != nil {
		return nil, err
	}
	result := make([]Interface, 0, len(interfaces))
	for _, iface := range interfaces {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		addresses, err := iface.Addrs()
		if err != nil {
			return nil, err
		}
		item := Interface{Index: iface.Index, Name: iface.Name, MTU: iface.MTU, HardwareAddress: iface.HardwareAddr.String(), Up: iface.Flags&net.FlagUp != 0, Running: iface.Flags&net.FlagRunning != 0, Loopback: iface.Flags&net.FlagLoopback != 0, Multicast: iface.Flags&net.FlagMulticast != 0, Addresses: make([]Address, 0, len(addresses))}
		for _, address := range addresses {
			prefix, err := netip.ParsePrefix(address.String())
			if err != nil {
				return nil, fmt.Errorf("parse interface address: %w", err)
			}
			family, scope := "ipv6", "global"
			if prefix.Addr().Is4() {
				family = "ipv4"
			}
			if prefix.Addr().IsLoopback() {
				scope = "loopback"
			} else if prefix.Addr().IsLinkLocalUnicast() {
				scope = "link"
			}
			item.Addresses = append(item.Addresses, Address{Prefix: prefix.String(), Family: family, Scope: scope})
		}
		sort.Slice(item.Addresses, func(i, j int) bool { return item.Addresses[i].Prefix < item.Addresses[j].Prefix })
		result = append(result, item)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].Index < result[j].Index })
	return result, nil
}

func (LinuxProvider) Routes(ctx context.Context) ([]Route, error) {
	payload := make([]byte, syscall.SizeofRtMsg)
	payload[0] = syscall.AF_UNSPEC
	messages, err := dump(ctx, syscall.NETLINK_ROUTE, syscall.RTM_GETROUTE, payload)
	if err != nil {
		return nil, err
	}
	routes := make([]Route, 0, len(messages))
	for _, message := range messages {
		if message.Header.Type != syscall.RTM_NEWROUTE {
			continue
		}
		if len(message.Data) < syscall.SizeofRtMsg {
			return nil, errors.New("truncated route header")
		}
		if message.Data[0] != syscall.AF_INET && message.Data[0] != syscall.AF_INET6 {
			continue
		}
		route, err := parseRoute(message.Data)
		if err != nil {
			return nil, err
		}
		routes = append(routes, route)
	}
	sort.SliceStable(routes, func(i, j int) bool {
		a, b := routes[i], routes[j]
		if a.Table != b.Table {
			return a.Table < b.Table
		}
		if a.Destination != b.Destination {
			return a.Destination < b.Destination
		}
		if a.Priority != b.Priority {
			return a.Priority < b.Priority
		}
		if a.InterfaceIndex != b.InterfaceIndex {
			return a.InterfaceIndex < b.InterfaceIndex
		}
		return a.Gateway < b.Gateway
	})
	return routes, nil
}

func parseRoute(data []byte) (Route, error) {
	if len(data) < syscall.SizeofRtMsg {
		return Route{}, errors.New("truncated route header")
	}
	family := data[0]
	zero := netip.IPv6Unspecified()
	familyName := "ipv6"
	if family == syscall.AF_INET {
		zero = netip.IPv4Unspecified()
		familyName = "ipv4"
	} else if family != syscall.AF_INET6 {
		return Route{}, errors.New("unsupported route family")
	}
	if int(data[1]) > zero.BitLen() || int(data[2]) > zero.BitLen() {
		return Route{}, errors.New("invalid route prefix length")
	}
	route := Route{Family: familyName, Destination: netip.PrefixFrom(zero, int(data[1])).String(), Table: uint32(data[4]), Protocol: data[5], Scope: data[6], Type: data[7], Flags: binary.NativeEndian.Uint32(data[8:])}
	attrs, err := attributes(data[syscall.SizeofRtMsg:])
	if err != nil {
		return Route{}, err
	}
	for _, attr := range attrs {
		switch attr.kind {
		case syscall.RTA_DST, syscall.RTA_SRC, syscall.RTA_GATEWAY, syscall.RTA_PREFSRC:
			addr, err := routeAddress(family, attr.value)
			if err != nil {
				return Route{}, err
			}
			switch attr.kind {
			case syscall.RTA_DST:
				route.Destination = netip.PrefixFrom(addr, int(data[1])).Masked().String()
			case syscall.RTA_SRC:
				route.Source = netip.PrefixFrom(addr, int(data[2])).Masked().String()
			case syscall.RTA_GATEWAY:
				route.Gateway = addr.String()
			case syscall.RTA_PREFSRC:
				route.PreferredSource = addr.String()
			}
		case syscall.RTA_OIF, syscall.RTA_PRIORITY, syscall.RTA_TABLE:
			value, err := uint32Attribute(attr.value)
			if err != nil {
				return Route{}, err
			}
			switch attr.kind {
			case syscall.RTA_OIF:
				route.InterfaceIndex = value
			case syscall.RTA_PRIORITY:
				route.Priority = value
			case syscall.RTA_TABLE:
				route.Table = value
			}
		case syscall.RTA_MULTIPATH:
			route.NextHops, err = parseNextHops(family, attr.value)
			if err != nil {
				return Route{}, err
			}
		case 18: // RTA_VIA: gateway may have a different address family.
			route.Gateway, err = parseVia(attr.value)
			if err != nil {
				return Route{}, err
			}
		}
	}
	return route, nil
}

func routeAddress(family byte, data []byte) (netip.Addr, error) {
	addr, ok := netip.AddrFromSlice(data)
	if !ok || (family == syscall.AF_INET && !addr.Is4()) || (family == syscall.AF_INET6 && !addr.Is6()) {
		return netip.Addr{}, errors.New("invalid netlink address")
	}
	return addr, nil
}

func parseVia(data []byte) (string, error) {
	if len(data) < 2 {
		return "", errors.New("truncated route via address")
	}
	family := binary.NativeEndian.Uint16(data)
	if family != syscall.AF_INET && family != syscall.AF_INET6 {
		return "", errors.New("unsupported route via family")
	}
	addr, err := routeAddress(byte(family), data[2:])
	if err != nil {
		return "", err
	}
	return addr.String(), nil
}

func parseNextHops(family byte, data []byte) ([]NextHop, error) {
	result := make([]NextHop, 0)
	for len(data) > 0 {
		if len(data) < 8 {
			return nil, errors.New("truncated route nexthop")
		}
		length := int(binary.NativeEndian.Uint16(data))
		aligned := (length + 3) &^ 3
		if length < 8 || aligned > len(data) {
			return nil, errors.New("invalid route nexthop length")
		}
		hop := NextHop{Flags: data[2], Weight: uint16(data[3]) + 1, InterfaceIndex: binary.NativeEndian.Uint32(data[4:])}
		attrs, err := attributes(data[8:length])
		if err != nil {
			return nil, err
		}
		for _, attr := range attrs {
			if attr.kind == syscall.RTA_GATEWAY {
				addr, err := routeAddress(family, attr.value)
				if err != nil {
					return nil, err
				}
				hop.Gateway = addr.String()
			}
			if attr.kind == 18 {
				hop.Gateway, err = parseVia(attr.value)
				if err != nil {
					return nil, err
				}
			}
		}
		result = append(result, hop)
		data = data[aligned:]
	}
	return result, nil
}

func (LinuxProvider) WireGuard(ctx context.Context) (WireGuardCapability, error) {
	// Dump registered Generic Netlink families. Unlike a named family lookup,
	// this cannot request automatic loading of the WireGuard kernel module.
	messages, err := dump(ctx, syscall.NETLINK_GENERIC, 0x10, []byte{3, 2, 0, 0})
	if err != nil {
		return WireGuardCapability{}, err
	}
	registered := false
	for _, message := range messages {
		if message.Header.Type != 0x10 {
			continue
		}
		if len(message.Data) < 4 {
			return WireGuardCapability{}, errors.New("truncated generic netlink header")
		}
		attrs, err := attributes(message.Data[4:])
		if err != nil {
			return WireGuardCapability{}, err
		}
		for _, attr := range attrs {
			if attr.kind == 2 && strings.TrimRight(string(attr.value), "\x00") == "wireguard" {
				registered = true
			}
		}
	}
	state, reason := "not_registered", "WireGuard kernel family is not currently registered; no module was loaded"
	if registered {
		state = "registered"
		reason = "Kernel family is registered; this agent provides observation and validation only"
	}
	return WireGuardCapability{KernelFamilyRegistered: registered, State: state, Management: false, Reason: reason}, nil
}
