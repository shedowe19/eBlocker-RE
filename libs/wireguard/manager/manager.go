//go:build linux

package manager

import (
	"bytes"
	"context"
	"errors"
	"os"
	"sync"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard"
)

type Manager struct {
	mu               sync.Mutex
	jobsMu           sync.Mutex
	jobs             map[string]context.CancelFunc
	store            *store
	backend          Backend
	closed           bool
	recovered        bool
	startupRecovered bool
}

// New takes an exclusive process lock and validates private storage. It performs
// no kernel operation; the caller must explicitly Recover before serving writes.
func New(directory string, backend Backend) (*Manager, error) {
	if backend == nil {
		return nil, failure("backend_unavailable")
	}
	state, err := openStore(directory)
	if err != nil {
		return nil, err
	}
	return &Manager{store: state, backend: backend, jobs: map[string]context.CancelFunc{}}, nil
}

func (m *Manager) Close() error {
	m.jobsMu.Lock()
	for _, cancel := range m.jobs {
		cancel()
	}
	m.jobsMu.Unlock()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return nil
	}
	m.closed = true
	return m.store.lock.Close()
}

// Cancel requests cancellation without waiting for the current operation. A
// cancelled Apply is rolled back with its own timeout, not the cancelled context.
func (m *Manager) Cancel(id string) bool {
	m.jobsMu.Lock()
	defer m.jobsMu.Unlock()
	if cancel, ok := m.jobs[id]; ok {
		cancel()
		return true
	}
	return false
}

func (m *Manager) Connect(ctx context.Context, id string, configuration []byte) (Status, error) {
	if !profileID.MatchString(id) {
		return Status{}, failure("invalid_id")
	}
	if len(configuration) > wireguard.MaxConfigBytes {
		return Status{}, failure("invalid_profile")
	}
	configuration = append([]byte(nil), configuration...)
	defer clear(configuration)
	profile, err := wireguard.Parse(configuration)
	if err != nil {
		return Status{}, failure("invalid_profile")
	}
	defer profile.Destroy()
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.connectLocked(ctx, id, configuration, profile)
}

