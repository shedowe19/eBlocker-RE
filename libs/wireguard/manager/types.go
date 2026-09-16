// Package manager orchestrates crash-recoverable, ownership-scoped WireGuard
// operations. A native backend must be explicitly supplied; there is no shell
// implementation and construction alone never changes network configuration.
package manager

import (
	"context"
	"errors"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
)

var (
	ErrUnsupportedProfile = errors.New("unsupported WireGuard runtime profile")
	ErrOwnershipMismatch  = errors.New("WireGuard resource ownership mismatch")
	ErrUnavailable        = errors.New("WireGuard backend unavailable")
)

type Target struct {
	ProfileID     string `json:"profileId"`
	InterfaceName string `json:"interfaceName"`
	OwnershipID   string `json:"ownershipId"`
}

type PeerObservation struct {
	PublicKey         string `json:"publicKey"`
	LastHandshakeUnix int64  `json:"lastHandshakeUnix"`
	ReceiveBytes      uint64 `json:"receiveBytes"`
	TransmitBytes     uint64 `json:"transmitBytes"`
}

type Observation struct {
	Exists bool                `json:"exists"`
	Owned  bool                `json:"owned"`
	Up     bool                `json:"up"`
	Peers  []PeerObservation   `json:"peers"`
	Policy *policy.Attestation `json:"policy,omitempty"`
}

// Prepare must be read-only and reject unsupported DNS, routing or firewall
// semantics before Apply. Apply may create only this exact target. Rollback and
// Remove must be idempotent, verify persistent ownership, and refuse foreign
// resources, even when recovering after a process crash.
type Backend interface {
	Prepare(context.Context, Target, wireguard.RuntimeProfile) error
	Apply(context.Context, Target, wireguard.RuntimeProfile) error
	Observe(context.Context, Target) (Observation, error)
	Rollback(context.Context, Target) error
	Remove(context.Context, Target) error
}

// FullTunnelBackend is optional. Split-only backends remain supported and must
// never receive a full-tunnel profile through their unguarded Apply operation.
// The exact policy is durable before ApplyFullTunnel. Its firewall guard must
// precede routing mutations and be removed last during rollback/disconnection.
type FullTunnelBackend interface {
	PrepareFullTunnel(context.Context, Target, wireguard.RuntimeProfile) (policy.Plan, error)
	ApplyFullTunnel(context.Context, Target, wireguard.RuntimeProfile, policy.Plan) error
	ObserveFullTunnel(context.Context, Target, policy.Plan) (Observation, policy.Attestation, error)
	RollbackFullTunnel(context.Context, Target, policy.Plan) error
	RemoveFullTunnel(context.Context, Target, policy.Plan) error
}

func (t Target) PolicyOwner() policy.Owner {
	return policy.Owner{InterfaceName: t.InterfaceName, OwnershipID: t.OwnershipID}
}

const (
	PhasePrepared         = "prepared"
	PhaseApplying         = "applying"
	PhaseActive           = "active"
	PhaseRemoving         = "removing"
	PhaseRollingBack      = "rolling_back"
	PhaseDisconnected     = "disconnected"
	PhaseFailed           = "failed"
	PhaseCancelled        = "cancelled"
	PhaseRecoveryRequired = "recovery_required"
)

// Active requires an owned/up interface and its expected peers. A full tunnel
// additionally requires fresh routing/firewall attestation. KillSwitchActive
// describes this verified policy only, never a handshake or working DNS/VPN.
type Status struct {
	SchemaVersion    int            `json:"schemaVersion"`
	Target           Target         `json:"target"`
	Phase            string         `json:"phase"`
	Plan             wireguard.Plan `json:"plan"`
	Observation      Observation    `json:"observation"`
	ErrorCode        string         `json:"errorCode,omitempty"`
	Policy           *policy.Plan   `json:"policy,omitempty"`
	KillSwitchActive bool           `json:"killSwitchActive"`
}

type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (e *Error) Error() string { return e.Code + ": " + e.Message }

func failure(code string) error {
	messages := map[string]string{
		"invalid_profile": "WireGuard profile is invalid.", "invalid_id": "Profile identifier is invalid.",
		"conflict": "Disconnect the existing profile before changing its configuration.", "not_found": "Profile was not found.",
		"storage_failed": "Private lifecycle storage is unavailable.", "closed": "Lifecycle manager is closed.",
		"unsupported_profile": "The native backend cannot safely apply this profile.", "ownership_mismatch": "A network resource is not owned by this profile.",
		"backend_unavailable": "The native backend is unavailable.", "apply_failed": "Applying the profile failed; inspect the lifecycle state.",
		"observation_failed": "The owned interface could not be observed.", "rollback_failed": "Rollback is incomplete; recovery is required.",
		"remove_failed": "Disconnect is incomplete; retry or recover before reconnecting.", "cancelled": "The operation was cancelled.",
		"recovery_failed":     "Recovery is incomplete; inspect each profile before retrying.",
		"policy_not_verified": "Full-tunnel routing and firewall protection could not be verified.",
		"profile_limit":       "The private profile store has reached its capacity.",
	}
	return &Error{Code: code, Message: messages[code]}
}

func backendCode(err error, fallback string) string {
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return "cancelled"
	case errors.Is(err, ErrUnsupportedProfile):
		return "unsupported_profile"
	case errors.Is(err, ErrOwnershipMismatch):
		return "ownership_mismatch"
	case errors.Is(err, ErrUnavailable):
		return "backend_unavailable"
	default:
		return fallback
	}
}
