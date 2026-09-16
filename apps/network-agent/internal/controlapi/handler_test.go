package controlapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
)

const config = "[Interface]\nPrivateKey=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\nAddress=10.20.0.2/32\n[Peer]\nPublicKey=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\nAllowedIPs=10.99.0.0/16\nEndpoint=198.51.100.1:51820\n"

var identity = Identity{UID: 1200, GID: 1201}

type testBackend struct {
	target            manager.Target
	peers             []manager.PeerObservation
	started           chan struct{}
	removeErr         error
	applies           int
	cancelledRollback bool
}

func (b *testBackend) Prepare(context.Context, manager.Target, wireguard.RuntimeProfile) error {
	return nil
}
func (b *testBackend) Apply(ctx context.Context, t manager.Target, p wireguard.RuntimeProfile) error {
	b.applies++
	b.target = t
	b.peers = []manager.PeerObservation{}
	for _, peer := range p.Plan().Peers {
		b.peers = append(b.peers, manager.PeerObservation{PublicKey: peer.PublicKey})
	}
	if b.started != nil {
		close(b.started)
		<-ctx.Done()
		return ctx.Err()
	}
	return nil
}
func (b *testBackend) Observe(context.Context, manager.Target) (manager.Observation, error) {
	return manager.Observation{Exists: b.target.InterfaceName != "", Owned: b.target.InterfaceName != "", Up: b.target.InterfaceName != "", Peers: b.peers}, nil
}
func (b *testBackend) Rollback(ctx context.Context, t manager.Target) error {
	b.cancelledRollback = ctx.Err() != nil
	return b.Remove(ctx, t)
}
func (b *testBackend) Remove(_ context.Context, t manager.Target) error {
	if b.removeErr != nil {
		return b.removeErr
	}
	if b.target.InterfaceName != "" && b.target != t {
		return manager.ErrOwnershipMismatch
	}
	b.target = manager.Target{}
	b.peers = []manager.PeerObservation{}
	return nil
}

func fixtureHandler(t *testing.T) (*Handler, *testBackend, string) {
	t.Helper()
	b := &testBackend{peers: []manager.PeerObservation{}}
	directory := filepath.Join(t.TempDir(), "state")
	lifecycle, err := manager.New(directory, b)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = lifecycle.Close() })
	if err := lifecycle.Recover(t.Context()); err != nil {
		t.Fatal(err)
	}
	return New(lifecycle, identity), b, directory
}
func call(h http.Handler, method, path, body string, peer *Identity) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if peer != nil {
		r = r.WithContext(context.WithValue(r.Context(), peerKey{}, *peer))
	}
	if method == http.MethodPut || method == http.MethodPost {
		r.Header.Set("Content-Type", "application/json")
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}
func importBody(configuration string) string {
	data, _ := json.Marshal(struct {
		Version       int    `json:"schemaVersion"`
		Configuration string `json:"configuration"`
	}{1, configuration})
	return string(data)
}
func mustStatus(t *testing.T, w *httptest.ResponseRecorder, status int) {
	t.Helper()
	if w.Code != status {
		t.Fatalf("got %d: %s, want %d", w.Code, w.Body.String(), status)
	}
	if w.Header().Get("Content-Length") == "" || w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("missing response framing/privacy headers")
	}
}