func (m *Manager) connectLocked(ctx context.Context, id string, configuration []byte, profile *wireguard.Profile) (Status, error) {
	if m.closed {
		return Status{}, failure("closed")
	}
	if !m.recovered {
		return Status{}, failure("recovery_failed")
	}
	if ctx.Err() != nil {
		return Status{}, failure("cancelled")
	}
	previous, err := m.store.load(id)
	if err == nil {
		if previous.Phase == PhaseActive {
			stored, readErr := m.store.read(id+".conf", wireguard.MaxConfigBytes)
			if readErr != nil {
				return previous, failure("storage_failed")
			}
			same := bytes.Equal(stored, configuration)
			clear(stored)
			if !same {
				return previous, failure("conflict")
			}
			return m.observe(ctx, previous)
		}
		if previous.Phase != PhaseDisconnected && previous.Phase != PhaseFailed && previous.Phase != PhaseCancelled {
			return previous, failure("conflict")
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		return Status{}, failure("storage_failed")
	}
	token, err := randomID()
	if err != nil {
		return Status{}, failure("storage_failed")
	}
	status := Status{SchemaVersion: 1, Target: Target{ProfileID: id, InterfaceName: "ebwg" + token[:10], OwnershipID: token}, Phase: PhasePrepared, Plan: profile.Plan(), Observation: Observation{Peers: []PeerObservation{}}}
	operation, cancel := context.WithCancel(ctx)
	m.jobsMu.Lock()
	m.jobs[id] = cancel
	m.jobsMu.Unlock()
	defer func() { cancel(); m.jobsMu.Lock(); delete(m.jobs, id); m.jobsMu.Unlock() }()
	err = profile.WithRuntime(func(runtime wireguard.RuntimeProfile) error {
		if err := m.prepare(operation, &status, runtime); err != nil {
			return failure(backendCode(err, "apply_failed"))
		}
		if operation.Err() != nil {
			return failure("cancelled")
		}
		if err := m.store.write(id+".conf", configuration); err != nil {
			return failure("storage_failed")
		}
		if err := m.store.save(status); err != nil {
			_ = m.store.remove(id + ".conf")
			return failure("storage_failed")
		}
		status.Phase = PhaseApplying
		if err := m.store.save(status); err != nil {
			return m.rollback(&status, "storage_failed")
		}
		if err := m.apply(operation, status, runtime); err != nil {
			return m.rollback(&status, backendCode(err, "apply_failed"))
		}
		if operation.Err() != nil {
			return m.rollback(&status, "cancelled")
		}
		observed, err := m.observeBackend(operation, status)
		if err != nil {
			return m.rollback(&status, backendCode(err, "observation_failed"))
		}
		if !matchesObservation(status, observed) {
			return m.rollback(&status, observationCode(status, observed))
		}
		status.Observation = observed
		status.KillSwitchActive = policyVerified(status, observed)
		status.Phase = PhaseActive
		status.ErrorCode = ""
		if err := m.store.save(status); err != nil {
			return m.rollback(&status, "storage_failed")
		}
		return nil
	})
	if err != nil && status.Phase == PhasePrepared {
		// A rejected Prepare has not created resources or a journal.
		status.Phase = PhaseFailed
		var detail *Error
		if errors.As(err, &detail) {
			status.ErrorCode = detail.Code
			if detail.Code == "cancelled" {
				status.Phase = PhaseCancelled
			}
		}
	}
	return status, err
}

func (m *Manager) rollback(status *Status, code string) error {
	status.Phase = PhaseRollingBack
	clearProtection(status)
	status.ErrorCode = code
	_ = m.store.save(*status) // The preceding durable applying record still permits recovery.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := m.release(ctx, *status, true); err != nil {
		m.recovered = false
		status.Phase = PhaseRecoveryRequired
		status.ErrorCode = "rollback_failed"
		_ = m.store.save(*status)
		return failure("rollback_failed")
	}
	status.Observation = Observation{Peers: []PeerObservation{}}
	status.Phase = PhaseFailed
	if code == "cancelled" {
		status.Phase = PhaseCancelled
	}
	if err := m.store.remove(status.Target.ProfileID + ".conf"); err != nil {
		m.recovered = false
		status.Phase = PhaseRecoveryRequired
		status.ErrorCode = "storage_failed"
		_ = m.store.save(*status)
		return failure("storage_failed")
	}
	if err := m.store.save(*status); err != nil {
		m.recovered = false
		return failure("storage_failed")
	}
	return failure(code)
}

func (m *Manager) Disconnect(ctx context.Context, id string) (Status, error) {
	if !profileID.MatchString(id) {
		return Status{}, failure("invalid_id")
	}
	m.Cancel(id)
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return Status{}, failure("closed")
	}
	if ctx.Err() != nil {
		return Status{}, failure("cancelled")
	}
	status, err := m.store.load(id)
	if errors.Is(err, os.ErrNotExist) {
		return Status{}, failure("not_found")
	}
	if err != nil {
		return Status{}, failure("storage_failed")
	}
	if status.Phase == PhaseDisconnected {
		return status, nil
	}
	status.Phase = PhaseRemoving
	clearProtection(&status)
	status.ErrorCode = ""
	if err := m.store.save(status); err != nil {
		return status, failure("storage_failed")
	}
	if err := m.release(ctx, status, false); err != nil {
		m.recovered = false
		status.Phase = PhaseRecoveryRequired
		status.ErrorCode = backendCode(err, "remove_failed")
		_ = m.store.save(status)
		return status, failure(status.ErrorCode)
	}
	status.Phase = PhaseDisconnected
	status.Observation = Observation{Peers: []PeerObservation{}}
	if err := m.store.remove(id + ".conf"); err != nil {
		m.recovered = false
		status.Phase = PhaseRecoveryRequired
		status.ErrorCode = "storage_failed"
		_ = m.store.save(status)
		return status, failure("storage_failed")
	}
	if err := m.store.save(status); err != nil {
		m.recovered = false
		return status, failure("storage_failed")
	}
	m.refreshRecoveryGate()
	return status, nil
}

