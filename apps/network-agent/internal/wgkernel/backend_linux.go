// Package wgkernel implements ownership-scoped Linux WireGuard operations.
// It is used only by the explicit local runtime CLI, never by the read-only API.
package wgkernel

import (
	"context"
	"errors"
	"net/netip"
	"regexp"
	"sync"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
)

var (
	interfacePattern = regexp.MustCompile(`^ebwg[0-9a-f]{10}$`)
	ownerPattern     = regexp.MustCompile(`^[0-9a-f]{32}$`)
	errNotFound      = errors.New("kernel interface not found")
)

const ownerPrefix = "eblocker-wireguard:"

type link struct {
	index             int
	name, alias, kind string
	up                bool
}
type route struct {
	prefix netip.Prefix
	index  int
}
type snapshot struct {
	addresses []netip.Prefix
	routes    []route
}

// kernel is deliberately restricted to the operations required by this backend.
// Tests substitute an explicit in-memory implementation; production never does.
type kernel interface {
	registered(context.Context) error
	lookup(context.Context, string) (link, error)
	snapshot(context.Context) (snapshot, error)
	create(context.Context, string, string, int) (link, error)
	configure(context.Context, link, []byte) error
	address(context.Context, link, netip.Prefix) error
	route(context.Context, link, netip.Prefix) error
	up(context.Context, link) error
	peers(context.Context, link) ([]manager.PeerObservation, error)
	remove(context.Context, link) error
}

type Backend struct {
	mu     sync.Mutex
	kernel kernel
	policy fullKernel
}

// New uses native RTNETLINK and the WireGuard Generic Netlink UAPI. Construction
// does not open sockets, inspect or mutate the network, or load a kernel module.
func New() *Backend { return &Backend{kernel: nativeKernel{}, policy: nativeFullKernel{}} }

func validTarget(target manager.Target) bool {
	return interfacePattern.MatchString(target.InterfaceName) && ownerPattern.MatchString(target.OwnershipID)
}
func owned(target manager.Target, item link) bool {
	return item.name == target.InterfaceName && item.kind == "wireguard" && item.alias == ownerPrefix+target.OwnershipID && item.index > 0
}

type desired struct {
	addresses, routes []netip.Prefix
	mtu               int
	encoded           []byte
}

func prepareDesired(config wireguard.RuntimeProfile) (desired, error) {
	plan := config.Plan()
	// There is no DNS/firewall/policy-route implementation in this runtime.
	// Reject profiles needing those semantics before the first kernel mutation.
	if plan.DefaultRouteIPv4 || plan.DefaultRouteIPv6 || len(plan.DNS) > 0 || len(plan.EndpointExclusions) > 0 || len(plan.InterfaceAddresses) == 0 || len(plan.Peers) == 0 {
		return desired{}, manager.ErrUnsupportedProfile
	}
	result := desired{mtu: 1420}
	if plan.MTU != nil {
		result.mtu = int(*plan.MTU)
	}
	for _, text := range plan.InterfaceAddresses {
		prefix, err := netip.ParsePrefix(text)
		if err != nil || !safeUnicast(prefix.Addr()) {
			return desired{}, manager.ErrUnsupportedProfile
		}
		result.addresses = append(result.addresses, prefix)
	}
	for _, peer := range plan.Peers {
		if peer.Endpoint != "" {
			endpoint, err := netip.ParseAddrPort(peer.Endpoint)
			if err != nil || endpoint.Addr().Zone() != "" || endpoint.Addr().IsLinkLocalUnicast() {
				return desired{}, manager.ErrUnsupportedProfile
			}
		}
		for _, text := range peer.AllowedIPs {
			prefix, err := netip.ParsePrefix(text)
			if err != nil || prefix.Bits() == 0 || unsafeRoute(prefix) {
				return desired{}, manager.ErrUnsupportedProfile
			}
			result.routes = append(result.routes, prefix.Masked())
		}
	}
	encoded, err := encodeDevice(0, config)
	if err != nil {
		return desired{}, manager.ErrUnsupportedProfile
	}
	result.encoded = encoded
	return result, nil
}

func safeUnicast(addr netip.Addr) bool {
	return addr.IsGlobalUnicast() && !addr.Is4In6() && !addr.IsLinkLocalUnicast() && !unsafeRoute(netip.PrefixFrom(addr, addr.BitLen()))
}

var protectedPrefixes = []netip.Prefix{
	netip.MustParsePrefix("0.0.0.0/8"), netip.MustParsePrefix("127.0.0.0/8"), netip.MustParsePrefix("169.254.0.0/16"), netip.MustParsePrefix("224.0.0.0/4"), netip.MustParsePrefix("240.0.0.0/4"),
	netip.MustParsePrefix("::/128"), netip.MustParsePrefix("::1/128"), netip.MustParsePrefix("::ffff:0:0/96"), netip.MustParsePrefix("fe80::/10"), netip.MustParsePrefix("ff00::/8"),
}

func unsafeRoute(prefix netip.Prefix) bool {
	for _, protected := range protectedPrefixes {
		if prefix.Overlaps(protected) {
			return true
		}
	}
	return false
}

