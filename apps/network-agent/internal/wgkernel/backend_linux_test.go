package wgkernel

import (
	"context"
	"crypto/ecdh"
	"encoding/base64"
	"errors"
	"net/netip"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
)

var testTarget = manager.Target{ProfileID: "work", InterfaceName: "ebwg0123456789", OwnershipID: "0123456789abcdef0123456789abcdef"}

func keyOf(fill byte) string {
	var key [32]byte
	for i := range key {
		key[i] = fill
	}
	return base64.StdEncoding.EncodeToString(key[:])
}
func publicOf(fill byte) string {
	var data [32]byte
	for i := range data {
		data[i] = fill
	}
	key, _ := ecdh.X25519().NewPrivateKey(data[:])
	return base64.StdEncoding.EncodeToString(key.PublicKey().Bytes())
}
func runtimeConfig() string {
	return "[Interface]\nPrivateKey=" + keyOf(17) + "\nAddress=10.253.0.2/32,fd00:abcd::2/128\nListenPort=51820\n[Peer]\nPublicKey=" + publicOf(83) + "\nPresharedKey=" + keyOf(35) + "\nAllowedIPs=10.254.0.0/24,fd00:abce::/64\nEndpoint=198.51.100.50:51820\nPersistentKeepalive=25\n"
}

func withProfile(t *testing.T, text string, callback func(wireguard.RuntimeProfile) error) error {
	t.Helper()
	profile, err := wireguard.Parse([]byte(text))
	if err != nil {
		t.Fatal(err)
	}
	defer profile.Destroy()
	return profile.WithRuntime(callback)
}

type fakeKernel struct {
	item            *link
	state           snapshot
	mutations       []string
	fail            string
	registeredError error
	takeover        bool
	onMutation      func(string)
}

func (f *fakeKernel) registered(context.Context) error { return f.registeredError }
func (f *fakeKernel) lookup(context.Context, string) (link, error) {
	if f.item == nil {
		return link{}, errNotFound
	}
	return *f.item, nil
}
func (f *fakeKernel) snapshot(context.Context) (snapshot, error) { return f.state, nil }
func (f *fakeKernel) record(operation string) error {
	f.mutations = append(f.mutations, operation)
	if f.onMutation != nil {
		f.onMutation(operation)
	}
	if operation == f.fail {
		return errors.New("injected kernel failure")
	}
	return nil
}
func (f *fakeKernel) create(_ context.Context, name, alias string, mtu int) (link, error) {
	if err := f.record("create"); err != nil {
		return link{}, err
	}
	if f.item != nil {
		return link{}, errors.New("exclusive creation failed")
	}
	f.item = &link{index: 9, name: name, alias: alias, kind: "wireguard"}
	return *f.item, nil
}
func (f *fakeKernel) configure(context.Context, link, []byte) error {
	err := f.record("configure")
	if f.takeover {
		f.item.alias = "foreign"
	}
	return err
}
func (f *fakeKernel) address(context.Context, link, netip.Prefix) error { return f.record("address") }
func (f *fakeKernel) route(context.Context, link, netip.Prefix) error   { return f.record("route") }
func (f *fakeKernel) up(context.Context, link) error {
	if err := f.record("up"); err != nil {
		return err
	}
	f.item.up = true
	return nil
}
func (f *fakeKernel) peers(context.Context, link) ([]manager.PeerObservation, error) {
	if f.fail == "observe" {
		return nil, errors.New("observation failed")
	}
	return []manager.PeerObservation{{PublicKey: publicOf(83), LastHandshakeUnix: 1234, ReceiveBytes: 56, TransmitBytes: 78}}, nil
}
func (f *fakeKernel) remove(context.Context, link) error {
	if err := f.record("remove"); err != nil {
		return err
	}
	f.item = nil
	return nil
}

