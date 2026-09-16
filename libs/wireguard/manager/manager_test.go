//go:build linux

package manager

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard"
)

const testConfig = "[Interface]\nPrivateKey=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\nAddress=10.20.0.2/32\n[Peer]\nPublicKey=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\nPresharedKey=AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=\nAllowedIPs=10.99.0.0/16\nEndpoint=198.51.100.1:51820\n"

type fakeBackend struct {
	peers                                        map[string][]PeerObservation
	resources                                    map[string]Target
	prepareErr, applyErr, rollbackErr, removeErr error
	applyCalls, rollbackCalls, removeCalls       int
	applyHook                                    func(Target)
	started                                      chan struct{}
	retained                                     wireguard.RuntimeProfile
	rollbackCancelled                            bool
}

func newBackend() *fakeBackend {
	return &fakeBackend{resources: map[string]Target{}, peers: map[string][]PeerObservation{}}
}
func (b *fakeBackend) Prepare(_ context.Context, _ Target, profile wireguard.RuntimeProfile) error {
	b.retained = profile
	return b.prepareErr
}
func (b *fakeBackend) Apply(ctx context.Context, target Target, profile wireguard.RuntimeProfile) error {
	b.applyCalls++
	if profile.PrivateKeyBytes() == [32]byte{} {
		return errors.New("missing runtime key")
	}
	b.resources[target.InterfaceName] = target // Simulate partial application before a failure.
	for _, peer := range profile.Plan().Peers {
		b.peers[target.InterfaceName] = append(b.peers[target.InterfaceName], PeerObservation{PublicKey: peer.PublicKey})
	}
	if b.applyHook != nil {
		b.applyHook(target)
	}
	if b.started != nil {
		close(b.started)
		<-ctx.Done()
		return ctx.Err()
	}
	return b.applyErr
}
func (b *fakeBackend) Observe(_ context.Context, target Target) (Observation, error) {
	existing, ok := b.resources[target.InterfaceName]
	return Observation{Exists: ok, Owned: ok && existing == target, Up: ok, Peers: b.peers[target.InterfaceName]}, nil
}
func (b *fakeBackend) Rollback(ctx context.Context, target Target) error {
	b.rollbackCalls++
	b.rollbackCancelled = ctx.Err() != nil
	if b.rollbackErr != nil {
		return b.rollbackErr
	}
	return b.remove(target)
}
func (b *fakeBackend) Remove(_ context.Context, target Target) error {
	b.removeCalls++
	if b.removeErr != nil {
		return b.removeErr
	}
	return b.remove(target)
}
func (b *fakeBackend) remove(target Target) error {
	if existing, ok := b.resources[target.InterfaceName]; ok && existing != target {
		return ErrOwnershipMismatch
	}
	delete(b.resources, target.InterfaceName)
	delete(b.peers, target.InterfaceName)
	return nil
}

func openReady(t *testing.T, backend *fakeBackend) (*Manager, string) {
	t.Helper()
	directory := filepath.Join(t.TempDir(), "state")
	m, err := New(directory, backend)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = m.Close() })
	if err := m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	return m, directory
}
func expectCode(t *testing.T, err error, code string) {
	t.Helper()
	var e *Error
	if !errors.As(err, &e) || e.Code != code {
		t.Fatalf("expected %s, got %v", code, err)
	}
}

