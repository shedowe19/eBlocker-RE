package api

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/apps/network-agent/internal/network"
)

// contractProvider supplies deterministic kernel observations only to tests.
// Fixtures are produced by the real HTTP handler and WireGuard parser, and are
// also consumed by the Java bridge (and the console) from the repository root.
type contractProvider struct{}

func (contractProvider) Interfaces(context.Context) ([]network.Interface, error) {
	return []network.Interface{
		{Index: 2, Name: "lan0", MTU: 1500, HardwareAddress: "02:00:00:00:00:02", Up: true, Running: true, Multicast: true,
			Addresses: []network.Address{
				{Prefix: "192.0.2.2/24", Family: "ipv4", Scope: "global"},
				{Prefix: "fe80::2/64", Family: "ipv6", Scope: "link"},
			}},
		{Index: 3, Name: "unassigned0", MTU: 1500, Addresses: []network.Address{}},
	}, nil
}

func (contractProvider) Routes(context.Context) ([]network.Route, error) {
	return []network.Route{
		// A blackhole route has no output interface, gateway, or next hops.
		{Family: "ipv4", Destination: "198.51.100.0/24", Table: 100, Priority: 42, Protocol: 4, Type: 6},
		{Family: "ipv6", Destination: "::/0", Source: "2001:db8:1::/64", PreferredSource: "2001:db8:1::2",
			Table: 1001, Priority: 4294967295, Protocol: 4, Type: 1,
			NextHops: []network.NextHop{
				{InterfaceIndex: 2, Gateway: "fe80::1", Weight: 1},
				{InterfaceIndex: 3, Gateway: "2001:db8::1", Weight: 256, Flags: 4},
			}},
	}, nil
}

func (contractProvider) WireGuard(context.Context) (network.WireGuardCapability, error) {
	return network.WireGuardCapability{KernelFamilyRegistered: true, State: "registered", Management: false, Reason: "Deterministic test observation; no kernel access"}, nil
}

func TestSharedProtocolGoldenFixtures(t *testing.T) {
	// These deliberately recognizable keys are test-only, never appliance keys.
	secondPeerPublicKey := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte{4}, 32))
	config := strings.Replace(validConfig(), "AllowedIPs = 0.0.0.0/0, ::/0", "AllowedIPs = 0.0.0.0/0", 1)
	config = strings.Replace(config, "[Peer]", "ListenPort = 51820\nMTU = 1420\n[Peer]", 1)
	config = strings.Replace(config, "[2001:db8::1]:51820", "198.51.100.1:51820\nPersistentKeepalive = 25", 1)
	config += "[Peer]\nPublicKey = " + secondPeerPublicKey + "\nAllowedIPs = ::/0\n"
	encoded, err := json.Marshal(map[string]string{"config": config})
	if err != nil {
		t.Fatal(err)
	}
	cases := []struct{ name, method, path, body string }{
		{"status", http.MethodGet, "/v1/status", ""},
		{"wireguard-plan", http.MethodPost, "/v1/wireguard/validate", string(encoded)},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			response := request(New(contractProvider{}), test.method, test.path, test.body, "application/json")
			if response.Code != http.StatusOK {
				t.Fatalf("handler returned %d: %s", response.Code, response.Body)
			}
			if response.Header().Get("Content-Length") != strconv.Itoa(response.Body.Len()) {
				t.Fatal("response framing drift")
			}
			for _, secret := range []string{testPrivateKey, testPresharedKey, `"privateKey"`, `"presharedKey"`} {
				if strings.Contains(response.Body.String(), secret) {
					t.Fatal("a fixture must not contain secret material")
				}
			}
			var formatted bytes.Buffer
			if err := json.Indent(&formatted, bytes.TrimSpace(response.Body.Bytes()), "", "  "); err != nil {
				t.Fatal(err)
			}
			formatted.WriteByte('\n')
			fixture := filepath.Join("..", "..", "..", "..", "contracts", "network-agent", "v1", test.name+".json")
			if os.Getenv("UPDATE_NETWORK_AGENT_CONTRACTS") == "1" {
				if err := os.MkdirAll(filepath.Dir(fixture), 0755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(fixture, formatted.Bytes(), 0644); err != nil {
					t.Fatal(err)
				}
			}
			want, err := os.ReadFile(fixture)
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(want, formatted.Bytes()) {
				t.Fatalf("shared %s contract changed; inspect and regenerate explicitly, then run Java/console consumers", test.name)
			}
		})
	}
}
