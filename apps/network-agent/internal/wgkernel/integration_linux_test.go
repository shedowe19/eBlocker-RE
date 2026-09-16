package wgkernel

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/vishvananda/netlink"
	"github.com/vishvananda/netns"
	"golang.org/x/sys/unix"
)

// This test is never enabled by EBLOCKER_AGENT_INTEGRATION. It requires a
// separate explicit privileged gate and enters a fresh network namespace before
// any kernel mutation. Failure to enter that namespace is fatal, never fallback.
func TestNativeLifecycleInIsolatedNamespace(t *testing.T) {
	if os.Getenv("EBLOCKER_WIREGUARD_INTEGRATION") != "1" {
		t.Skip("set EBLOCKER_WIREGUARD_INTEGRATION=1 only in a disposable privileged Linux runner with WireGuard already loaded")
	}
	runtime.LockOSThread()
	original, err := netns.Get()
	if err != nil {
		runtime.UnlockOSThread()
		t.Fatal(err)
	}
	defer original.Close()
	if err := unix.Unshare(unix.CLONE_NEWNET); err != nil {
		runtime.UnlockOSThread()
		t.Fatalf("refusing mutation without an isolated network namespace: %v", err)
	}
	defer func() {
		if err := netns.Set(original); err != nil {
			t.Errorf("could not restore original namespace: %v", err)
			return
		}
		runtime.UnlockOSThread()
	}()
	if err := (nativeKernel{}).registered(t.Context()); err != nil {
		t.Fatalf("WireGuard must be explicitly loaded by the runner administrator before this test: %v", err)
	}
	backend := New()
	lifecycle, err := manager.New(filepath.Join(t.TempDir(), "state"), backend)
	if err != nil {
		t.Fatal(err)
	}
	defer lifecycle.Close()
	if err := lifecycle.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	status, err := lifecycle.Connect(t.Context(), "isolated", []byte(runtimeConfig()))
	if err != nil {
		t.Fatal(err)
	}
	if status.Phase != manager.PhaseActive || !status.Observation.Exists || !status.Observation.Owned || !status.Observation.Up || len(status.Observation.Peers) != 1 {
		t.Fatalf("unexpected native observation: %#v", status.Observation)
	}
	if status.Observation.Peers[0].LastHandshakeUnix != 0 {
		t.Fatal("isolated unreachable peer unexpectedly reported a handshake")
	}
	if status.Plan.KillSwitchActive {
		t.Fatal("split-route runtime claimed a kill switch")
	}
	handle, err := routeHandle(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer handle.Close()
	item, err := handle.LinkByName(status.Target.InterfaceName)
	if err != nil {
		t.Fatal(err)
	}
	addresses, err := handle.AddrList(item, netlink.FAMILY_ALL)
	if err != nil || len(addresses) != 2 {
		t.Fatalf("IPv4/IPv6 addresses not installed: %v %v", len(addresses), err)
	}
	routes, err := handle.RouteList(item, netlink.FAMILY_ALL)
	if err != nil || len(routes) != 2 {
		t.Fatalf("owned split routes not installed: %v %v", len(routes), err)
	}
	if _, err := lifecycle.Connect(t.Context(), "isolated", []byte(runtimeConfig())); err != nil {
		t.Fatalf("idempotent reconnect: %v", err)
	}
	if _, err := lifecycle.Disconnect(t.Context(), "isolated"); err != nil {
		t.Fatal(err)
	}
	if _, err := handle.LinkByName(status.Target.InterfaceName); err == nil {
		t.Fatal("owned interface survived disconnect")
	}
	foreign := &netlink.Wireguard{LinkAttrs: netlink.LinkAttrs{Name: testTarget.InterfaceName, Alias: "foreign"}}
	if err := handle.LinkAdd(foreign); err != nil {
		t.Fatal(err)
	}
	if err := backend.Remove(t.Context(), testTarget); !errors.Is(err, manager.ErrOwnershipMismatch) {
		t.Fatal("foreign native interface was not rejected")
	}
	if _, err := handle.LinkByName(testTarget.InterfaceName); err != nil {
		t.Fatal("foreign native interface was removed")
	}
}