func TestDurableConnectIsIdempotentAndSecretsStayPrivate(t *testing.T) {
	backend := newBackend()
	m, directory := openReady(t, backend)
	backend.applyHook = func(target Target) {
		record, err := m.store.load("home")
		if err != nil || record.Phase != PhaseApplying || record.Target != target {
			t.Fatal("mutation preceded durable applying journal")
		}
		for _, name := range []string{"home.conf", "home.json", ".lock"} {
			info, err := os.Stat(filepath.Join(directory, name))
			if err != nil || info.Mode().Perm() != 0600 {
				t.Fatal("state file must be 0600")
			}
		}
	}
	status, err := m.Connect(t.Context(), "home", []byte(testConfig))
	if err != nil {
		t.Fatal(err)
	}
	if status.Phase != PhaseActive || !status.Observation.Owned || !status.Observation.Up {
		t.Fatal("owned interface was not observed")
	}
	if status.Plan.Applied || status.Plan.KillSwitchActive {
		t.Fatal("validation plan was upgraded to an unverified protection claim")
	}
	if backend.retained.PrivateKeyBytes() != [32]byte{} {
		t.Fatal("runtime snapshot survived callback")
	}
	data, err := os.ReadFile(filepath.Join(directory, "home.conf"))
	if err != nil || string(data) != testConfig {
		t.Fatal("private config not persisted exactly")
	}
	clear(data)
	journal, err := os.ReadFile(filepath.Join(directory, "home.json"))
	if err != nil {
		t.Fatal(err)
	}
	public, _ := json.Marshal(status)
	for _, secret := range []string{"AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=", "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="} {
		if strings.Contains(string(journal)+string(public)+fmt.Sprint(status), secret) {
			t.Fatal("public state leaked secret")
		}
	}
	again, err := m.Connect(t.Context(), "home", []byte(testConfig))
	if err != nil || again.Target != status.Target || backend.applyCalls != 1 {
		t.Fatal("retry mutated an active interface")
	}
	_, err = m.Connect(t.Context(), "home", []byte(testConfig+"# changed\n"))
	expectCode(t, err, "conflict")
	if _, err := m.Disconnect(t.Context(), "home"); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Disconnect(t.Context(), "home"); err != nil {
		t.Fatal(err)
	}
	if backend.removeCalls != 1 || len(backend.resources) != 0 {
		t.Fatal("disconnect not idempotent")
	}
	if _, err := os.Stat(filepath.Join(directory, "home.conf")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("disconnected secret retained")
	}
}

func TestRejectedPrepareMakesNoChanges(t *testing.T) {
	backend := newBackend()
	backend.prepareErr = fmt.Errorf("secret detail: %w", ErrUnsupportedProfile)
	m, directory := openReady(t, backend)
	status, err := m.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "unsupported_profile")
	if status.Phase != PhaseFailed || backend.applyCalls != 0 || strings.Contains(err.Error(), "secret detail") {
		t.Fatal("prepare failure unsafe")
	}
	for _, name := range []string{"home.conf", "home.json"} {
		if _, err := os.Stat(filepath.Join(directory, name)); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("rejected config persisted")
		}
	}
}

func TestPartialApplyRollsBackBeforeReturning(t *testing.T) {
	backend := newBackend()
	backend.applyErr = errors.New("sensitive kernel command")
	m, directory := openReady(t, backend)
	status, err := m.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "apply_failed")
	if status.Phase != PhaseFailed || backend.rollbackCalls != 1 || len(backend.resources) != 0 {
		t.Fatal("partial apply not cleaned")
	}
	if strings.Contains(err.Error(), "sensitive") {
		t.Fatal("backend error leaked")
	}
	if _, err := os.Stat(filepath.Join(directory, "home.conf")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("failed secret retained")
	}
	backend.applyErr = nil
	if _, err := m.Connect(t.Context(), "home", []byte(testConfig)); err != nil {
		t.Fatal("safe retry failed", err)
	}
}

func TestRollbackFailureRequiresRecoveryAndCanBeRetried(t *testing.T) {
	backend := newBackend()
	backend.applyErr = errors.New("partial")
	backend.rollbackErr = errors.New("cleanup")
	m, _ := openReady(t, backend)
	status, err := m.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "rollback_failed")
	if status.Phase != PhaseRecoveryRequired || len(backend.resources) != 1 {
		t.Fatal("incomplete rollback not recorded")
	}
	_, err = m.Connect(t.Context(), "other", []byte(testConfig))
	expectCode(t, err, "recovery_failed")
	backend.rollbackErr = nil
	backend.applyErr = nil
	if err := m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	if len(backend.resources) != 0 {
		t.Fatal("recovery retained partial resource")
	}
	if _, err := m.Connect(t.Context(), "home", []byte(testConfig)); err != nil {
		t.Fatal(err)
	}
}