func (b *Backend) preflight(ctx context.Context, target manager.Target, desired desired) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	if !validTarget(target) {
		return manager.ErrOwnershipMismatch
	}
	if err := b.kernel.registered(ctx); err != nil {
		return errors.Join(manager.ErrUnavailable, err)
	}
	if _, err := b.kernel.lookup(ctx, target.InterfaceName); err == nil {
		return manager.ErrOwnershipMismatch
	} else if !errors.Is(err, errNotFound) {
		return errors.Join(manager.ErrUnavailable, err)
	}
	observed, err := b.kernel.snapshot(ctx)
	if err != nil {
		return errors.Join(manager.ErrUnavailable, err)
	}
	for _, address := range desired.addresses {
		for _, existing := range observed.addresses {
			if address.Overlaps(existing) {
				return manager.ErrUnsupportedProfile
			}
		}
	}
	for _, wanted := range desired.routes {
		for _, existing := range observed.routes {
			// A split route may override an existing default but must not take
			// over a connected or explicitly configured non-default prefix.
			if existing.prefix.IsValid() && existing.prefix.Bits() != 0 && wanted.Overlaps(existing.prefix) {
				return manager.ErrUnsupportedProfile
			}
		}
	}
	return nil
}

func (b *Backend) Prepare(ctx context.Context, target manager.Target, config wireguard.RuntimeProfile) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	desired, err := prepareDesired(config)
	if err != nil {
		return err
	}
	defer clear(desired.encoded)
	return b.preflight(ctx, target, desired)
}

func (b *Backend) Apply(ctx context.Context, target manager.Target, config wireguard.RuntimeProfile) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	desired, err := prepareDesired(config)
	if err != nil {
		return err
	}
	defer clear(desired.encoded)
	// Repeat read-only checks immediately before exclusive link creation.
	if err := b.preflight(ctx, target, desired); err != nil {
		return err
	}
	item, err := b.kernel.create(ctx, target.InterfaceName, ownerPrefix+target.OwnershipID, desired.mtu)
	if err != nil {
		return err
	}
	if !owned(target, item) {
		return manager.ErrOwnershipMismatch
	}
	encoded, err := encodeDevice(item.index, config)
	if err != nil {
		return err
	}
	defer clear(encoded)
	if err := b.check(ctx, target, item); err != nil {
		return err
	}
	if err := b.kernel.configure(ctx, item, encoded); err != nil {
		return err
	}
	for _, address := range desired.addresses {
		if err := b.check(ctx, target, item); err != nil {
			return err
		}
		if err := b.kernel.address(ctx, item, address); err != nil {
			return err
		}
	}
	// IPv6 rejects routes through a down device with ENETDOWN. Addresses use
	// NOPREFIXROUTE, so activating the configured link does not install routes.
	if err := b.check(ctx, target, item); err != nil {
		return err
	}
	if err := b.kernel.up(ctx, item); err != nil {
		return err
	}
	for _, prefix := range desired.routes {
		if err := b.check(ctx, target, item); err != nil {
			return err
		}
		if err := b.kernel.route(ctx, item, prefix); err != nil {
			return err
		}
	}
	return b.check(ctx, target, item)
}

func (b *Backend) check(ctx context.Context, target manager.Target, expected link) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	actual, err := b.kernel.lookup(ctx, target.InterfaceName)
	if err != nil {
		return err
	}
	if !owned(target, actual) || actual.index != expected.index {
		return manager.ErrOwnershipMismatch
	}
	return nil
}

func (b *Backend) Observe(ctx context.Context, target manager.Target) (manager.Observation, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !validTarget(target) {
		return manager.Observation{}, manager.ErrOwnershipMismatch
	}
	item, err := b.kernel.lookup(ctx, target.InterfaceName)
	if errors.Is(err, errNotFound) {
		return manager.Observation{Peers: []manager.PeerObservation{}}, nil
	}
	if err != nil {
		return manager.Observation{}, err
	}
	result := manager.Observation{Exists: true, Owned: owned(target, item), Up: item.up, Peers: []manager.PeerObservation{}}
	if !result.Owned {
		return result, manager.ErrOwnershipMismatch
	}
	result.Peers, err = b.kernel.peers(ctx, item)
	if err != nil {
		return manager.Observation{}, err
	}
	if err := b.check(ctx, target, item); err != nil {
		return manager.Observation{}, err
	}
	return result, nil
}

func (b *Backend) Rollback(ctx context.Context, target manager.Target) error {
	return b.Remove(ctx, target)
}

func (b *Backend) Remove(ctx context.Context, target manager.Target) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !validTarget(target) {
		return manager.ErrOwnershipMismatch
	}
	if err := ctx.Err(); err != nil {
		return err
	}
	item, err := b.kernel.lookup(ctx, target.InterfaceName)
	if errors.Is(err, errNotFound) {
		return nil
	}
	if err != nil {
		return err
	}
	if !owned(target, item) {
		return manager.ErrOwnershipMismatch
	}
	// Removing this exact owned interface also removes its attached routes and
	// addresses. There is no global route flush or deletion by prefix/name alone.
	return b.kernel.remove(ctx, item)
}