func (m *Manager) Status(ctx context.Context, id string) (Status, error) {
	if !profileID.MatchString(id) {
		return Status{}, failure("invalid_id")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return Status{}, failure("closed")
	}
	status, err := m.store.load(id)
	if errors.Is(err, os.ErrNotExist) {
		return Status{}, failure("not_found")
	}
	if err != nil {
		return Status{}, failure("storage_failed")
	}
	return m.observe(ctx, status)
}

func (m *Manager) observe(ctx context.Context, status Status) (Status, error) {
	clearProtection(&status)
	observation, err := m.observeBackend(ctx, status)
	if err != nil {
		return status, failure(backendCode(err, "observation_failed"))
	}
	if observation.Exists && !observation.Owned {
		return status, failure("ownership_mismatch")
	}
	status.Observation = observation
	if status.Phase == PhaseActive && !matchesObservation(status, observation) {
		status.Phase = PhaseRecoveryRequired
		status.ErrorCode = observationCode(status, observation)
		return status, failure(status.ErrorCode)
	}
	status.KillSwitchActive = status.Phase == PhaseActive && policyVerified(status, observation)
	return status, nil
}

// Recover keeps only durably active, currently owned/up interfaces. Interrupted
// operations are rolled back, never silently retried as new network mutations.
func (m *Manager) Recover(ctx context.Context) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return failure("closed")
	}
	m.recovered = false
	ids, err := m.store.ids()
	if err != nil {
		return failure("storage_failed")
	}
	for _, id := range ids {
		if ctx.Err() != nil {
			return failure("cancelled")
		}
		status, err := m.store.load(id)
		if err != nil {
			return failure("storage_failed")
		}
		if status.Phase == PhaseDisconnected || status.Phase == PhaseFailed || status.Phase == PhaseCancelled {
			// A reconnect may have durably replaced the private config before a
			// crash prevented its prepared journal from replacing this terminal
			// record. Terminal states never require retained private material.
			if err := m.store.remove(id + ".conf"); err != nil {
				return failure("storage_failed")
			}
			continue
		}
		if status.Phase == PhaseActive {
			observed, err := m.observeBackend(ctx, status)
			if err != nil {
				return failure("recovery_failed")
			}
			if matchesObservation(status, observed) {
				continue
			}
		}
		status.Phase = PhaseRollingBack
		clearProtection(&status)
		status.ErrorCode = ""
		if err := m.store.save(status); err != nil {
			return failure("storage_failed")
		}
		if err := m.release(ctx, status, true); err != nil {
			status.Phase = PhaseRecoveryRequired
			status.ErrorCode = backendCode(err, "rollback_failed")
			_ = m.store.save(status)
			return failure("recovery_failed")
		}
		status.Phase = PhaseDisconnected
		status.Observation = Observation{Peers: []PeerObservation{}}
		if err := m.store.remove(id + ".conf"); err != nil {
			return failure("storage_failed")
		}
		if err := m.store.save(status); err != nil {
			return failure("storage_failed")
		}
	}
	if err := m.store.cleanOrphans(); err != nil {
		return failure("storage_failed")
	}
	m.recovered = true
	m.startupRecovered = true
	return nil
}

// A successful disconnect retry can clear a previous cleanup failure without
// requiring a daemon restart, but cannot bypass mandatory startup recovery.
func (m *Manager) refreshRecoveryGate() {
	if !m.startupRecovered {
		return
	}
	ids, err := m.store.ids()
	if err != nil {
		return
	}
	for _, id := range ids {
		status, err := m.store.load(id)
		if err != nil {
			return
		}
		switch status.Phase {
		case PhaseActive, PhaseDisconnected, PhaseFailed, PhaseCancelled:
		default:
			return
		}
	}
	m.recovered = true
}

func matchesObservation(status Status, observation Observation) bool {
	if status.Policy != nil && !policyVerified(status, observation) {
		return false
	}
	if !observation.Exists || !observation.Owned || !observation.Up || len(observation.Peers) != len(status.Plan.Peers) {
		return false
	}
	keys := make(map[string]bool, len(status.Plan.Peers))
	for _, peer := range status.Plan.Peers {
		keys[peer.PublicKey] = true
	}
	for _, peer := range observation.Peers {
		if !keys[peer.PublicKey] {
			return false
		}
		delete(keys, peer.PublicKey)
	}
	return len(keys) == 0
}
