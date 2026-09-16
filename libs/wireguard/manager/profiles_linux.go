package manager

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"strings"

	"github.com/eblocker/eblocker/libs/wireguard"
)

const MaxStoredProfiles = 64
const MaxProfileCatalogBytes = 448 << 10

// StoredProfile never contains private configuration. Imported sources are
// separate from ephemeral runtime .conf files and persist until DeleteProfile.
type StoredProfile struct {
	ProfileID string         `json:"profileId"`
	Phase     string         `json:"phase"`
	Plan      wireguard.Plan `json:"plan"`
}

func (m *Manager) profileLocked(id string) (StoredProfile, error) {
	configuration, err := m.store.read(id+".profile", wireguard.MaxConfigBytes)
	if errors.Is(err, os.ErrNotExist) {
		return StoredProfile{}, failure("not_found")
	}
	if err != nil {
		return StoredProfile{}, failure("storage_failed")
	}
	defer clear(configuration)
	profile, err := wireguard.Parse(configuration)
	if err != nil {
		return StoredProfile{}, failure("storage_failed")
	}
	defer profile.Destroy()
	result := StoredProfile{ProfileID: id, Phase: "imported", Plan: profile.Plan()}
	status, err := m.store.load(id)
	if err == nil {
		result.Phase = status.Phase
	} else if !errors.Is(err, os.ErrNotExist) {
		return StoredProfile{}, failure("storage_failed")
	}
	return result, nil
}

func (m *Manager) Profile(ctx context.Context, id string) (StoredProfile, error) {
	if !profileID.MatchString(id) {
		return StoredProfile{}, failure("invalid_id")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return StoredProfile{}, failure("closed")
	}
	if ctx.Err() != nil {
		return StoredProfile{}, failure("cancelled")
	}
	return m.profileLocked(id)
}

func (m *Manager) profileIDs() ([]string, error) {
	entries, err := os.ReadDir(m.store.directory)
	if err != nil {
		return nil, err
	}
	ids := []string{}
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".profile") {
			id := strings.TrimSuffix(entry.Name(), ".profile")
			if !profileID.MatchString(id) {
				return nil, failure("storage_failed")
			}
			ids = append(ids, id)
		}
	}
	if len(ids) > MaxStoredProfiles {
		return nil, failure("storage_failed")
	}
	return ids, nil
}

func (m *Manager) Profiles(ctx context.Context) ([]StoredProfile, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return nil, failure("closed")
	}
	ids, err := m.profileIDs()
	if err != nil {
		return nil, failure("storage_failed")
	}
	profiles := make([]StoredProfile, 0, len(ids))
	for _, id := range ids {
		if ctx.Err() != nil {
			return nil, failure("cancelled")
		}
		p, err := m.profileLocked(id)
		if err != nil {
			return nil, err
		}
		profiles = append(profiles, p)
	}
	return profiles, nil
}

func (m *Manager) inactive(id string) error {
	status, err := m.store.load(id)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return failure("storage_failed")
	}
	switch status.Phase {
	case PhaseDisconnected, PhaseFailed, PhaseCancelled:
		return nil
	default:
		return failure("conflict")
	}
}

func (m *Manager) ImportProfile(ctx context.Context, id string, configuration []byte) (StoredProfile, error) {
	if !profileID.MatchString(id) {
		return StoredProfile{}, failure("invalid_id")
	}
	if len(configuration) > wireguard.MaxConfigBytes {
		return StoredProfile{}, failure("invalid_profile")
	}
	configuration = append([]byte(nil), configuration...)
	defer clear(configuration)
	profile, err := wireguard.Parse(configuration)
	if err != nil {
		return StoredProfile{}, failure("invalid_profile")
	}
	defer profile.Destroy()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return StoredProfile{}, failure("closed")
	}
	if ctx.Err() != nil {
		return StoredProfile{}, failure("cancelled")
	}
	if err := m.inactive(id); err != nil {
		return StoredProfile{}, err
	}
	ids, err := m.profileIDs()
	if err != nil {
		return StoredProfile{}, failure("storage_failed")
	}
	found := false
	for _, existing := range ids {
		if existing == id {
			found = true
		}
	}
	if !found && len(ids) >= MaxStoredProfiles {
		return StoredProfile{}, failure("profile_limit")
	}
	candidate := StoredProfile{ProfileID: id, Phase: "imported", Plan: profile.Plan()}
	encoded, _ := json.Marshal(candidate)
	total := len(encoded)
	for _, other := range ids {
		if other == id {
			continue
		}
		existing, err := m.profileLocked(other)
		if err != nil {
			return StoredProfile{}, err
		}
		encoded, _ := json.Marshal(existing)
		total += len(encoded) + 1
	}
	if total > MaxProfileCatalogBytes {
		return StoredProfile{}, failure("profile_limit")
	}
	// Old terminal history owns no resources. Remove it before replacing the
	// source, so a crash cannot make fresh data appear to be an old active plan.
	if err := m.store.remove(id + ".conf"); err != nil {
		return StoredProfile{}, failure("storage_failed")
	}
	if err := m.store.remove(id + ".json"); err != nil {
		return StoredProfile{}, failure("storage_failed")
	}
	if err := m.store.write(id+".profile", configuration); err != nil {
		return StoredProfile{}, failure("storage_failed")
	}
	return candidate, nil
}

func (m *Manager) ConnectProfile(ctx context.Context, id string) (Status, error) {
	if !profileID.MatchString(id) {
		return Status{}, failure("invalid_id")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return Status{}, failure("closed")
	}
	configuration, err := m.store.read(id+".profile", wireguard.MaxConfigBytes)
	if errors.Is(err, os.ErrNotExist) {
		return Status{}, failure("not_found")
	}
	if err != nil {
		return Status{}, failure("storage_failed")
	}
	defer clear(configuration)
	profile, err := wireguard.Parse(configuration)
	if err != nil {
		return Status{}, failure("storage_failed")
	}
	defer profile.Destroy()
	return m.connectLocked(ctx, id, configuration, profile)
}

func (m *Manager) DeleteProfile(ctx context.Context, id string) error {
	if !profileID.MatchString(id) {
		return failure("invalid_id")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return failure("closed")
	}
	if ctx.Err() != nil {
		return failure("cancelled")
	}
	if err := m.inactive(id); err != nil {
		return err
	}
	for _, suffix := range []string{".conf", ".json", ".profile"} {
		if err := m.store.remove(id + suffix); err != nil {
			return failure("storage_failed")
		}
	}
	return nil
}