func TestRealManagerControlProfileLifecycleAndSecretRetention(t *testing.T) {
	h, b, directory := fixtureHandler(t)
	mustStatus(t, call(h, "PUT", "/v1/profiles/home", importBody(config), &identity), 200)
	for _, name := range []string{"home.profile", ".lock"} {
		info, err := os.Stat(filepath.Join(directory, name))
		if err != nil || info.Mode().Perm() != 0600 {
			t.Fatal("profile storage is not private")
		}
	}
	status := call(h, "GET", "/v1/profiles/home", "", &identity)
	mustStatus(t, status, 200)
	if !strings.Contains(status.Body.String(), `"runtime":null`) {
		t.Fatal("import performed an implicit network operation")
	}
	mustStatus(t, call(h, "GET", "/v1/profiles", "", &identity), 200)
	status = call(h, "POST", "/v1/profiles/home/connect", `{"schemaVersion":1}`, &identity)
	mustStatus(t, status, 200)
	if b.applies != 1 || !strings.Contains(status.Body.String(), `"phase":"active"`) || strings.Contains(status.Body.String(), "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=") {
		t.Fatal("invalid active response or secret leak")
	}
	mustStatus(t, call(h, "PUT", "/v1/profiles/home", importBody(config), &identity), 409)
	mustStatus(t, call(h, "DELETE", "/v1/profiles/home", "", &identity), 409)
	mustStatus(t, call(h, "POST", "/v1/profiles/home/disconnect", `{"schemaVersion":1}`, &identity), 200)
	if _, err := os.Stat(filepath.Join(directory, "home.conf")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("runtime secret not deleted")
	}
	if _, err := os.Stat(filepath.Join(directory, "home.profile")); err != nil {
		t.Fatal("import source unexpectedly lost")
	}
	mustStatus(t, call(h, "POST", "/v1/profiles/home/connect", `{"schemaVersion":1}`, &identity), 200)
	if b.applies != 2 {
		t.Fatal("retained import could not reconnect")
	}
	mustStatus(t, call(h, "POST", "/v1/profiles/home/disconnect", `{"schemaVersion":1}`, &identity), 200)
	mustStatus(t, call(h, "DELETE", "/v1/profiles/home", "", &identity), 200)
	mustStatus(t, call(h, "DELETE", "/v1/profiles/home", "", &identity), 200)
	if _, err := os.Stat(filepath.Join(directory, "home.profile")); !errors.Is(err, os.ErrNotExist) {
		t.Fatal("delete retained import secret")
	}
	mustStatus(t, call(h, "GET", "/v1/profiles/home", "", &identity), 404)
}

func TestPeerIdentityCannotBeSuppliedOrBroadenedByHeaders(t *testing.T) {
	h, _, _ := fixtureHandler(t)
	for _, peer := range []*Identity{nil, {UID: identity.UID + 1, GID: identity.GID}, {UID: identity.UID, GID: identity.GID + 1}, {UID: 0, GID: 0}} {
		r := httptest.NewRequest("GET", "/v1/profiles", nil)
		r.Header.Set("X-Peer-UID", "1200")
		r.Header.Set("X-Peer-GID", "1201")
		if peer != nil {
			r = r.WithContext(context.WithValue(r.Context(), peerKey{}, *peer))
		}
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		mustStatus(t, w, 403)
	}
}

func TestStrictRequestsAndErrorsNeverEchoUntrustedConfiguration(t *testing.T) {
	h, _, _ := fixtureHandler(t)
	for _, tc := range []struct {
		method, path, body string
		status             int
	}{
		{"PUT", "/v1/profiles/home", `{"schemaVersion":1,"configuration":"PRIVATE-CANARY"}`, 422},
		{"PUT", "/v1/profiles/home", `{"schemaVersion":1,"schemaVersion":1,"configuration":"PRIVATE-CANARY"}`, 400},
		{"PUT", "/v1/profiles/home", `{"SchemaVersion":1,"configuration":"PRIVATE-CANARY"}`, 400},
		{"PUT", "/v1/profiles/home", `{"schemaVersion":2,"configuration":"PRIVATE-CANARY"}`, 400},
		{"PUT", "/v1/profiles/home", `{"schemaVersion":1,"configuration":null}`, 400},
		{"PUT", "/v1/profiles/home", importBody(config) + ` {}`, 400},
		{"POST", "/v1/profiles/home/connect", `{"schemaVersion":1,"configuration":"PRIVATE-CANARY"}`, 400},
		{"POST", "/v1/profiles/home/connect", `{"schemaVersion":1,"x":"PRIVATE-CANARY"}`, 400},
		{"GET", "/v1/profiles", "PRIVATE-CANARY", 400},
		{"GET", "/v1/profiles?query=PRIVATE-CANARY", "", 400},
		{"GET", "/v1/profiles/bad_ID", "", 400},
		{"GET", "/v1/profiles/home/", "", 404},
		{"GET", "/v1/profiles/home/connect", "", 405},
		{"POST", "/v1/profiles", `{"schemaVersion":1}`, 405},
		{"PUT", "/v1/profiles/home", importBody(strings.Repeat("x", wireguard.MaxConfigBytes+1)), 413},
		{"PUT", "/v1/profiles/home", strings.Repeat(" ", MaxRequestBytes) + "{}", 413},
	} {
		t.Run(tc.method+tc.path+strconvName(tc.body), func(t *testing.T) {
			w := call(h, tc.method, tc.path, tc.body, &identity)
			mustStatus(t, w, tc.status)
			if strings.Contains(w.Body.String(), "PRIVATE-CANARY") {
				t.Fatal("untrusted data escaped")
			}
		})
	}
	for _, contentType := range []string{"text/plain", "application/json;broken"} {
		r := httptest.NewRequest("PUT", "/v1/profiles/home", strings.NewReader(importBody(config)))
		r.Header.Set("Content-Type", contentType)
		r = r.WithContext(context.WithValue(r.Context(), peerKey{}, identity))
		w := httptest.NewRecorder()
		h.ServeHTTP(w, r)
		mustStatus(t, w, 415)
	}
}
func strconvName(body string) string {
	if len(body) > 40 {
		return body[:40]
	}
	return body
}

