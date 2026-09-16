package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/apps/network-agent/internal/network"
)

type fakeProvider struct{ err error }

func (p fakeProvider) Interfaces(context.Context) ([]network.Interface, error) {
	return []network.Interface{{Index: 2, Name: "lan0", Up: true, Addresses: []network.Address{{Prefix: "fe80::1/64", Family: "ipv6", Scope: "link"}}}}, p.err
}
func (p fakeProvider) Routes(context.Context) ([]network.Route, error) {
	return []network.Route{{Family: "ipv6", Destination: "::/0", Gateway: "fe80::abcd", InterfaceIndex: 2, Table: 254}}, p.err
}
func (p fakeProvider) WireGuard(context.Context) (network.WireGuardCapability, error) {
	return network.WireGuardCapability{State: "not_registered", Reason: "Not loaded"}, p.err
}

func request(handler http.Handler, method, path, body, contentType string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	if contentType != "" {
		r.Header.Set("Content-Type", contentType)
	}
	w := httptest.NewRecorder()
	handler.ServeHTTP(w, r)
	return w
}

func TestObservedStatusAndResponseFraming(t *testing.T) {
	w := request(New(fakeProvider{}), "GET", "/v1/status", "", "")
	if w.Code != 200 {
		t.Fatalf("status: %d %s", w.Code, w.Body)
	}
	if w.Header().Get("Content-Length") != strconv.Itoa(w.Body.Len()) {
		t.Fatal("missing or incorrect content length")
	}
	if w.Header().Get("Cache-Control") != "no-store" {
		t.Fatal("observation must not be cached")
	}
	var response struct {
		SchemaVersion int
		Data          struct {
			ReadOnly     bool
			Interfaces   []network.Interface
			Routes       []network.Route
			Capabilities capabilities
		}
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SchemaVersion != 1 || !response.Data.ReadOnly || !response.Data.Capabilities.ReadOnly || response.Data.Capabilities.WireGuard.Management {
		t.Fatal("invalid read-only capabilities")
	}
	if response.Data.Interfaces[0].Addresses[0].Prefix != "fe80::1/64" || response.Data.Routes[0].Gateway != "fe80::abcd" {
		t.Fatal("IPv6 data was lost")
	}
}

func TestMethodsBodiesAndRoutesAreRestricted(t *testing.T) {
	for _, test := range []struct {
		method, path, body string
		status             int
		allow              string
	}{
		{"POST", "/v1/routes", `{"command":"ip route flush"}`, 405, "GET"},
		{"GET", "/v1/wireguard/validate", "", 405, "POST"},
		{"DELETE", "/v1/interfaces", "", 405, "GET"},
		{"HEAD", "/v1/status", "", 405, "GET"},
		{"GET", "/v1/status?command=anything", "", 400, ""},
		{"GET", "/v1/interfaces", "x", 400, ""},
		{"GET", "/v1/exec", "", 404, ""},
		{"GET", "/v1/status/", "", 404, ""},
	} {
		t.Run(test.method+test.path, func(t *testing.T) {
			w := request(New(fakeProvider{}), test.method, test.path, test.body, "")
			if w.Code != test.status || w.Header().Get("Allow") != test.allow {
				t.Fatalf("unexpected response %d %s", w.Code, w.Body)
			}
		})
	}
}

func TestProviderFailureDoesNotExposeDetails(t *testing.T) {
	for _, path := range []string{"/v1/status", "/v1/interfaces", "/v1/routes", "/v1/capabilities"} {
		w := request(New(fakeProvider{err: errors.New("sensitive provider detail")}), "GET", path, "", "")
		if w.Code != 503 || strings.Contains(w.Body.String(), "sensitive") {
			t.Fatalf("unsafe provider error: %d %s", w.Code, w.Body)
		}
	}
	w := request(New(fakeProvider{err: context.DeadlineExceeded}), "GET", "/v1/status", "", "")
	if w.Code != 504 {
		t.Fatalf("deadline status: %d", w.Code)
	}
}

const testPrivateKey = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
const testPresharedKey = "AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI="
const testPublicKey = "AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM="

func validConfig() string {
	return fmt.Sprintf("[Interface]\nPrivateKey = %s\nAddress = 10.20.0.2/32, fd00:20::2/128\nDNS = 10.20.0.1\n[Peer]\nPublicKey = %s\nPresharedKey = %s\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = [2001:db8::1]:51820\n", testPrivateKey, testPublicKey, testPresharedKey)
}

func TestWireGuardPlanNeverExposesSecretsOrClaimsApplied(t *testing.T) {
	body, _ := json.Marshal(map[string]string{"config": validConfig()})
	w := request(New(fakeProvider{}), "POST", "/v1/wireguard/validate", string(body), "application/json")
	if w.Code != 200 {
		t.Fatalf("status %d: %s", w.Code, w.Body)
	}
	for _, secret := range []string{testPrivateKey, testPresharedKey, "privateKey", "presharedKey"} {
		if strings.Contains(w.Body.String(), secret) {
			t.Fatalf("secret field leaked: %s", secret)
		}
	}
	var response struct {
		Data struct {
			Applied            bool
			KillSwitchActive   bool
			DefaultRouteIPv4   bool
			DefaultRouteIPv6   bool
			InterfaceAddresses []string
			Peers              []struct{ HasPresharedKey bool }
		}
	}
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.Data.Applied || response.Data.KillSwitchActive || !response.Data.DefaultRouteIPv4 || !response.Data.DefaultRouteIPv6 || len(response.Data.InterfaceAddresses) != 2 || !response.Data.Peers[0].HasPresharedKey {
		t.Fatalf("unexpected plan: %s", w.Body)
	}
}

func TestWireGuardJSONSchemaAndLimits(t *testing.T) {
	for _, test := range []struct {
		name, body, contentType string
		status                  int
	}{
		{"unknown", `{"config":"bad","command":"secret-input"}`, "application/json", 400},
		{"duplicate", `{"config":"bad","config":"secret-input"}`, "application/json", 400},
		{"case_variant", `{"Config":"secret-input"}`, "application/json", 400},
		{"missing", `{}`, "application/json", 400},
		{"null", `{"config":null}`, "application/json", 400},
		{"wrong_type", `{"config":1}`, "application/json", 400},
		{"array", `[]`, "application/json", 400},
		{"trailing", `{"config":"secret-input"}{}`, "application/json", 400},
		{"broken", `{"config":"secret-input`, "application/json", 400},
		{"invalid_profile", `{"config":"secret-input"}`, "application/json", 422},
		{"wrong_media", `{"config":"bad"}`, "text/plain", 415},
		{"body_limit", `{"config":"` + strings.Repeat("x", maxRequestBody) + `"}`, "application/json", 413},
		{"profile_limit", `{"config":"` + strings.Repeat("x", (64<<10)+1) + `"}`, "application/json", 413},
	} {
		t.Run(test.name, func(t *testing.T) {
			w := request(New(fakeProvider{}), "POST", "/v1/wireguard/validate", test.body, test.contentType)
			if w.Code != test.status {
				t.Fatalf("status %d: %s", w.Code, w.Body)
			}
			if strings.Contains(w.Body.String(), "secret-input") {
				t.Fatal("request data leaked into error")
			}
		})
	}
}

func TestWireGuardHooksRejected(t *testing.T) {
	body, _ := json.Marshal(map[string]string{"config": strings.Replace(validConfig(), "[Peer]", "PostUp = sensitive-command\n[Peer]", 1)})
	w := request(New(fakeProvider{}), "POST", "/v1/wireguard/validate", string(body), "application/json")
	if w.Code != 422 || strings.Contains(w.Body.String(), "sensitive-command") {
		t.Fatalf("unsafe hook handling: %d %s", w.Code, w.Body)
	}
}

func TestConcurrentRequestLimit(t *testing.T) {
	h := New(fakeProvider{}).(*Handler)
	for range cap(h.slots) {
		h.slots <- struct{}{}
	}
	w := request(h, "GET", "/v1/status", "", "")
	if w.Code != 503 || !strings.Contains(w.Body.String(), `"busy"`) {
		t.Fatalf("missing admission limit: %d %s", w.Code, w.Body)
	}
}
