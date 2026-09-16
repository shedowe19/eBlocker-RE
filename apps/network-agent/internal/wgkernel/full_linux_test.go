package wgkernel

import (
	"context"
	"errors"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
)

func fullConfig() string {
	return strings.Replace(runtimeConfig(), "10.254.0.0/24,fd00:abce::/64", "0.0.0.0/0,::/0", 1)
}

type fakeFullKernel struct {
	kernel                   *fakeKernel
	env                      policy.Environment
	p                        policy.Plan
	exists, verified, routes bool
	fail                     string
	failOnce                 bool
	markOverride             bool
	endpointDrift            bool
	mutations                []string
}

func fakeFull() *fakeFullKernel {
	return &fakeFullKernel{kernel: &fakeKernel{}, env: policy.Environment{LocalNetworks: []string{"192.0.2.0/24", "2001:db8:1::/64"}, LocalInterfaces: []string{"eth0"}, Endpoints: []policy.Endpoint{{Address: "198.51.100.50", Port: 51820, InterfaceName: "eth0"}}}}
}
func (f *fakeFullKernel) record(name string) error {
	f.mutations = append(f.mutations, name)
	if f.fail == name {
		if f.failOnce {
			f.fail = ""
		}
		return errors.New("injected full-tunnel failure")
	}
	return nil
}
func (f *fakeFullKernel) environment(context.Context, manager.Target, wireguard.Plan) (policy.Environment, error) {
	return f.env, nil
}
func (f *fakeFullKernel) preflight(context.Context, policy.Plan) error {
	if f.fail == "preflight" {
		return manager.ErrOwnershipMismatch
	}
	return nil
}
func (f *fakeFullKernel) installGuard(_ context.Context, p policy.Plan) error {
	if err := f.record("guard"); err != nil {
		return err
	}
	f.p = p
	f.exists = true
	f.verified = true
	return nil
}
func (f *fakeFullKernel) guard(context.Context, policy.Plan) (bool, bool, error) {
	return f.exists, f.exists && f.verified, nil
}
func (f *fakeFullKernel) installRouting(context.Context, link, policy.Plan) error {
	if !f.exists || !f.verified {
		panic("route before guard")
	}
	f.routes = true
	return f.record("routing")
}
func (f *fakeFullKernel) routing(context.Context, link, policy.Plan) (bool, bool, error) {
	if f.fail == "readback" {
		return false, false, manager.ErrUnavailable
	}
	return f.routes, f.routes, nil
}
func (f *fakeFullKernel) endpoints(context.Context, policy.Plan) (bool, error) {
	return !f.endpointDrift, nil
}
func (f *fakeFullKernel) mark(context.Context, link) (uint32, error) {
	if f.markOverride {
		return 0, nil
	}
	return f.p.FirewallMark, nil
}
func (f *fakeFullKernel) removeRouting(context.Context, link, policy.Plan) error {
	if err := f.record("removeRouting"); err != nil {
		return err
	}
	if !f.exists || !f.verified {
		panic("remove routes without guard")
	}
	f.routes = false
	return nil
}
func (f *fakeFullKernel) removeGuard(context.Context, policy.Plan) error {
	if f.kernel.item != nil || f.routes {
		panic("guard removed before resources")
	}
	if err := f.record("removeGuard"); err != nil {
		return err
	}
	f.exists = false
	f.verified = false
	return nil
}
func (f *fakeFullKernel) backend() *Backend { return &Backend{kernel: f.kernel, policy: f} }
func fullPlan(t *testing.T) policy.Plan {
	t.Helper()
	var result policy.Plan
	err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error {
		var err error
		result, err = policy.Build(testTarget.PolicyOwner(), r.Plan(), fakeFull().env)
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	return result
}
func TestFullTunnelGuardBeforeNetworkAndLastDuringCleanup(t *testing.T) {
	f := fakeFull()
	f.kernel.onMutation = func(op string) {
		if op != "remove" && (!f.exists || !f.verified) {
			t.Fatalf("%s before guard", op)
		}
	}
	b := f.backend()
	var p policy.Plan
	err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error {
		var err error
		p, err = b.PrepareFullTunnel(t.Context(), testTarget, r)
		if err != nil {
			return err
		}
		if len(f.mutations) != 0 || len(f.kernel.mutations) != 0 {
			t.Fatal("preflight mutated")
		}
		return b.ApplyFullTunnel(t.Context(), testTarget, r, p)
	})
	if err != nil {
		t.Fatal(err)
	}
	_, a, err := b.ObserveFullTunnel(t.Context(), testTarget, p)
	if err != nil || !a.Matches(p) {
		t.Fatalf("missing attestation: %#v %v", a, err)
	}
	if err = b.RemoveFullTunnel(t.Context(), testTarget, p); err != nil {
		t.Fatal(err)
	}
	if f.exists || f.routes || f.kernel.item != nil {
		t.Fatal("resources survived cleanup")
	}
	if !slices.Equal(f.mutations, []string{"guard", "routing", "removeRouting", "removeGuard"}) {
		t.Fatal(f.mutations)
	}
}
func TestFullTunnelManagerRollsBackEveryPartialFailure(t *testing.T) {
	for _, failure := range []string{"guard", "create", "configure", "address", "routing", "up", "observe", "readback"} {
		t.Run(failure, func(t *testing.T) {
			f := fakeFull()
			f.fail = failure
			f.kernel.fail = failure
			// Fail the initial transaction once, so rollback can establish the guard.
			if failure == "guard" {
				f.failOnce = true
			}
			m, err := manager.New(filepath.Join(t.TempDir(), "state"), f.backend())
			if err != nil {
				t.Fatal(err)
			}
			defer m.Close()
			if err = m.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			status, err := m.Connect(t.Context(), "work", []byte(fullConfig()))
			if err == nil || status.KillSwitchActive || status.Phase != manager.PhaseFailed || f.exists || f.routes || f.kernel.item != nil {
				t.Fatalf("partial failure survived: %s %v %#v", status.Phase, err, f.mutations)
			}
		})
	}
}