func TestCancelBypassesBusyGateAndTimeoutRollsBack(t *testing.T) {
	for _, timed := range []bool{false, true} {
		t.Run(map[bool]string{false: "cancel", true: "timeout"}[timed], func(t *testing.T) {
			h, b, _ := fixtureHandler(t)
			mustStatus(t, call(h, "PUT", "/v1/profiles/home", importBody(config), &identity), 200)
			b.started = make(chan struct{})
			if timed {
				h.timeout = 20 * time.Millisecond
			}
			result := make(chan *httptest.ResponseRecorder, 1)
			go func() { result <- call(h, "POST", "/v1/profiles/home/connect", `{"schemaVersion":1}`, &identity) }()
			<-b.started
			if !timed {
				mustStatus(t, call(h, "GET", "/v1/profiles", "", &identity), 503)
				cancelled := call(h, "POST", "/v1/profiles/home/cancel", `{"schemaVersion":1}`, &identity)
				mustStatus(t, cancelled, 200)
				if !strings.Contains(cancelled.Body.String(), `"cancellationRequested":true`) {
					t.Fatal("active cancellation unavailable")
				}
			}
			want := 409
			if timed {
				want = 504
			}
			mustStatus(t, <-result, want)
			if b.cancelledRollback || b.target.InterfaceName != "" {
				t.Fatal("cancellation left partial owned state")
			}
		})
	}
}

func TestFailedDisconnectCannotDeleteOwnedResourcesOrExposeDriverError(t *testing.T) {
	h, b, _ := fixtureHandler(t)
	mustStatus(t, call(h, "PUT", "/v1/profiles/home", importBody(config), &identity), 200)
	mustStatus(t, call(h, "POST", "/v1/profiles/home/connect", `{"schemaVersion":1}`, &identity), 200)
	b.removeErr = errors.New("PRIVATE-CANARY")
	w := call(h, "POST", "/v1/profiles/home/disconnect", `{"schemaVersion":1}`, &identity)
	mustStatus(t, w, 503)
	if strings.Contains(w.Body.String(), "PRIVATE-CANARY") {
		t.Fatal("backend detail leaked")
	}
	mustStatus(t, call(h, "DELETE", "/v1/profiles/home", "", &identity), 409)
	b.removeErr = nil
	mustStatus(t, call(h, "POST", "/v1/profiles/home/disconnect", `{"schemaVersion":1}`, &identity), 200)
	mustStatus(t, call(h, "DELETE", "/v1/profiles/home", "", &identity), 200)
}

type goldenLifecycle struct {
	Lifecycle
	profile manager.StoredProfile
	status  manager.Status
}

