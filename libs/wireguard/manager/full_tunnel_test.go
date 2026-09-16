//go:build linux

package manager

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
)

var fullConfig = strings.Replace(testConfig, "AllowedIPs=10.99.0.0/16", "AllowedIPs=0.0.0.0/0,::/0", 1)

// This typed driver models partial kernel state exclusively in tests. Native
// nft/netlink readback and ordering are tested in the network-agent module.
type fullBackend struct {
	*fakeBackend
	policies                                           map[string]policy.Plan
	fullApplyCalls, fullRemoveCalls, fullRollbackCalls int
	observeErr                                         error
	attestationChange                                  func(*policy.Attestation)
	policyChange                                       func(*policy.Plan)
	applyPolicyHook                                    func(Target, policy.Plan)
	lastRemovedPolicy                                  policy.Plan
}

func newFullBackend() *fullBackend {
	return &fullBackend{fakeBackend: newBackend(), policies: map[string]policy.Plan{}}
}
func (b *fullBackend) PrepareFullTunnel(_ context.Context, target Target, runtime wireguard.RuntimeProfile) (policy.Plan, error) {
	if b.prepareErr != nil {
		return policy.Plan{}, b.prepareErr
	}
	plan, err := policy.Build(target.PolicyOwner(), runtime.Plan(), policy.Environment{LocalNetworks: []string{"192.168.1.0/24"}, LocalInterfaces: []string{"eth0"}, Endpoints: []policy.Endpoint{{Address: "198.51.100.1", Port: 51820, InterfaceName: "eth0"}}})
	if b.policyChange != nil {
		b.policyChange(&plan)
	}
	return plan, err
}
func (b *fullBackend) ApplyFullTunnel(ctx context.Context, target Target, runtime wireguard.RuntimeProfile, plan policy.Plan) error {
	b.fullApplyCalls++
	if b.applyPolicyHook != nil {
		b.applyPolicyHook(target, plan)
	}
	b.policies[target.InterfaceName] = plan // Guard may exist when subsequent link configuration fails.
	return b.fakeBackend.Apply(ctx, target, runtime)
}
func (b *fullBackend) ObserveFullTunnel(ctx context.Context, target Target, plan policy.Plan) (Observation, policy.Attestation, error) {
	if b.observeErr != nil {
		return Observation{}, policy.Attestation{}, b.observeErr
	}
	observation, err := b.fakeBackend.Observe(ctx, target)
	installed, ok := b.policies[target.InterfaceName]
	att := policy.Attestation{Owner: plan.Owner, Digest: installed.Digest, RoutingVerified: ok, FirewallVerified: ok, MarkVerified: ok, EndpointsVerified: ok, IPv4PoliciesVerified: ok, IPv6PoliciesVerified: ok, KillSwitchActive: ok}
	if b.attestationChange != nil {
		b.attestationChange(&att)
	}
	return observation, att, err
}
func (b *fullBackend) RollbackFullTunnel(ctx context.Context, target Target, plan policy.Plan) error {
	b.fullRollbackCalls++
	b.lastRemovedPolicy = plan
	if err := b.fakeBackend.Rollback(ctx, target); err != nil {
		return err
	}
	delete(b.policies, target.InterfaceName)
	return nil
}
func (b *fullBackend) RemoveFullTunnel(ctx context.Context, target Target, plan policy.Plan) error {
	b.fullRemoveCalls++
	b.lastRemovedPolicy = plan
	if err := b.fakeBackend.Remove(ctx, target); err != nil {
		return err
	}
	delete(b.policies, target.InterfaceName)
	return nil
}
func readyFull(t *testing.T, b *fullBackend) (*Manager, string) {
	t.Helper()
	directory := filepath.Join(t.TempDir(), "state")
	m, err := New(directory, b)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = m.Close() })
	if err := m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	return m, directory
}

