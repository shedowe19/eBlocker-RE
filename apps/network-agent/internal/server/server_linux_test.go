package server

import (
	"context"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestSocketPermissionsHTTPAndShutdown(t *testing.T) {
	requireIntegration(t)
	path := filepath.Join(t.TempDir(), "agent.sock")
	listener, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	info, err := os.Stat(path)
	if err != nil || info.Mode().Perm() != 0660 {
		t.Fatalf("unsafe socket permissions: %v %v", info, err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	finished := make(chan error, 1)
	go func() {
		finished <- Serve(ctx, listener, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { _, _ = io.WriteString(w, "ready") }))
	}()
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", path)
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: time.Second}
	response, err := client.Get("http://agent/v1/health")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = io.Copy(io.Discard, response.Body)
	_ = response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatalf("HTTP status %d", response.StatusCode)
	}
	cancel()
	select {
	case err := <-finished:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(6 * time.Second):
		t.Fatal("shutdown did not complete")
	}
	if _, err := os.Lstat(path); !os.IsNotExist(err) {
		t.Fatalf("owned socket was not removed: %v", err)
	}
}

func TestExistingFilesAndSocketsAreNeverReplaced(t *testing.T) {
	for _, kind := range []string{"file", "symlink", "stale_socket", "active_socket"} {
		t.Run(kind, func(t *testing.T) {
			path := filepath.Join(t.TempDir(), "agent.sock")
			switch kind {
			case "file":
				if err := os.WriteFile(path, []byte("keep me"), 0600); err != nil {
					t.Fatal(err)
				}
			case "symlink":
				if err := os.Symlink("missing-target", path); err != nil {
					t.Fatal(err)
				}
			default:
				requireIntegration(t)
				listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
				if err != nil {
					t.Fatal(err)
				}
				listener.SetUnlinkOnClose(false)
				defer listener.Close()
				if kind == "stale_socket" {
					_ = listener.Close()
				}
			}
			before, err := os.Lstat(path)
			if err != nil {
				t.Fatal(err)
			}
			if listener, err := Listen(path); err == nil {
				_ = listener.Close()
				t.Fatal("existing path was replaced")
			}
			after, err := os.Lstat(path)
			if err != nil || !os.SameFile(before, after) {
				t.Fatal("existing path was changed")
			}
		})
	}
}

func TestCleanupDoesNotRemoveReplacement(t *testing.T) {
	requireIntegration(t)
	path := filepath.Join(t.TempDir(), "agent.sock")
	listener, err := Listen(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte("replacement"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	if err := listener.Close(); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "replacement" {
		t.Fatal("cleanup removed a different inode")
	}
}

func TestUnsafeDirectoryAndRelativePathRejected(t *testing.T) {
	directory := t.TempDir()
	if err := os.Chmod(directory, 0770); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"relative.sock", filepath.Join(directory, "agent.sock"), filepath.Join(directory, "missing", "agent.sock")} {
		if listener, err := Listen(path); err == nil {
			_ = listener.Close()
			t.Fatalf("unsafe path accepted: %s", path)
		}
	}
	link := filepath.Join(t.TempDir(), "linked-directory")
	if err := os.Symlink(t.TempDir(), link); err != nil {
		t.Fatal(err)
	}
	if listener, err := Listen(filepath.Join(link, "agent.sock")); err == nil {
		_ = listener.Close()
		t.Fatal("symlinked parent accepted")
	}
	ancestor := t.TempDir()
	child := filepath.Join(ancestor, "child")
	if err := os.Mkdir(child, 0750); err != nil {
		t.Fatal(err)
	}
	if err := os.Chmod(ancestor, 0777); err != nil {
		t.Fatal(err)
	}
	if listener, err := Listen(filepath.Join(child, "agent.sock")); err == nil {
		_ = listener.Close()
		t.Fatal("writable ancestor accepted")
	}
	nestedTarget := t.TempDir()
	if err := os.Mkdir(filepath.Join(nestedTarget, "child"), 0750); err != nil {
		t.Fatal(err)
	}
	nestedLink := filepath.Join(t.TempDir(), "ancestor")
	if err := os.Symlink(nestedTarget, nestedLink); err != nil {
		t.Fatal(err)
	}
	if listener, err := Listen(filepath.Join(nestedLink, "child", "agent.sock")); err == nil {
		_ = listener.Close()
		t.Fatal("symlinked ancestor accepted")
	}
}

func requireIntegration(t *testing.T) {
	t.Helper()
	if os.Getenv("EBLOCKER_AGENT_INTEGRATION") != "1" {
		t.Skip("set EBLOCKER_AGENT_INTEGRATION=1 for real Unix-socket integration; sandbox forbids AF_UNIX")
	}
}
