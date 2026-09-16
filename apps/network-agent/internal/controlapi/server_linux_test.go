package controlapi

import (
	"os"
	"path/filepath"
	"testing"
)

// These failures all occur before ListenUnix. No socket or kernel privileges
// are requested in the ordinary test suite.
func TestSocketPathRejectsUnsafeDirectoriesAndNeverUnlinksExistingFiles(t *testing.T) {
	peer := Identity{UID: uint32(os.Geteuid()), GID: uint32(os.Getegid())}
	for _, kind := range []string{"world_writable", "symlink", "existing_file", "existing_symlink"} {
		t.Run(kind, func(t *testing.T) {
			base := t.TempDir()
			directory := filepath.Join(base, "private")
			if err := os.Mkdir(directory, 0750); err != nil {
				t.Fatal(err)
			}
			path := filepath.Join(directory, "control.sock")
			switch kind {
			case "world_writable":
				if err := os.Chmod(directory, 0777); err != nil {
					t.Fatal(err)
				}
			case "symlink":
				link := filepath.Join(base, "link")
				if err := os.Symlink(directory, link); err != nil {
					t.Fatal(err)
				}
				path = filepath.Join(link, "control.sock")
			case "existing_file":
				if err := os.WriteFile(path, []byte("canary"), 0600); err != nil {
					t.Fatal(err)
				}
			case "existing_symlink":
				if err := os.Symlink(filepath.Join(base, "missing"), path); err != nil {
					t.Fatal(err)
				}
			}
			if listener, err := Listen(path, peer); err == nil {
				listener.Close()
				t.Fatal("unsafe socket path accepted")
			}
			if kind == "existing_file" {
				data, err := os.ReadFile(path)
				if err != nil || string(data) != "canary" {
					t.Fatal("existing resource changed")
				}
			}
			if kind == "existing_symlink" {
				if _, err := os.Lstat(path); err != nil {
					t.Fatal("stale pathname removed")
				}
			}
		})
	}
}
