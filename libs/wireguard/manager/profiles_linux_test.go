package manager

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestImportedProfilesSurviveRecoveryAndRequireExplicitDeletion(t *testing.T) {
	b := newBackend()
	m, directory := openReady(t, b)
	profile, err := m.ImportProfile(t.Context(), "home", []byte(testConfig))
	if err != nil || profile.Phase != "imported" || b.applyCalls != 0 {
		t.Fatal("import mutated kernel", err)
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
	profiles, err := reopened.Profiles(t.Context())
	if err != nil || len(profiles) != 1 || profiles[0].ProfileID != "home" {
		t.Fatal("imported profile lost in recovery", err)
	}
	status, err := reopened.ConnectProfile(t.Context(), "home")
	if err != nil || status.Phase != PhaseActive {
		t.Fatal(err)
	}
	_, err = reopened.ImportProfile(t.Context(), "home", []byte(testConfig))
	expectCode(t, err, "conflict")
	expectCode(t, reopened.DeleteProfile(t.Context(), "home"), "conflict")
	if _, err := reopened.Disconnect(t.Context(), "home"); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(directory, "home.profile")); err != nil {
		t.Fatal("disconnect erased intended import")
	}
	if err := reopened.DeleteProfile(t.Context(), "home"); err != nil {
		t.Fatal(err)
	}
	for _, suffix := range []string{".profile", ".conf", ".json"} {
		if _, err := os.Stat(filepath.Join(directory, "home"+suffix)); !errors.Is(err, os.ErrNotExist) {
			t.Fatal("deleted profile material remains")
		}
	}
}

func TestImportedProfileStorageRejectsHardlinksAndOversizedCatalogs(t *testing.T) {
	t.Run("hardlink", func(t *testing.T) {
		b := newBackend()
		m, directory := openReady(t, b)
		if _, err := m.ImportProfile(t.Context(), "home", []byte(testConfig)); err != nil {
			t.Fatal(err)
		}
		if err := os.Link(filepath.Join(directory, "home.profile"), filepath.Join(directory, "linked")); err != nil {
			t.Fatal(err)
		}
		_, err := m.Profile(t.Context(), "home")
		expectCode(t, err, "storage_failed")
		expectCode(t, m.DeleteProfile(t.Context(), "home"), "storage_failed")
	})
	t.Run("capacity", func(t *testing.T) {
		b := newBackend()
		m, _ := openReady(t, b)
		for i := 0; i < MaxStoredProfiles; i++ {
			if _, err := m.ImportProfile(t.Context(), fmt.Sprintf("p%d", i), []byte(testConfig)); err != nil {
				t.Fatal(err)
			}
		}
		_, err := m.ImportProfile(t.Context(), "overflow", []byte(testConfig))
		expectCode(t, err, "profile_limit")
		if _, err := m.ImportProfile(t.Context(), "p0", []byte(testConfig)); err != nil {
			t.Fatal("replace at capacity failed", err)
		}
	})
}