func TestCancellationUsesAnIndependentRollbackContext(t *testing.T) {
	backend := newBackend()
	backend.started = make(chan struct{})
	m, _ := openReady(t, backend)
	type result struct {
		status Status
		err    error
	}
	done := make(chan result, 1)
	go func() { status, err := m.Connect(t.Context(), "home", []byte(testConfig)); done <- result{status, err} }()
	<-backend.started
	if !m.Cancel("home") {
		t.Fatal("running operation was not cancellable")
	}
	finished := <-done
	expectCode(t, finished.err, "cancelled")
	if finished.status.Phase != PhaseCancelled || backend.rollbackCancelled || len(backend.resources) != 0 {
		t.Fatal("cancelled operation did not clean up with live context")
	}
}

func TestCrashDuringApplyRecoversFromDurableJournal(t *testing.T) {
	backend := newBackend()
	m, directory := openReady(t, backend)
	backend.applyHook = func(Target) { panic("simulated process interruption") }
	func() {
		defer func() {
			if recover() == nil {
				t.Fatal("expected simulated interruption")
			}
		}()
		_, _ = m.Connect(t.Context(), "home", []byte(testConfig))
	}()
	if len(backend.resources) != 1 {
		t.Fatal("crash must leave the partial resource")
	}
	if err := m.Close(); err != nil {
		t.Fatal(err)
	}
	restarted, err := New(directory, backend)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	if err := restarted.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	status, err := restarted.Status(t.Context(), "home")
	if err != nil || status.Phase != PhaseDisconnected || len(backend.resources) != 0 {
		t.Fatal("crash recovery failed", err)
	}
	if _, err := os.Stat(filepath.Join(directory, "home.conf")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("crash secret retained after rollback")
	}
}

func TestDurablyActiveProfileSurvivesRestartWithoutReapply(t *testing.T) {
	backend := newBackend()
	m, directory := openReady(t, backend)
	active, err := m.Connect(t.Context(), "home", []byte(testConfig))
	if err != nil {
		t.Fatal(err)
	}
	_ = m.Close()
	restarted, err := New(directory, backend)
	if err != nil {
		t.Fatal(err)
	}
	defer restarted.Close()
	_, err = restarted.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "recovery_failed")
	if err := restarted.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	status, err := restarted.Connect(t.Context(), "home", []byte(testConfig))
	if err != nil || status.Target != active.Target || backend.applyCalls != 1 || backend.rollbackCalls != 0 {
		t.Fatal("restart replaced active owned interface")
	}
}

func TestRecoveryHandlesEveryIncompleteJournalPhase(t *testing.T) {
	for _, phase := range []string{PhasePrepared, PhaseApplying, PhaseRemoving, PhaseRollingBack, PhaseRecoveryRequired} {
		t.Run(phase, func(t *testing.T) {
			backend := newBackend()
			m, directory := openReady(t, backend)
			status, err := m.Connect(t.Context(), "home", []byte(testConfig))
			if err != nil {
				t.Fatal(err)
			}
			status.Phase = phase
			if err := m.store.save(status); err != nil {
				t.Fatal(err)
			}
			if phase == PhaseRemoving {
				if err := backend.remove(status.Target); err != nil {
					t.Fatal(err)
				}
			} // Crash after deletion, before completion journal.
			_ = m.Close()
			restarted, err := New(directory, backend)
			if err != nil {
				t.Fatal(err)
			}
			defer restarted.Close()
			if err := restarted.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			if err := restarted.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			if backend.rollbackCalls != 1 || len(backend.resources) != 0 {
				t.Fatal("interrupted phase was not recovered idempotently")
			}
			status, err = restarted.Disconnect(t.Context(), "home")
			if err != nil || status.Phase != PhaseDisconnected || backend.removeCalls != 0 {
				t.Fatal("repeated disconnect after recovery mutated state")
			}
		})
	}
}