func TestOwnedSplitRouteLifecycle(t *testing.T) {
	fake := &fakeKernel{}
	backend := &Backend{kernel: fake}
	err := withProfile(t, runtimeConfig(), func(profile wireguard.RuntimeProfile) error {
		if err := backend.Prepare(t.Context(), testTarget, profile); err != nil {
			return err
		}
		if len(fake.mutations) != 0 {
			t.Fatal("preflight changed kernel state")
		}
		return backend.Apply(t.Context(), testTarget, profile)
	})
	if err != nil {
		t.Fatal(err)
	}
	want := []string{"create", "configure", "address", "address", "route", "route", "up"}
	if !slices.Equal(fake.mutations, want) {
		t.Fatalf("unexpected operation order: %v", fake.mutations)
	}
	observed, err := backend.Observe(t.Context(), testTarget)
	if err != nil || !observed.Exists || !observed.Owned || !observed.Up || len(observed.Peers) != 1 || observed.Peers[0].LastHandshakeUnix != 1234 {
		t.Fatalf("unexpected observation: %#v %v", observed, err)
	}
	if err := backend.Remove(t.Context(), testTarget); err != nil {
		t.Fatal(err)
	}
	if err := backend.Remove(t.Context(), testTarget); err != nil {
		t.Fatal(err)
	}
	if fake.item != nil || len(fake.mutations) != len(want)+1 {
		t.Fatal("remove was not idempotent")
	}
}

func TestUnsupportedProfilesAreRejectedBeforeMutation(t *testing.T) {
	base := runtimeConfig()
	for name, config := range map[string]string{
		"dns":              strings.Replace(base, "[Peer]", "DNS=10.254.0.1\n[Peer]", 1),
		"full_ipv4":        strings.Replace(base, "10.254.0.0/24", "0.0.0.0/0", 1),
		"full_ipv6":        strings.Replace(base, "fd00:abce::/64", "::/0", 1),
		"split_default":    strings.Replace(base, "10.254.0.0/24", "0.0.0.0/1,128.0.0.0/1", 1),
		"hostname":         strings.Replace(base, "198.51.100.50:51820", "vpn.example.org:51820", 1),
		"endpoint_loop":    strings.Replace(base, "198.51.100.50:51820", "10.254.0.1:51820", 1),
		"loopback_route":   strings.Replace(base, "10.254.0.0/24", "127.0.0.0/8", 1),
		"link_local_route": strings.Replace(base, "fd00:abce::/64", "fe80::/64", 1),
	} {
		t.Run(name, func(t *testing.T) {
			fake := &fakeKernel{}
			backend := &Backend{kernel: fake}
			err := withProfile(t, config, func(profile wireguard.RuntimeProfile) error { return backend.Apply(t.Context(), testTarget, profile) })
			if !errors.Is(err, manager.ErrUnsupportedProfile) || len(fake.mutations) != 0 {
				t.Fatalf("unsupported profile changed state: %v %v", err, fake.mutations)
			}
		})
	}
}

func TestPreflightRejectsCollisionsAndMissingKernel(t *testing.T) {
	for name, fake := range map[string]*fakeKernel{
		"existing_foreign_link": {item: &link{index: 7, name: testTarget.InterfaceName, alias: "foreign", kind: "wireguard"}},
		"existing_owned_link":   {item: &link{index: 7, name: testTarget.InterfaceName, alias: ownerPrefix + testTarget.OwnershipID, kind: "wireguard"}},
		"address_overlap":       {state: snapshot{addresses: []netip.Prefix{netip.MustParsePrefix("10.253.0.1/24")}}},
		"route_overlap":         {state: snapshot{routes: []route{{prefix: netip.MustParsePrefix("10.254.0.128/25")}}}},
		"module_absent":         {registeredError: manager.ErrUnavailable},
	} {
		t.Run(name, func(t *testing.T) {
			backend := &Backend{kernel: fake}
			err := withProfile(t, runtimeConfig(), func(profile wireguard.RuntimeProfile) error { return backend.Prepare(t.Context(), testTarget, profile) })
			if err == nil || len(fake.mutations) != 0 {
				t.Fatal("unsafe preflight accepted")
			}
		})
	}
	fake := &fakeKernel{state: snapshot{routes: []route{{prefix: netip.MustParsePrefix("0.0.0.0/0")}, {prefix: netip.MustParsePrefix("::/0")}}}}
	if err := withProfile(t, runtimeConfig(), func(profile wireguard.RuntimeProfile) error {
		return (&Backend{kernel: fake}).Prepare(t.Context(), testTarget, profile)
	}); err != nil {
		t.Fatalf("default uplinks must not block safe split routes: %v", err)
	}
}