func (g goldenLifecycle) Profiles(context.Context) ([]manager.StoredProfile, error) {
	return []manager.StoredProfile{g.profile}, nil
}
func (g goldenLifecycle) Profile(context.Context, string) (manager.StoredProfile, error) {
	return g.profile, nil
}
func (g goldenLifecycle) Status(context.Context, string) (manager.Status, error) {
	return g.status, nil
}
func (g goldenLifecycle) ImportProfile(context.Context, string, []byte) (manager.StoredProfile, error) {
	p := g.profile
	p.Phase = "imported"
	return p, nil
}
func (g goldenLifecycle) DeleteProfile(context.Context, string) error { return nil }

// Shared goldens are produced by the actual handler and typed public models;
// Java and React consume these exact bytes to detect protocol shape drift.
func TestSharedControlContractGoldens(t *testing.T) {
	profile, err := wireguard.Parse([]byte(strings.Replace(config, "10.99.0.0/16", "0.0.0.0/0,::/0", 1)))
	if err != nil {
		t.Fatal(err)
	}
	defer profile.Destroy()
	target := manager.Target{ProfileID: "home", InterfaceName: "ebwg1234567890", OwnershipID: "1234567890abcdef1234567890abcdef"}
	p, err := policy.Build(target.PolicyOwner(), profile.Plan(), policy.Environment{LocalInterfaces: []string{"eth0"}, LocalNetworks: []string{"192.168.1.0/24"}, Endpoints: []policy.Endpoint{{Address: "198.51.100.1", Port: 51820, InterfaceName: "eth0"}}})
	if err != nil {
		t.Fatal(err)
	}
	a := policy.Attestation{Owner: p.Owner, Digest: p.Digest, RoutingVerified: true, FirewallVerified: true, MarkVerified: true, EndpointsVerified: true, IPv4PoliciesVerified: true, IPv6PoliciesVerified: true, KillSwitchActive: true}
	g := goldenLifecycle{profile: manager.StoredProfile{ProfileID: "home", Phase: "active", Plan: profile.Plan()}, status: manager.Status{SchemaVersion: 1, Target: target, Phase: "active", Plan: profile.Plan(), Policy: &p, KillSwitchActive: true, Observation: manager.Observation{Exists: true, Owned: true, Up: true, Peers: []manager.PeerObservation{{PublicKey: profile.Plan().Peers[0].PublicKey, LastHandshakeUnix: 1700000000, ReceiveBytes: 12345, TransmitBytes: 54321}}, Policy: &a}}}
	h := New(g, identity)
	for _, tc := range []struct{ name, method, path, body string }{{"profile-list.json", "GET", "/v1/profiles", ""}, {"profile-status.json", "GET", "/v1/profiles/home", ""}, {"import-result.json", "PUT", "/v1/profiles/home", importBody(config)}, {"delete-result.json", "DELETE", "/v1/profiles/home", ""}} {
		w := call(h, tc.method, tc.path, tc.body, &identity)
		mustStatus(t, w, 200)
		golden(t, tc.name, w.Body.Bytes())
	}
	empty, _, _ := fixtureHandler(t)
	w := call(empty, "GET", "/v1/profiles", "", &identity)
	golden(t, "empty-list.json", w.Body.Bytes())
	public := map[string]any{}
	for code, definition := range errorsByCode {
		public[code] = struct {
			Status  int    `json:"status"`
			Message string `json:"message"`
		}{definition.Status, definition.Message}
	}
	data, _ := json.Marshal(public)
	golden(t, "errors.json", data)
}
func golden(t *testing.T, name string, data []byte) {
	t.Helper()
	var value any
	if json.Unmarshal(data, &value) != nil {
		t.Fatal("invalid fixture")
	}
	data, _ = json.MarshalIndent(value, "", "  ")
	data = append(data, '\n')
	path := filepath.Join("../../../../contracts/wireguard-control/v1", name)
	if os.Getenv("UPDATE_WIREGUARD_CONTROL_GOLDEN") == "1" {
		if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, data, 0644); err != nil {
			t.Fatal(err)
		}
	}
	want, err := os.ReadFile(path)
	if err != nil || string(want) != string(data) {
		t.Fatalf("shared contract changed: %s (%v)", name, err)
	}
}
