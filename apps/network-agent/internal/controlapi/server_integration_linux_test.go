//go:build controlintegration

package controlapi

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

// Explicit CI-only Unix transport test. It never constructs a native backend
// or requests CAP_NET_ADMIN; ordinary tests do not attempt local sockets.
func TestPrivateUnixSocketSOPEERCREDIntegration(t *testing.T) {
	if os.Getenv("EBLOCKER_CONTROL_SOCKET_TEST") != "1" {
		t.Skip("explicit isolated Unix-socket test opt-in required")
	}
	directory := t.TempDir()
	if err := os.Chmod(directory, 0750); err != nil {
		t.Fatal(err)
	}
	peer := Identity{UID: uint32(os.Geteuid()), GID: uint32(os.Getegid())}
	l, err := Listen(filepath.Join(directory, "control.sock"), peer)
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	h, _, _ := fixtureHandler(t)
	h.identity = peer
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	done := make(chan error, 1)
	go func() { done <- Serve(ctx, l, h) }()
	transport := &http.Transport{DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
		return (&net.Dialer{}).DialContext(ctx, "unix", l.Addr().String())
	}}
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: 3 * time.Second}
	response, err := client.Get("http://local/v1/profiles")
	if err != nil {
		t.Fatal(err)
	}
	data, err := io.ReadAll(response.Body)
	response.Body.Close()
	if err != nil || response.StatusCode != 200 || len(data) == 0 {
		t.Fatal("authenticated socket exchange failed", err)
	}
	cancel()
	if err := <-done; err != nil {
		t.Fatal(err)
	}
}