func TestMissingGuardIsRestoredBeforeCleanup(t *testing.T) {
	f := fakeFull()
	b := f.backend()
	p := fullPlan(t)
	if err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error { return b.ApplyFullTunnel(t.Context(), testTarget, r, p) }); err != nil {
		t.Fatal(err)
	}
	f.exists = false
	f.verified = false
	f.mutations = nil
	if err := b.RemoveFullTunnel(t.Context(), testTarget, p); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(f.mutations, []string{"guard", "removeRouting", "removeGuard"}) {
		t.Fatal("guard was not restored before cleanup", f.mutations)
	}
}
func TestFullTunnelCleanupFailuresRetainGuard(t *testing.T) {
	for _, failure := range []string{"removeRouting", "remove", "removeGuard"} {
		t.Run(failure, func(t *testing.T) {
			f := fakeFull()
			b := f.backend()
			p := fullPlan(t)
			if err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error { return b.ApplyFullTunnel(t.Context(), testTarget, r, p) }); err != nil {
				t.Fatal(err)
			}
			f.fail = failure
			f.kernel.fail = failure
			if err := b.RemoveFullTunnel(t.Context(), testTarget, p); err == nil {
				t.Fatal("cleanup unexpectedly succeeded")
			}
			if !f.exists {
				t.Fatal("guard lost on partial cleanup")
			}
			f.fail = ""
			f.kernel.fail = ""
			if err := b.RemoveFullTunnel(t.Context(), testTarget, p); err != nil {
				t.Fatal(err)
			}
		})
	}
}
func TestFullTunnelDriftNeverAttestsProtection(t *testing.T) {
	for _, drift := range []string{"firewall", "mark", "endpoint", "routing", "link"} {
		t.Run(drift, func(t *testing.T) {
			f := fakeFull()
			b := f.backend()
			p := fullPlan(t)
			if err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error { return b.ApplyFullTunnel(t.Context(), testTarget, r, p) }); err != nil {
				t.Fatal(err)
			}
			switch drift {
			case "firewall":
				f.verified = false
			case "mark":
				f.markOverride = true
			case "endpoint":
				f.endpointDrift = true
			case "routing":
				f.routes = false
			case "link":
				f.kernel.item.up = false
			}
			_, a, _ := b.ObserveFullTunnel(t.Context(), testTarget, p)
			if a.Matches(p) || a.KillSwitchActive {
				t.Fatal("drift still protected")
			}
		})
	}
}
func TestFullTunnelRecoveryRefusesForeignResources(t *testing.T) {
	for _, foreign := range []string{"guard", "link", "digest", "owner"} {
		t.Run(foreign, func(t *testing.T) {
			f := fakeFull()
			b := f.backend()
			p := fullPlan(t)
			if err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error { return b.ApplyFullTunnel(t.Context(), testTarget, r, p) }); err != nil {
				t.Fatal(err)
			}
			before := len(f.mutations)
			switch foreign {
			case "guard":
				f.verified = false
			case "link":
				f.kernel.item.alias = "foreign"
			case "digest":
				p.Digest = strings.Repeat("0", 64)
			case "owner":
				p.Owner.OwnershipID = strings.Repeat("0", 32)
			}
			if err := b.RemoveFullTunnel(t.Context(), testTarget, p); err == nil {
				t.Fatal("foreign cleanup accepted")
			}
			if len(f.mutations) != before || !f.exists {
				t.Fatal("foreign resources changed")
			}
		})
	}
}
func TestFullTunnelPrepareRejectsUnsupportedAndEnvironmentDrift(t *testing.T) {
	for _, config := range []string{strings.Replace(fullConfig(), "[Peer]", "DNS=192.0.2.53\n[Peer]", 1), strings.Replace(fullConfig(), "198.51.100.50:51820", "vpn.example.org:51820", 1)} {
		f := fakeFull()
		if err := withProfile(t, config, func(r wireguard.RuntimeProfile) error {
			_, err := f.backend().PrepareFullTunnel(t.Context(), testTarget, r)
			return err
		}); err == nil {
			t.Fatal("unsupported accepted")
		}
		if len(f.mutations) != 0 {
			t.Fatal("preflight mutated")
		}
	}
	f := fakeFull()
	p := fullPlan(t)
	f.env.LocalNetworks = []string{"192.0.3.0/24"}
	if err := withProfile(t, fullConfig(), func(r wireguard.RuntimeProfile) error {
		return f.backend().ApplyFullTunnel(t.Context(), testTarget, r, p)
	}); err == nil || len(f.mutations) != 0 {
		t.Fatal("environment changed between prepare and apply")
	}
}
func TestFullTunnelCancellationKeepsGuardUntilRollback(t *testing.T) {
	f := fakeFull()
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	f.kernel.onMutation = func(op string) {
		if op == "configure" {
			cancel()
		}
	}
	m, err := manager.New(filepath.Join(t.TempDir(), "state"), f.backend())
	if err != nil {
		t.Fatal(err)
	}
	defer m.Close()
	if err = m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	status, err := m.Connect(ctx, "work", []byte(fullConfig()))
	if err == nil || status.Phase != manager.PhaseCancelled || f.exists || f.kernel.item != nil {
		t.Fatalf("cancel rollback failed: %s %v", status.Phase, err)
	}
}
