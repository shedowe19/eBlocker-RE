package wgkernel

import (
	"context"
	"errors"
	"net/netip"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
	mdnetlink "github.com/mdlayher/netlink"
)

// fullKernel is separate from the split-tunnel transport: the read-only service
// never constructs a manager or calls this interface.
type fullKernel interface {
	environment(context.Context, manager.Target, wireguard.Plan) (policy.Environment, error)
	preflight(context.Context, policy.Plan) error
	installGuard(context.Context, policy.Plan) error
	guard(context.Context, policy.Plan) (bool, bool, error) // exists, exact readback
	installRouting(context.Context, link, policy.Plan) error
	routing(context.Context, link, policy.Plan) (bool, bool, error)
	endpoints(context.Context, policy.Plan) (bool, error)
	mark(context.Context, link) (uint32, error)
	removeRouting(context.Context, link, policy.Plan) error
	removeGuard(context.Context, policy.Plan) error
}

var _ manager.FullTunnelBackend = (*Backend)(nil)

func fullDesired(config wireguard.RuntimeProfile) (desired, error) {
	plan := config.Plan()
	if (!plan.DefaultRouteIPv4 && !plan.DefaultRouteIPv6) || len(plan.DNS) != 0 || len(plan.InterfaceAddresses) == 0 || len(plan.Peers) == 0 {
		return desired{}, manager.ErrUnsupportedProfile
	}
	result := desired{mtu: 1420}
	if plan.MTU != nil {
		result.mtu = int(*plan.MTU)
	}
	for _, text := range plan.InterfaceAddresses {
		p, err := netip.ParsePrefix(text)
		if err != nil || !safeUnicast(p.Addr()) {
			return desired{}, manager.ErrUnsupportedProfile
		}
		result.addresses = append(result.addresses, p)
	}
	encoded, err := encodeDevice(0, config)
	if err != nil {
		return desired{}, err
	}
	clear(encoded)
	return result, nil
}

func (b *Backend) prepareFull(ctx context.Context, target manager.Target, config wireguard.RuntimeProfile) (policy.Plan, desired, error) {
	d, err := fullDesired(config)
	if err != nil {
		return policy.Plan{}, d, err
	}
	if b.policy == nil {
		return policy.Plan{}, d, manager.ErrUnavailable
	}
	if err = b.preflight(ctx, target, d); err != nil {
		return policy.Plan{}, d, err
	}
	env, err := b.policy.environment(ctx, target, config.Plan())
	if err != nil {
		return policy.Plan{}, d, err
	}
	p, err := policy.Build(target.PolicyOwner(), config.Plan(), env)
	if err != nil {
		return policy.Plan{}, d, manager.ErrUnsupportedProfile
	}
	if err = b.policy.preflight(ctx, p); err != nil {
		return policy.Plan{}, d, err
	}
	return p, d, nil
}
func (b *Backend) PrepareFullTunnel(ctx context.Context, target manager.Target, config wireguard.RuntimeProfile) (policy.Plan, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	p, _, err := b.prepareFull(ctx, target, config)
	return p, err
}

