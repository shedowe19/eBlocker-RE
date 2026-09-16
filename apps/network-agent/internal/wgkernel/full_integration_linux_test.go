package wgkernel

import (
	"errors"
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/vishvananda/netlink"
	"github.com/vishvananda/netns"
	"golang.org/x/sys/unix"
)

// This separately gated test must only run in a disposable privileged runner.
// It isolates its thread before every socket/sysctl/link operation. It proves
// native readback and output packet rejection after forced interface loss; an
// external WireGuard server, handshake or appliance feature test is not implied.
func TestNativeFullTunnelInIsolatedNamespace(t *testing.T) {
	if os.Getenv("EBLOCKER_WIREGUARD_INTEGRATION") != "1" {
		t.Skip("requires explicit disposable privileged WireGuard/nftables runner")
	}
	runtime.LockOSThread()
	original, err := netns.Get()
	if err != nil {
		runtime.UnlockOSThread()
		t.Fatal(err)
	}
	defer original.Close()
	if err = unix.Unshare(unix.CLONE_NEWNET); err != nil {
		runtime.UnlockOSThread()
		t.Fatalf("refusing test without new network namespace: %v", err)
	}
	defer func() {
		if err := netns.Set(original); err != nil {
			t.Errorf("namespace restore: %v", err)
			return
		}
		runtime.UnlockOSThread()
	}()
	if err = (nativeKernel{}).registered(t.Context()); err != nil {
		t.Fatal("runner must preload WireGuard:", err)
	}
	if _, err = os.Stat("/sys/module/nf_tables"); err != nil {
		t.Fatal("runner must preload nf_tables:", err)
	}
	h, err := routeHandle(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer h.Close()
	// Only namespaced sysctls are changed, after successful isolation. The runtime
	// itself rejects strict rp_filter and never rewrites these settings.
	for _, name := range []string{"all", "default"} {
		if err = os.WriteFile("/proc/sys/net/ipv4/conf/"+name+"/rp_filter", []byte("0\n"), 0600); err != nil {
			t.Fatal(err)
		}
	}
	veth := &netlink.Veth{LinkAttrs: netlink.LinkAttrs{Name: "ebtest0"}, PeerName: "ebtest1"}
	if err = h.LinkAdd(veth); err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"lo", "ebtest0", "ebtest1"} {
		item, err := h.LinkByName(name)
		if err != nil {
			t.Fatal(err)
		}
		if err = h.LinkSetUp(item); err != nil {
			t.Fatal(err)
		}
	}
	uplink, err := h.LinkByName("ebtest0")
	if err != nil {
		t.Fatal(err)
	}
	for _, text := range []string{"192.0.2.2/24", "2001:db8:1::2/64"} {
		address, err := netlink.ParseAddr(text)
		if err != nil {
			t.Fatal(err)
		}
		address.Flags = unix.IFA_F_NODAD
		if err = h.AddrAdd(uplink, address); err != nil {
			t.Fatal(err)
		}
	}
	for _, gateway := range []string{"192.0.2.1", "2001:db8:1::1"} {
		r := netlink.Route{LinkIndex: uplink.Attrs().Index, Gw: net.ParseIP(gateway), Table: unix.RT_TABLE_MAIN, Protocol: unix.RTPROT_STATIC}
		if err = h.RouteAdd(&r); err != nil {
			t.Fatal(err)
		}
	}
	// A private interface loss must not fall back to cleartext through either
	// family's original default route. Test dual stack and a v4-only tunnel.
	for _, variant := range []string{"dual-stack", "ipv4-only", "ipv6-endpoint"} {
		config := fullConfig()
		if variant == "ipv4-only" {
			config = strings.Replace(config, "0.0.0.0/0,::/0", "0.0.0.0/0", 1)
		}
		if variant == "ipv6-endpoint" {
			config = strings.Replace(config, "198.51.100.50:51820", "[2001:db8:2::50]:51820", 1)
		}
		stateDirectory := filepath.Join(t.TempDir(), "state")
		m, err := manager.New(stateDirectory, nativeTestBackend(t))
		if err != nil {
			t.Fatal(err)
		}
		if err = m.Recover(t.Context()); err != nil {
			m.Close()
			t.Fatal(err)
		}
		status, err := m.Connect(t.Context(), "native", []byte(config))
		if err != nil {
			m.Close()
			t.Fatalf("%s connect: %v", variant, err)
		}
		if !status.KillSwitchActive || status.Policy == nil || status.Observation.Policy == nil || !status.Observation.Policy.Matches(*status.Policy) {
			m.Close()
			t.Fatalf("%s missing native attestation", variant)
		}
		p := *status.Policy
		if err = m.Close(); err != nil {
			t.Fatal(err)
		}
		m, err = manager.New(stateDirectory, nativeTestBackend(t))
		if err != nil {
			t.Fatal(err)
		}
		if err = m.Recover(t.Context()); err != nil {
			m.Close()
			t.Fatalf("%s recovery of active full tunnel: %v", variant, err)
		}
		recovered, err := m.Status(t.Context(), "native")
		if err != nil || recovered.Target != status.Target || !recovered.KillSwitchActive {
			m.Close()
			t.Fatalf("%s full tunnel not freshly attested after recovery: %v", variant, err)
		}
		item, err := h.LinkByName(status.Target.InterfaceName)
		if err != nil {
			m.Close()
			t.Fatal(err)
		}
		// Force a real link loss without using runtime cleanup, leaving guard and rules.
		if err = h.LinkDel(item); err != nil {
			m.Close()
			t.Fatal(err)
		}
		for _, entry := range []struct {
			address string
			port    uint16
			mark    uint32
			drop    bool
		}{
			{"203.0.113.1", 443, 0, true}, {"2001:db8:ffff::1", 443, 0, true},
			{"192.0.2.53", 53, 0, true}, {"2001:db8:1::53", 853, 0, true},
			{p.Endpoints[0].Address, p.Endpoints[0].Port, 0, true},
			{p.Endpoints[0].Address, p.Endpoints[0].Port + 1, p.FirewallMark, true},
			{p.Endpoints[0].Address, p.Endpoints[0].Port, p.FirewallMark, false},
			{"192.0.2.8", 8080, 0, false}, {"2001:db8:1::8", 8080, 0, false},
		} {
			err := sendNativeUDP(entry.address, entry.port, entry.mark)
			if entry.drop && !errors.Is(err, unix.EPERM) {
				m.Close()
				t.Fatalf("%s: unprotected packet to %s:%d was not blocked by output guard: %v", variant, entry.address, entry.port, err)
			}
			if !entry.drop && err != nil {
				m.Close()
				t.Fatalf("%s: allowed endpoint/local packet rejected: %v", variant, err)
			}
		}
		observed, _ := m.Status(t.Context(), "native")
		if observed.KillSwitchActive {
			m.Close()
			t.Fatal("missing link retained protection claim")
		}
		if variant == "dual-stack" {
			if err = m.Close(); err != nil {
				t.Fatal(err)
			}
			m, err = manager.New(stateDirectory, nativeTestBackend(t))
			if err != nil {
				t.Fatal(err)
			}
			if err = m.Recover(t.Context()); err != nil {
				m.Close()
				t.Fatal("native recovery cleanup after link loss:", err)
			}
		} else {
			if _, err = m.Disconnect(t.Context(), "native"); err != nil {
				m.Close()
				t.Fatal("cleanup after link loss:", err)
			}
		}
		if err = m.Close(); err != nil {
			t.Fatal(err)
		}
		if exists, _, err := (nativeFullKernel{}).guard(t.Context(), p); err != nil || exists {
			t.Fatal("owned guard survived cleanup", err)
		}
	}
}
func sendNativeUDP(address string, port uint16, mark uint32) error {
	ip := net.ParseIP(address)
	family := unix.AF_INET6
	var target unix.Sockaddr
	if ip4 := ip.To4(); ip4 != nil {
		family = unix.AF_INET
		sa := &unix.SockaddrInet4{Port: int(port)}
		copy(sa.Addr[:], ip4)
		target = sa
	} else {
		sa := &unix.SockaddrInet6{Port: int(port)}
		copy(sa.Addr[:], ip.To16())
		target = sa
	}
	fd, err := unix.Socket(family, unix.SOCK_DGRAM|unix.SOCK_CLOEXEC, 0)
	if err != nil {
		return err
	}
	defer unix.Close(fd)
	if mark != 0 {
		if err = unix.SetsockoptInt(fd, unix.SOL_SOCKET, unix.SO_MARK, int(mark)); err != nil {
			return err
		}
	}
	return unix.Sendto(fd, []byte("eblocker-isolated-output-probe"), 0, target)
}