func TestForeignResourcesAreNotAdoptedOrRemoved(t *testing.T) {
	backend := newBackend()
	m, _ := openReady(t, backend)
	active, err := m.Connect(t.Context(), "home", []byte(testConfig))
	if err != nil {
		t.Fatal(err)
	}
	foreign := active.Target
	foreign.OwnershipID = strings.Repeat("f", 32)
	backend.resources[active.Target.InterfaceName] = foreign
	_, err = m.Status(t.Context(), "home")
	expectCode(t, err, "ownership_mismatch")
	status, err := m.Disconnect(t.Context(), "home")
	expectCode(t, err, "ownership_mismatch")
	if status.Phase != PhaseRecoveryRequired || backend.resources[active.Target.InterfaceName] != foreign {
		t.Fatal("foreign interface touched")
	}
	expectCode(t, m.Recover(t.Context()), "recovery_failed")
	if backend.resources[active.Target.InterfaceName] != foreign {
		t.Fatal("recovery removed foreign interface")
	}
}

func TestFailedRemoveCanBeRetried(t *testing.T) {
	backend := newBackend()
	m, _ := openReady(t, backend)
	if _, err := m.Connect(t.Context(), "home", []byte(testConfig)); err != nil {
		t.Fatal(err)
	}
	backend.removeErr = errors.New("temporary kernel failure")
	status, err := m.Disconnect(t.Context(), "home")
	expectCode(t, err, "remove_failed")
	if status.Phase != PhaseRecoveryRequired {
		t.Fatal("failed remove not durable")
	}
	backend.removeErr = nil
	status, err = m.Disconnect(t.Context(), "home")
	if err != nil || status.Phase != PhaseDisconnected {
		t.Fatal("retry failed", err)
	}
	if _, err := m.Connect(t.Context(), "home", []byte(testConfig)); err != nil {
		t.Fatal("successful disconnect retry must permit reconnect", err)
	}
}

func TestChangedOrMissingPeersAreNotReportedAsActive(t *testing.T) {
	backend := newBackend()
	m, _ := openReady(t, backend)
	status, err := m.Connect(t.Context(), "home", []byte(testConfig))
	if err != nil {
		t.Fatal(err)
	}
	backend.peers[status.Target.InterfaceName] = nil
	status, err = m.Status(t.Context(), "home")
	expectCode(t, err, "observation_failed")
	if status.Phase != PhaseRecoveryRequired {
		t.Fatal("missing peers still reported active")
	}
	if err := m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	if len(backend.resources) != 0 {
		t.Fatal("recovery retained changed configuration")
	}
}

func TestPostApplyJournalFailureTriggersRollback(t *testing.T) {
	backend := newBackend()
	m, directory := openReady(t, backend)
	backend.applyHook = func(Target) {
		if err := os.Chmod(filepath.Join(directory, "home.json"), 0400); err != nil {
			t.Fatal(err)
		}
	}
	_, err := m.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "storage_failed")
	if len(backend.resources) != 0 || backend.rollbackCalls != 1 {
		t.Fatal("storage failure left applied interface")
	}
	if err := os.Chmod(filepath.Join(directory, "home.json"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
}

func TestPrivateStorageRejectsUnsafeModesSymlinksAndConcurrentOwners(t *testing.T) {
	backend := newBackend()
	m, directory := openReady(t, backend)
	if other, err := New(directory, backend); err == nil {
		_ = other.Close()
		t.Fatal("second process owner accepted")
	}
	if err := os.Symlink("outside", filepath.Join(directory, "home.conf")); err != nil {
		t.Fatal(err)
	}
	_, err := m.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "storage_failed")
	if backend.applyCalls != 0 {
		t.Fatal("unsafe store caused kernel mutation")
	}
	unsafe := filepath.Join(t.TempDir(), "unsafe")
	if err := os.Mkdir(unsafe, 0755); err != nil {
		t.Fatal(err)
	}
	if other, err := New(unsafe, backend); err == nil {
		_ = other.Close()
		t.Fatal("public storage accepted")
	}
	link := filepath.Join(t.TempDir(), "link")
	if err := os.Symlink(directory, link); err != nil {
		t.Fatal(err)
	}
	if other, err := New(link, backend); err == nil {
		_ = other.Close()
		t.Fatal("symlink storage accepted")
	}
}