func TestForeignInterfaceIsNeverDeletedOrAdopted(t *testing.T) {
	for _, item := range []link{{index: 9, name: testTarget.InterfaceName, alias: "foreign", kind: "wireguard"}, {index: 9, name: testTarget.InterfaceName, alias: ownerPrefix + testTarget.OwnershipID, kind: "dummy"}, {index: 9, name: "foreign", alias: ownerPrefix + testTarget.OwnershipID, kind: "wireguard"}} {
		fake := &fakeKernel{item: &item}
		backend := &Backend{kernel: fake}
		if err := backend.Remove(t.Context(), testTarget); !errors.Is(err, manager.ErrOwnershipMismatch) {
			t.Fatal("foreign removal accepted")
		}
		if _, err := backend.Observe(t.Context(), testTarget); !errors.Is(err, manager.ErrOwnershipMismatch) {
			t.Fatal("foreign observation accepted")
		}
		if len(fake.mutations) != 0 {
			t.Fatal("foreign interface mutated")
		}
	}
	invalid := testTarget
	invalid.InterfaceName = "eth0"
	if err := (&Backend{kernel: &fakeKernel{}}).Remove(t.Context(), invalid); !errors.Is(err, manager.ErrOwnershipMismatch) {
		t.Fatal("arbitrary interface name accepted")
	}
}

func TestLifecycleManagerRollsBackEachPartialApplyFailure(t *testing.T) {
	for _, failure := range []string{"create", "configure", "address", "route", "up", "observe"} {
		t.Run(failure, func(t *testing.T) {
			fake := &fakeKernel{fail: failure}
			lifecycle, err := manager.New(filepath.Join(t.TempDir(), "state"), &Backend{kernel: fake})
			if err != nil {
				t.Fatal(err)
			}
			defer lifecycle.Close()
			if err := lifecycle.Recover(t.Context()); err != nil {
				t.Fatal(err)
			}
			status, err := lifecycle.Connect(t.Context(), "work", []byte(runtimeConfig()))
			if err == nil || status.Phase != manager.PhaseFailed || fake.item != nil {
				t.Fatalf("partial kernel state survived: phase=%s error=%v item=%v", status.Phase, err, fake.item != nil)
			}
			if slices.Contains(fake.mutations, "up") && failure != "up" && failure != "observe" {
				t.Fatal("interface brought up after earlier failure")
			}
		})
	}
}

func TestTakeoverStopsWritesAndRequiresRecovery(t *testing.T) {
	fake := &fakeKernel{takeover: true}
	lifecycle, err := manager.New(filepath.Join(t.TempDir(), "state"), &Backend{kernel: fake})
	if err != nil {
		t.Fatal(err)
	}
	defer lifecycle.Close()
	if err := lifecycle.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	status, err := lifecycle.Connect(t.Context(), "work", []byte(runtimeConfig()))
	if err == nil || status.Phase != manager.PhaseRecoveryRequired || fake.item == nil || fake.item.alias != "foreign" || slices.Contains(fake.mutations, "remove") || slices.Contains(fake.mutations, "address") {
		t.Fatalf("takeover did not fail closed: phase=%s ops=%v", status.Phase, fake.mutations)
	}
}

func TestCancellationAfterMutationRollsBackWithIndependentContext(t *testing.T) {
	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()
	fake := &fakeKernel{onMutation: func(operation string) {
		if operation == "configure" {
			cancel()
		}
	}}
	lifecycle, err := manager.New(filepath.Join(t.TempDir(), "state"), &Backend{kernel: fake})
	if err != nil {
		t.Fatal(err)
	}
	defer lifecycle.Close()
	if err := lifecycle.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	status, err := lifecycle.Connect(ctx, "work", []byte(runtimeConfig()))
	if err == nil || status.Phase != manager.PhaseCancelled || fake.item != nil || !slices.Contains(fake.mutations, "remove") {
		t.Fatalf("cancelled apply was not rolled back: %s %v", status.Phase, err)
	}
}
