package wgkernel

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard"
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
	backend := nativeTestBackend(t)
	stateDirectory := filepath.Join(t.TempDir(), "state")
	lifecycle, err := manager.New(stateDirectory, backend)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if lifecycle != nil {
			lifecycle.Close()
		}
	}()
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
	// Reopening the durable journal must retain the verified live link instead
	// of creating a duplicate or treating an old observation as current state.
	if err := lifecycle.Close(); err != nil {
		t.Fatal(err)
	}
	lifecycle, err = manager.New(stateDirectory, nativeTestBackend(t))
	if err != nil {
		t.Fatal(err)
	}
	if err := lifecycle.Recover(t.Context()); err != nil {
		t.Fatalf("native active recovery: %v", err)
	}
	recovered, err := lifecycle.Status(t.Context(), "isolated")
	if err != nil || recovered.Phase != manager.PhaseActive || recovered.Target != status.Target {
		t.Fatalf("active target did not survive recovery: %v", err)
	}
	if _, err := lifecycle.Disconnect(t.Context(), "isolated"); err != nil {
		t.Fatal(err)
	}
	if _, err := handle.LinkByName(status.Target.InterfaceName); err == nil {
		t.Fatal("owned interface survived disconnect")
	}
	verifyNativeHandshake(t, lifecycle)
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

// Both transport sockets stay inside the already isolated namespace. The peer
// has no tunnel address or route installed, so a UDP probe cannot bypass the
// tunnel via the local table. Its authenticated receive counter is the oracle;
// this is not a claim about an external provider or appliance forwarding.
func verifyNativeHandshake(t *testing.T, lifecycle *manager.Manager) {
	t.Helper()
	h, err := routeHandle(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	loopback, err := h.LinkByName("lo")
	if err != nil {
		t.Fatal(err)
	}
	if err := h.LinkSetUp(loopback); err != nil {
		t.Fatal(err)
	}
	kernel := nativeKernel{}
	peer, err := kernel.create(t.Context(), "ebwgaaaaaaaaaa", ownerPrefix+strings.Repeat("a", 32), 1420)
	if err != nil {
		t.Fatal(err)
	}
	defer func() {
		if err := kernel.remove(t.Context(), peer); err != nil {
			t.Errorf("native test peer cleanup: %v", err)
		}
	}()
	peerConfig := "[Interface]\nPrivateKey=" + keyOf(83) + "\nAddress=10.254.0.1/32\nListenPort=51821\n[Peer]\nPublicKey=" + publicOf(17) + "\nPresharedKey=" + keyOf(35) + "\nAllowedIPs=10.253.0.2/32,fd00:abcd::2/128\nEndpoint=127.0.0.1:51820\n"
	err = withProfile(t, peerConfig, func(profile wireguard.RuntimeProfile) error {
		encoded, err := encodeDevice(peer.index, profile)
		if err != nil {
			return err
		}
		defer clear(encoded)
		return kernel.configure(t.Context(), peer, encoded)
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := kernel.up(t.Context(), peer); err != nil {
		t.Fatal(err)
	}
	config := strings.Replace(runtimeConfig(), "198.51.100.50:51820", "127.0.0.1:51821", 1)
	if _, err := lifecycle.Connect(t.Context(), "handshake", []byte(config)); err != nil {
		t.Fatalf("local peer connect: %v", err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		if err := sendNativeUDP("10.254.0.1", 53535, 0); err != nil {
			t.Fatalf("native tunnel probe: %v", err)
		}
		observed, err := lifecycle.Status(t.Context(), "handshake")
		if err != nil {
			t.Fatal(err)
		}
		peers, err := kernel.peers(t.Context(), peer)
		if err != nil {
			t.Fatal(err)
		}
		if len(observed.Observation.Peers) == 1 && len(peers) == 1 && observed.Observation.Peers[0].LastHandshakeUnix > 0 && peers[0].LastHandshakeUnix > 0 && peers[0].ReceiveBytes >= 64 && observed.Observation.Peers[0].TransmitBytes >= 64 {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("isolated peers did not complete a handshake and authenticated payload transfer")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if _, err := lifecycle.Disconnect(t.Context(), "handshake"); err != nil {
		t.Fatal(err)
	}
}