func TestFullTunnelJournalPrecedesGuardAndActiveRequiresFreshAttestation(t *testing.T) {
	b := newFullBackend()
	m, directory := readyFull(t, b)
	b.applyPolicyHook = func(target Target, plan policy.Plan) {
		stored, err := m.store.load("home")
		if err != nil || stored.Phase != PhaseApplying || stored.Policy == nil || stored.Policy.Digest != plan.Digest || stored.Target != target || stored.KillSwitchActive {
			t.Fatal("guard creation preceded durable complete policy")
		}
	}
	status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
	if err != nil {
		t.Fatal(err)
	}
	if !status.KillSwitchActive || status.Phase != PhaseActive || status.Policy == nil || status.Observation.Policy == nil || status.Plan.KillSwitchActive {
		t.Fatal("incorrect runtime/validation protection claims")
	}
	if _, err := m.Connect(t.Context(), "home", []byte(fullConfig)); err != nil || b.fullApplyCalls != 1 {
		t.Fatal("active repeat mutated resources", err)
	}
	stored, err := m.store.load("home")
	if err != nil || stored.KillSwitchActive || stored.Observation.Policy != nil {
		t.Fatal("historical observation trusted")
	}
	public, _ := json.Marshal(status)
	journal, _ := os.ReadFile(filepath.Join(directory, "home.json"))
	if strings.Contains(string(public)+string(journal), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=") {
		t.Fatal("secret in public policy")
	}
	b.observeErr = errors.New("untrusted detail")
	current, err := m.Status(t.Context(), "home")
	expectCode(t, err, "observation_failed")
	if current.KillSwitchActive || current.Observation.Policy != nil {
		t.Fatal("stale guard claim on readback failure")
	}
	b.observeErr = nil
	b.attestationChange = func(a *policy.Attestation) { a.IPv6PoliciesVerified = false }
	current, err = m.Status(t.Context(), "home")
	expectCode(t, err, "policy_not_verified")
	if current.KillSwitchActive || current.Observation.Policy.KillSwitchActive || current.Phase != PhaseRecoveryRequired {
		t.Fatal("partial attestation claims protection")
	}
	b.attestationChange = nil
	if _, err := m.Disconnect(t.Context(), "home"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Disconnect(t.Context(), "home"); err != nil || b.fullRemoveCalls != 1 || len(b.policies) != 0 {
		t.Fatal("full cleanup is not idempotent", err)
	}
}

func TestFullTunnelCannotFallBackToSplitBackend(t *testing.T) {
	b := newBackend()
	m, directory := openReady(t, b)
	status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
	expectCode(t, err, "unsupported_profile")
	if b.applyCalls != 0 || status.KillSwitchActive {
		t.Fatal("unguarded full tunnel applied")
	}
	if _, err := os.Stat(filepath.Join(directory, "home.conf")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("unsupported profile retained")
	}
}

func TestFullTunnelRejectsNoncanonicalPreparedPolicy(t *testing.T) {
	b := newFullBackend()
	m, _ := readyFull(t, b)
	b.policyChange = func(p *policy.Plan) { p.FirewallMark++ }
	_, err := m.Connect(t.Context(), "home", []byte(fullConfig))
	expectCode(t, err, "unsupported_profile")
	if b.fullApplyCalls != 0 {
		t.Fatal("unvalidated policy mutated resources")
	}
}

func TestFullTunnelPartialApplyAndAttestationFailureRollbackExactPolicy(t *testing.T) {
	for _, kind := range []string{"partial_apply", "attestation", "rollback_retry"} {
		t.Run(kind, func(t *testing.T) {
			b := newFullBackend()
			m, _ := readyFull(t, b)
			if kind == "attestation" {
				b.attestationChange = func(a *policy.Attestation) { a.FirewallVerified = false }
			} else {
				b.applyErr = errors.New("partial")
			}
			if kind == "rollback_retry" {
				b.rollbackErr = errors.New("busy")
			}
			status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
			code := "apply_failed"
			if kind == "attestation" {
				code = "policy_not_verified"
			}
			if kind == "rollback_retry" {
				code = "rollback_failed"
			}
			expectCode(t, err, code)
			if status.KillSwitchActive || b.fullRollbackCalls != 1 || b.lastRemovedPolicy.Digest != status.Policy.Digest {
				t.Fatal("rollback did not use journal policy")
			}
			if kind == "rollback_retry" {
				if status.Phase != PhaseRecoveryRequired || len(b.policies) != 1 {
					t.Fatal("partial guard state lost")
				}
				b.rollbackErr = nil
				if err := m.Recover(t.Context()); err != nil {
					t.Fatal(err)
				}
			}
			if len(b.policies) != 0 || len(b.resources) != 0 {
				t.Fatal("owned resources left after cleanup")
			}
		})
	}
}

func TestFullTunnelCrashRecoveryUsesJournaledPolicyAndRejectsTampering(t *testing.T) {
	for _, phase := range []string{PhaseApplying, PhaseActive, PhaseRemoving, PhaseRollingBack} {
		t.Run(phase, func(t *testing.T) {
			b := newFullBackend()
			m, directory := readyFull(t, b)
			status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
			if err != nil {
				t.Fatal(err)
			}
			status.Phase = phase
			if err := m.store.save(status); err != nil {
				t.Fatal(err)
			}
			_ = m.Close()
			reopened, err := New(directory, b)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			if err := reopened.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			if phase == PhaseActive {
				if len(b.policies) != 1 || b.fullRollbackCalls != 0 {
					t.Fatal("verified active policy removed")
				}
			} else if len(b.policies) != 0 || b.lastRemovedPolicy.Digest != status.Policy.Digest {
				t.Fatal("crashed policy abandoned")
			}
		})
	}
	t.Run("tampering", func(t *testing.T) {
		b := newFullBackend()
		m, _ := readyFull(t, b)
		status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
		if err != nil {
			t.Fatal(err)
		}
		status.Policy.NFTTable = "foreign"
		if err := m.store.save(status); err != nil {
			t.Fatal(err)
		}
		expectCode(t, m.Recover(t.Context()), "storage_failed")
		if b.fullRollbackCalls != 0 {
			t.Fatal("tampered journal touched kernel")
		}
	})
	t.Run("active_policy_missing", func(t *testing.T) {
		b := newFullBackend()
		m, _ := readyFull(t, b)
		status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
		if err != nil {
			t.Fatal(err)
		}
		delete(b.policies, status.Target.InterfaceName)
		if err := m.Recover(t.Context()); err != nil {
			t.Fatal(err)
		}
		if b.fullRollbackCalls != 1 || len(b.resources) != 0 {
			t.Fatal("active interface without guard retained")
		}
	})
}

func TestFullTunnelRecoveryWithoutCapableBackendDoesNotDiscardGuard(t *testing.T) {
	b := newFullBackend()
	m, directory := readyFull(t, b)
	status, err := m.Connect(t.Context(), "home", []byte(fullConfig))
	if err != nil {
		t.Fatal(err)
	}
	status.Phase = PhaseApplying
	if err := m.store.save(status); err != nil {
		t.Fatal(err)
	}
	_ = m.Close()
	reopened, err := New(directory, b.fakeBackend)
	if err != nil {
		t.Fatal(err)
	}
	defer reopened.Close()
	expectCode(t, reopened.Recover(t.Context()), "recovery_failed")
	if b.rollbackCalls != 0 || len(b.policies) != 1 || len(b.resources) != 1 {
		t.Fatal("split backend performed unsafe incomplete cleanup")
	}
}

func TestFullTunnelCancelAndDisconnectRetryKeepExactCleanupScope(t *testing.T) {
	t.Run("cancel", func(t *testing.T) {
		b := newFullBackend()
		b.started = make(chan struct{})
		m, _ := readyFull(t, b)
		result := make(chan error, 1)
		go func() { _, err := m.Connect(t.Context(), "home", []byte(fullConfig)); result <- err }()
		<-b.started
		if !m.Cancel("home") {
			t.Fatal("operation not cancellable")
		}
		expectCode(t, <-result, "cancelled")
		if b.rollbackCancelled || b.fullRollbackCalls != 1 || len(b.policies) != 0 {
			t.Fatal("guard rollback used cancelled context")
		}
	})
	t.Run("disconnect_retry", func(t *testing.T) {
		b := newFullBackend()
		m, _ := readyFull(t, b)
		connected, err := m.Connect(t.Context(), "home", []byte(fullConfig))
		if err != nil {
			t.Fatal(err)
		}
		b.removeErr = errors.New("busy")
		status, err := m.Disconnect(t.Context(), "home")
		expectCode(t, err, "remove_failed")
		if status.Phase != PhaseRecoveryRequired || status.KillSwitchActive || len(b.policies) != 1 {
			t.Fatal("incomplete cleanup discarded guard state")
		}
		b.removeErr = nil
		status, err = m.Disconnect(t.Context(), "home")
		if err != nil || status.Phase != PhaseDisconnected || b.lastRemovedPolicy.Digest != connected.Policy.Digest || len(b.policies) != 0 {
			t.Fatal("retry did not finish exact cleanup", err)
		}
	})
}