func (b *Backend) ApplyFullTunnel(ctx context.Context, target manager.Target, config wireguard.RuntimeProfile, p policy.Plan) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if err := p.Validate(target.PolicyOwner(), config.Plan()); err != nil {
		return manager.ErrOwnershipMismatch
	}
	current, d, err := b.prepareFull(ctx, target, config)
	if err != nil {
		return err
	}
	if current.Digest != p.Digest {
		return manager.ErrUnsupportedProfile
	}
	// The inet output/forward DROP guard is committed atomically before creating
	// any link or route. The manager has already durably journaled this exact plan.
	if err = b.policy.installGuard(ctx, p); err != nil {
		return err
	}
	if err = b.requireGuard(ctx, p); err != nil {
		return err
	}
	item, err := b.kernel.create(ctx, target.InterfaceName, ownerPrefix+target.OwnershipID, d.mtu)
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
	// append may allocate: erase both the original and final key-bearing buffers.
	defer func() { clear(encoded) }()
	mark := mdnetlink.NewAttributeEncoder()
	mark.Uint32(7, p.FirewallMark)
	data, err := mark.Encode()
	if err != nil {
		return err
	}
	encoded = append(encoded, data...)
	if err = b.check(ctx, target, item); err != nil {
		return err
	}
	if err = b.kernel.configure(ctx, item, encoded); err != nil {
		return err
	}
	for _, address := range d.addresses {
		if err = b.check(ctx, target, item); err != nil {
			return err
		}
		if err = b.kernel.address(ctx, item, address); err != nil {
			return err
		}
	}
	if err = b.requireGuard(ctx, p); err != nil {
		return err
	}
	if err = b.policy.installRouting(ctx, item, p); err != nil {
		return err
	}
	if err = b.check(ctx, target, item); err != nil {
		return err
	}
	if err = b.kernel.up(ctx, item); err != nil {
		return err
	}
	_, attestation, err := b.observeFull(ctx, target, p)
	if err != nil {
		return err
	}
	if !attestation.Matches(p) {
		return manager.ErrUnavailable
	}
	return nil
}
func (b *Backend) requireGuard(ctx context.Context, p policy.Plan) error {
	exists, verified, err := b.policy.guard(ctx, p)
	if err != nil {
		return err
	}
	if !exists || !verified {
		return manager.ErrOwnershipMismatch
	}
	return nil
}
func validFullTarget(target manager.Target, p policy.Plan) bool {
	return validTarget(target) && p.Owner == target.PolicyOwner() && p.ValidDigest()
}
func (b *Backend) ObserveFullTunnel(ctx context.Context, target manager.Target, p policy.Plan) (manager.Observation, policy.Attestation, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.observeFull(ctx, target, p)
}
func (b *Backend) observeFull(ctx context.Context, target manager.Target, p policy.Plan) (manager.Observation, policy.Attestation, error) {
	a := policy.Attestation{Owner: p.Owner, Digest: p.Digest}
	o := manager.Observation{Peers: []manager.PeerObservation{}}
	if !validFullTarget(target, p) || b.policy == nil {
		return o, a, manager.ErrOwnershipMismatch
	}
	item, err := b.kernel.lookup(ctx, target.InterfaceName)
	if errors.Is(err, errNotFound) {
		return o, a, nil
	}
	if err != nil {
		return o, a, err
	}
	o.Exists = true
	o.Owned = owned(target, item)
	o.Up = item.up
	if !o.Owned {
		return o, a, manager.ErrOwnershipMismatch
	}
	o.Peers, err = b.kernel.peers(ctx, item)
	if err != nil {
		return o, a, err
	}
	_, a.FirewallVerified, err = b.policy.guard(ctx, p)
	if err != nil {
		return o, a, err
	}
	a.IPv4PoliciesVerified, a.IPv6PoliciesVerified, err = b.policy.routing(ctx, item, p)
	if err != nil {
		return o, a, err
	}
	a.RoutingVerified = a.IPv4PoliciesVerified && a.IPv6PoliciesVerified
	mark, err := b.policy.mark(ctx, item)
	if err != nil {
		return o, a, err
	}
	a.MarkVerified = mark == p.FirewallMark
	a.EndpointsVerified, err = b.policy.endpoints(ctx, p)
	if err != nil {
		return o, a, err
	}
	if err = b.check(ctx, target, item); err != nil {
		return o, a, err
	}
	a.KillSwitchActive = o.Up && a.FirewallVerified && a.RoutingVerified && a.MarkVerified && a.EndpointsVerified
	o.Policy = &a
	return o, a, nil
}
func (b *Backend) RollbackFullTunnel(ctx context.Context, t manager.Target, p policy.Plan) error {
	return b.RemoveFullTunnel(ctx, t, p)
}
func (b *Backend) RemoveFullTunnel(ctx context.Context, t manager.Target, p policy.Plan) error {
	b.mu.Lock()
	defer b.mu.Unlock()
	if !validFullTarget(t, p) || b.policy == nil {
		return manager.ErrOwnershipMismatch
	}
	item, err := b.kernel.lookup(ctx, t.InterfaceName)
	if err != nil && !errors.Is(err, errNotFound) {
		return err
	}
	if err == nil && !owned(t, item) {
		return manager.ErrOwnershipMismatch
	}
	exists, verified, err := b.policy.guard(ctx, p)
	if err != nil {
		return err
	}
	if exists && !verified {
		return manager.ErrOwnershipMismatch
	}
	// A missing guard after a crash is re-established before touching routes.
	// Exclusive table creation cannot take over an existing foreign table.
	if !exists {
		if err = b.policy.installGuard(ctx, p); err != nil {
			return err
		}
		if err = b.requireGuard(ctx, p); err != nil {
			return err
		}
	}
	if err = b.policy.removeRouting(ctx, item, p); err != nil {
		return err
	}
	if item.index != 0 {
		if err = b.check(ctx, t, item); err != nil {
			return err
		}
		if err = b.kernel.remove(ctx, item); err != nil {
			return err
		}
	}
	// Remove only our complete, unchanged table, and only after routes/link are gone.
	return b.policy.removeGuard(ctx, p)
}