func TestRecoveryRejectsCorruptJournalAndCleansPrivateOrphans(t *testing.T) {
	backend := newBackend()
	m, directory := openReady(t, backend)
	if err := m.store.write("orphan.conf", []byte("private orphan")); err != nil {
		t.Fatal(err)
	}
	if err := m.store.write(".tmp-0123456789abcdef", []byte("private temporary")); err != nil {
		t.Fatal(err)
	}
	if err := m.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"orphan.conf", ".tmp-0123456789abcdef"} {
		if _, err := os.Stat(filepath.Join(directory, name)); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("orphan retained")
		}
	}
	if err := m.store.write("home.json", []byte(`{"version":99,"status":{}}`)); err != nil {
		t.Fatal(err)
	}
	expectCode(t, m.Recover(t.Context()), "storage_failed")
	if backend.applyCalls+backend.rollbackCalls+backend.removeCalls != 0 {
		t.Fatal("corrupt journal caused kernel mutation")
	}
}

func TestReconnectCrashBeforePreparedJournalRemovesSecretsForEveryTerminalState(t *testing.T) {
	for _, phase := range []string{PhaseDisconnected, PhaseFailed, PhaseCancelled} {
		t.Run(phase, func(t *testing.T) {
			backend := newBackend()
			m, directory := openReady(t, backend)
			if _, err := m.Connect(t.Context(), "home", []byte(testConfig)); err != nil {
				t.Fatal(err)
			}
			terminal, err := m.Disconnect(t.Context(), "home")
			if err != nil {
				t.Fatal(err)
			}
			terminal.Phase = phase
			if err := m.store.save(terminal); err != nil {
				t.Fatal(err)
			}
			// Reconnect persisted its replacement config, but the process dies
			// before it can replace the previous terminal journal with prepared.
			if err := m.store.write("home.conf", []byte(testConfig+"# replacement configuration\n")); err != nil {
				t.Fatal(err)
			}
			if err := m.Close(); err != nil {
				t.Fatal(err)
			}
			backend.applyCalls = 0
			backend.rollbackCalls = 0
			backend.removeCalls = 0
			restarted, err := New(directory, backend)
			if err != nil {
				t.Fatal(err)
			}
			defer restarted.Close()
			if err := restarted.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			if err := restarted.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(filepath.Join(directory, "home.conf")); !errors.Is(err, os.ErrNotExist) {
				t.Fatal("terminal journal retained replacement private configuration")
			}
			retained, err := restarted.store.load("home")
			if err != nil || retained.Phase != phase {
				t.Fatal("terminal journal state was unnecessarily changed", err)
			}
			if backend.applyCalls+backend.rollbackCalls+backend.removeCalls != 0 || len(backend.resources) != 0 {
				t.Fatal("private file cleanup must not mutate network resources")
			}
		})
	}
}

func TestInvalidIdsAndClosedManagerFailWithoutMutation(t *testing.T) {
	backend := newBackend()
	m, _ := openReady(t, backend)
	for _, id := range []string{"../secret", "", strings.Repeat("a", 33)} {
		_, err := m.Connect(t.Context(), id, []byte(testConfig))
		expectCode(t, err, "invalid_id")
	}
	_, err := m.Connect(t.Context(), "home", []byte("malformed secret"))
	expectCode(t, err, "invalid_profile")
	_ = m.Close()
	_, err = m.Connect(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "closed")
	if backend.applyCalls != 0 {
		t.Fatal("invalid operation mutated network")
	}
}
