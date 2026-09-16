//go:build linux

package manager

import (
	"context"

	"github.com/eblocker/eblocker/libs/wireguard"
)

func fullTunnel(plan wireguard.Plan) bool { return plan.DefaultRouteIPv4 || plan.DefaultRouteIPv6 }

func (m *Manager) prepare(ctx context.Context, status *Status, runtime wireguard.RuntimeProfile) error {
	if !fullTunnel(status.Plan) {
		return m.backend.Prepare(ctx, status.Target, runtime)
	}
	backend, ok := m.backend.(FullTunnelBackend)
	if !ok {
		return ErrUnsupportedProfile
	}
	plan, err := backend.PrepareFullTunnel(ctx, status.Target, runtime)
	if err != nil {
		return err
	}
	if err := plan.Validate(status.Target.PolicyOwner(), status.Plan); err != nil {
		return ErrUnsupportedProfile
	}
	status.Policy = &plan
	return nil
}

func (m *Manager) apply(ctx context.Context, status Status, runtime wireguard.RuntimeProfile) error {
	if status.Policy == nil {
		return m.backend.Apply(ctx, status.Target, runtime)
	}
	backend, ok := m.backend.(FullTunnelBackend)
	if !ok {
		return ErrUnsupportedProfile
	}
	return backend.ApplyFullTunnel(ctx, status.Target, runtime, *status.Policy)
}

func (m *Manager) observeBackend(ctx context.Context, status Status) (Observation, error) {
	if status.Policy == nil {
		observation, err := m.backend.Observe(ctx, status.Target)
		// A split backend cannot make a full-tunnel protection claim.
		observation.Policy = nil
		return observation, err
	}
	backend, ok := m.backend.(FullTunnelBackend)
	if !ok {
		return Observation{}, ErrUnsupportedProfile
	}
	observation, attestation, err := backend.ObserveFullTunnel(ctx, status.Target, *status.Policy)
	if err != nil {
		return Observation{}, err
	}
	attestation.KillSwitchActive = attestation.Matches(*status.Policy)
	observation.Policy = &attestation
	return observation, nil
}

func (m *Manager) release(ctx context.Context, status Status, rollback bool) error {
	if status.Policy != nil {
		backend, ok := m.backend.(FullTunnelBackend)
		if !ok {
			// Never use split cleanup: it would abandon policy routing/firewall.
			return ErrUnsupportedProfile
		}
		if rollback {
			return backend.RollbackFullTunnel(ctx, status.Target, *status.Policy)
		}
		return backend.RemoveFullTunnel(ctx, status.Target, *status.Policy)
	}
	if rollback {
		return m.backend.Rollback(ctx, status.Target)
	}
	return m.backend.Remove(ctx, status.Target)
}

func policyVerified(status Status, observation Observation) bool {
	return status.Policy != nil && observation.Policy != nil && observation.Policy.Matches(*status.Policy)
}

func observationCode(status Status, observation Observation) string {
	if status.Policy != nil && !policyVerified(status, observation) {
		return "policy_not_verified"
	}
	return "observation_failed"
}

func clearProtection(status *Status) {
	status.KillSwitchActive = false
	status.Observation.Policy = nil
}
