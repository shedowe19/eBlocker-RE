package wireguard

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/netip"
	"slices"
	"strings"
	"sync"
	"testing"
)

// Public test-only material. None of these keys may be used on a real device.
var (
	testPrivate = testKey(17)
	testPSK     = testKey(41)
	testPublic  = testPublicKey(83)
	testPublic2 = testPublicKey(101)
)

func testKey(fill byte) string {
	var key [32]byte
	for i := range key {
		key[i] = fill
	}
	return base64.StdEncoding.EncodeToString(key[:])
}

func testPublicKey(fill byte) string {
	decoded, _ := base64.StdEncoding.DecodeString(testKey(fill))
	key, _ := ecdh.X25519().NewPrivateKey(decoded)
	return base64.StdEncoding.EncodeToString(key.PublicKey().Bytes())
}

func config(allowed, endpoint string) string {
	return "[Interface]\nPrivateKey = " + testPrivate + "\nAddress = 10.8.0.2/24, fd42::2/64\nDNS = 10.8.0.1, fd42::1\nListenPort = 51820\nMTU = 1420\n[Peer]\nPublicKey = " + testPublic + "\nPresharedKey = " + testPSK + "\nAllowedIPs = " + allowed + "\nEndpoint = " + endpoint + "\nPersistentKeepalive = 25\n"
}

func mustParse(t *testing.T, value string) *Profile {
	t.Helper()
	profile, err := Parse([]byte(value))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	t.Cleanup(profile.Destroy)
	return profile
}

func TestDualStackPlan(t *testing.T) {
	profile := mustParse(t, config("0.0.0.0/0, ::/0", "[2001:db8::5]:51820"))
	plan := profile.Plan()
	if !plan.DefaultRouteIPv4 || !plan.DefaultRouteIPv6 {
		t.Fatal("expected two default routes")
	}
	if plan.Applied || plan.KillSwitchActive {
		t.Fatal("a validation plan must never claim application or protection")
	}
	if !slices.Equal(plan.InterfaceAddresses, []string{"10.8.0.2/24", "fd42::2/64"}) {
		t.Fatal("interface host bits lost")
	}
	if !slices.Equal(plan.EndpointExclusions, []string{"2001:db8::5"}) {
		t.Fatal("endpoint bypass route missing")
	}
	if !slices.Equal(plan.LeakRisks, []string{"no_kill_switch"}) {
		t.Fatalf("unexpected risks: %v", plan.LeakRisks)
	}
	if !slices.Equal(plan.RequiredCapabilities, []string{"CAP_NET_ADMIN"}) {
		t.Fatal("future apply capability missing")
	}
	peer := plan.Peers[0]
	if !peer.HasPresharedKey || peer.PublicKey != testPublic || *peer.PersistentKeepalive != 25 {
		t.Fatal("peer values missing")
	}
	if *plan.ListenPort != 51820 || *plan.MTU != 1420 {
		t.Fatal("interface values missing")
	}
}

func TestSplitDefaultRoutesAndCanonicalization(t *testing.T) {
	plan := mustParse(t, config("128.0.0.7/1, 0.0.0.7/1, 8000::1/1, ::1/1", "VPN.Example.org.:51820")).Plan()
	if !plan.DefaultRouteIPv4 || !plan.DefaultRouteIPv6 {
		t.Fatal("split defaults not recognized")
	}
	if plan.Peers[0].Endpoint != "vpn.example.org:51820" {
		t.Fatal("endpoint not normalized")
	}
	if !slices.Equal(plan.Peers[0].AllowedIPs, []string{"128.0.0.0/1", "0.0.0.0/1", "8000::/1", "::/1"}) {
		t.Fatal("prefixes not canonical")
	}
	if len(plan.EndpointExclusions) != 0 {
		t.Fatal("hostname must not be resolved during parsing")
	}
	if len(plan.Warnings) != 2 {
		t.Fatal("unresolved endpoint warning missing")
	}
}

func TestSplitTunnelRisks(t *testing.T) {
	plan := mustParse(t, config("10.8.0.0/24", "198.51.100.8:51820")).Plan()
	if plan.DefaultRouteIPv4 || plan.DefaultRouteIPv6 {
		t.Fatal("split tunnel misclassified")
	}
	for _, expected := range []string{"no_kill_switch", "ipv4_not_fully_tunneled", "ipv6_not_fully_tunneled", "dns_outside_tunnel"} {
		if !slices.Contains(plan.LeakRisks, expected) {
			t.Errorf("missing risk %s", expected)
		}
	}
	if len(plan.EndpointExclusions) != 0 {
		t.Fatal("outside endpoint must not receive bypass requirement")
	}
}

func TestListsCanRepeatAndUnnumberedAddressesGetHostMasks(t *testing.T) {
	value := "# comment\r\n[Interface]\r\nPrivateKey=" + testPrivate + " # ignored\r\nAddress=10.0.0.1\r\nAddress=fd42::1\r\nDNS=1.1.1.1\r\nDNS=2606:4700:4700::1111\r\nListenPort=0\r\n[Peer]\r\nPublicKey=" + testPublic + "\r\nAllowedIPs=10.10.0.0/16\r\nAllowedIPs=fd43::/64\r\nPersistentKeepalive=off\r\n"
	plan := mustParse(t, value).Plan()
	if !slices.Equal(plan.InterfaceAddresses, []string{"10.0.0.1/32", "fd42::1/128"}) {
		t.Fatal("host masks missing")
	}
	if *plan.ListenPort != 0 || *plan.Peers[0].PersistentKeepalive != 0 {
		t.Fatal("off/auto values not preserved")
	}
	if len(plan.Warnings) < 2 {
		t.Fatal("incoming handshake requirement missing")
	}
}

func TestMultiplePeers(t *testing.T) {
	value := config("10.10.0.0/16", "198.51.100.8:51820") + "[Peer]\nPublicKey=" + testPublic2 + "\nAllowedIPs=fd99::/64\nEndpoint=[2001:db8::8]:51820\n"
	if len(mustParse(t, value).Plan().Peers) != 2 {
		t.Fatal("second peer missing")
	}
}

func TestRejectInvalidConfigurations(t *testing.T) {
	base := config("10.10.0.0/16, fd42::/64", "198.51.100.8:51820")
	cases := []struct{ name, input, code string }{
		{"empty", "", "config_size"},
		{"oversize", strings.Repeat(" ", MaxConfigBytes+1), "config_size"},
		{"nul", base + "\x00", "invalid_encoding"},
		{"invalid_utf8", base + "\xff", "invalid_encoding"},
		{"missing_interface", "[Peer]\n", "section_order"},
		{"unknown_section", "[SecretSection]\n", "unknown_section"},
		{"duplicate_interface", base + "[Interface]\n", "section_order"},
		{"unknown_field", base + "SecretUnknownName=SecretValue\n", "unknown_field"},
		{"wrong_section", base + "PrivateKey=" + testPrivate, "unknown_field"},
		{"bad_assignment", base + "invalid", "invalid_assignment"},
		{"empty_value", strings.Replace(base, "MTU = 1420", "MTU=", 1), "empty_value"},
		{"duplicate_key", strings.Replace(base, "[Peer]", "PrivateKey="+testPrivate+"\n[Peer]", 1), "duplicate_field"},
		{"missing_private", strings.Replace(base, "PrivateKey = "+testPrivate+"\n", "", 1), "missing_private_key"},
		{"key_file", strings.Replace(base, testPrivate, "/etc/wireguard/private.key", 1), "invalid_key"},
		{"zero_key", strings.Replace(base, testPrivate, testKey(0), 1), "invalid_key"},
		{"short_key", strings.Replace(base, testPrivate, "c2hvcnQ=", 1), "invalid_key"},
		{"bad_base64", strings.Replace(base, testPrivate, strings.Repeat("!", 44), 1), "invalid_key"},
		{"missing_address", strings.Replace(base, "Address = 10.8.0.2/24, fd42::2/64\n", "", 1), "missing_address"},
		{"invalid_address", strings.Replace(base, "10.8.0.2/24", "0.0.0.0/24", 1), "invalid_address"},
		{"duplicate_address", strings.Replace(base, "10.8.0.2/24", "10.8.0.2/24, 10.8.0.2/32", 1), "duplicate_address"},
		{"missing_peer", strings.Split(base, "[Peer]")[0], "missing_peer"},
		{"missing_public", strings.Replace(base, "PublicKey = "+testPublic+"\n", "", 1), "missing_public_key"},
		{"self_peer", strings.Replace(base, testPublic, testPublicKey(17), 1), "self_peer"},
		{"missing_routes", strings.Replace(base, "AllowedIPs = 10.10.0.0/16, fd42::/64\n", "", 1), "missing_allowed_ips"},
		{"bad_cidr", strings.Replace(base, "10.10.0.0/16", "10.10.0.0/33", 1), "invalid_prefix"},
		{"bare_route", strings.Replace(base, "10.10.0.0/16", "10.10.0.1", 1), "invalid_prefix"},
		{"mapped_route", strings.Replace(base, "10.10.0.0/16", "::ffff:192.0.2.1/128", 1), "invalid_prefix"},
		{"overlap_same_peer", strings.Replace(base, "10.10.0.0/16", "10.10.0.0/16, 10.10.1.0/24", 1), "overlapping_allowed_ips"},
		{"overlap_other_peer", base + "[Peer]\nPublicKey=" + testPublic2 + "\nAllowedIPs=10.10.0.0/24", "overlapping_allowed_ips"},
		{"duplicate_peer", base + "[Peer]\nPublicKey=" + testPublic + "\nAllowedIPs=10.11.0.0/24", "duplicate_peer"},
		{"dns_domain", strings.Replace(base, "DNS = 10.8.0.1, fd42::1", "DNS=example.org", 1), "invalid_dns"},
		{"dns_multicast", strings.Replace(base, "DNS = 10.8.0.1, fd42::1", "DNS=224.0.0.1", 1), "invalid_dns"},
		{"dns_duplicate", strings.Replace(base, "DNS = 10.8.0.1, fd42::1", "DNS=10.8.0.1, 10.8.0.1", 1), "duplicate_dns"},
		{"invalid_port", strings.Replace(base, "ListenPort = 51820", "ListenPort=65536", 1), "invalid_number"},
		{"signed_port", strings.Replace(base, "ListenPort = 51820", "ListenPort=+1", 1), "invalid_number"},
		{"mtu_too_small", strings.Replace(base, "MTU = 1420", "MTU=500", 1), "invalid_number"},
		{"ipv6_mtu", strings.Replace(base, "MTU = 1420", "MTU=1279", 1), "ipv6_mtu"},
		{"keepalive_too_large", strings.Replace(base, "PersistentKeepalive = 25", "PersistentKeepalive=65536", 1), "invalid_number"},
		{"endpoint_self", strings.Replace(base, "198.51.100.8:51820", "10.8.0.2:51820", 1), "endpoint_loop"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			profile, err := Parse([]byte(test.input))
			if profile != nil || err == nil {
				t.Fatal("expected rejected configuration")
			}
			var validation *ValidationError
			if !errors.As(err, &validation) || validation.Code != test.code {
				t.Fatalf("expected %s, got %v", test.code, err)
			}
		})
	}
}

func TestRejectHooksAndCustomRouting(t *testing.T) {
	for _, key := range []string{"PreUp", "PostUp", "PreDown", "PostDown", "SaveConfig", "Table", "FwMark"} {
		t.Run(key, func(t *testing.T) {
			input := strings.Replace(config("0.0.0.0/0", "vpn.example.org:51820"), "[Peer]", key+"= touch /tmp/MUST_NEVER_EXECUTE; $(id)\n[Peer]", 1)
			_, err := Parse([]byte(input))
			var validation *ValidationError
			if !errors.As(err, &validation) || validation.Code != "unsupported_directive" {
				t.Fatalf("hook accepted: %v", err)
			}
			if strings.Contains(err.Error(), "MUST_NEVER_EXECUTE") {
				t.Fatal("hook content leaked")
			}
		})
	}
}

func TestRejectLowOrderPublicKey(t *testing.T) {
	lowOrder := [32]byte{1}
	input := strings.Replace(config("0.0.0.0/0", "vpn.example.org:51820"), testPublic, base64.StdEncoding.EncodeToString(lowOrder[:]), 1)
	_, err := Parse([]byte(input))
	var validation *ValidationError
	if !errors.As(err, &validation) || validation.Code != "invalid_key" {
		t.Fatalf("low-order public key accepted: %v", err)
	}
}

func TestConnectedRouteRequiresEndpointBypass(t *testing.T) {
	plan := mustParse(t, config("10.10.0.0/16", "10.8.0.1:51820")).Plan()
	if !slices.Equal(plan.EndpointExclusions, []string{"10.8.0.1"}) {
		t.Fatal("connected interface route would capture peer endpoint")
	}
}

func TestDNSOmissionIsVisible(t *testing.T) {
	input := strings.Replace(config("0.0.0.0/0, ::/0", "vpn.example.org:51820"), "DNS = 10.8.0.1, fd42::1\n", "", 1)
	if !slices.Contains(mustParse(t, input).Plan().LeakRisks, "dns_not_configured") {
		t.Fatal("missing DNS risk omitted")
	}
}

func TestZeroProfileCanBeDestroyedAndPrinted(t *testing.T) {
	var profile Profile
	profile.Destroy()
	if len(profile.Plan().Peers) != 0 {
		t.Fatal("zero profile must be empty")
	}
	if fmt.Sprintf("%#v", profile) != "wireguard.Profile[REDACTED]" {
		t.Fatal("profile formatter not redacted")
	}
}

func TestRejectMalformedEndpoints(t *testing.T) {
	for _, endpoint := range []string{"", "https://vpn.example.org:51820", "vpn.example.org", "vpn.example.org:0", "vpn.example.org:65536", "vpn.example.org:+1", "vpn.example.org:53/udp", "2001:db8::1:51820", "[fe80::1%eth0]:51820", "[example.org]:51820", "[192.0.2.1]:51820", "127.0.0.1:51820", "[::1]:51820", "[::ffff:192.0.2.1]:51820", "0.0.0.0:51820", "224.0.0.1:51820", "999.999.999.999:51820", "-vpn.example.org:51820", "vpn..example.org:51820", "vpn_example.org:51820", "$(id).example.org:51820", "bücher.example.org:51820", strings.Repeat("a", 64) + ".org:51820"} {
		t.Run(endpoint, func(t *testing.T) {
			profile, err := Parse([]byte(config("0.0.0.0/0", endpoint)))
			if err == nil || profile != nil {
				t.Fatal("invalid endpoint accepted")
			}
		})
	}
}

func TestLimits(t *testing.T) {
	base := config("10.10.0.0/16", "198.51.100.8:51820")
	cases := []struct{ name, input, code string }{
		{"peers", base + strings.Repeat("[Peer]\n", MaxPeers), "peer_limit"},
		{"prefixes", strings.Replace(base, "10.10.0.0/16", strings.Repeat("1.1.1.1/32,", MaxPrefixes)+"1.1.1.1/32", 1), "prefix_limit"},
	}
	for _, test := range cases {
		t.Run(test.name, func(t *testing.T) {
			_, err := Parse([]byte(test.input))
			var validation *ValidationError
			if !errors.As(err, &validation) || validation.Code != test.code {
				t.Fatalf("expected %s: %v", test.code, err)
			}
		})
	}
	addresses := make([]string, MaxAddresses+1)
	for i := range addresses {
		addresses[i] = fmt.Sprintf("10.20.0.%d/32", i+1)
	}
	_, err := Parse([]byte(strings.Replace(base, "10.8.0.2/24, fd42::2/64", strings.Join(addresses, ","), 1)))
	var validation *ValidationError
	if !errors.As(err, &validation) || validation.Code != "address_limit" {
		t.Fatalf("address limit: %v", err)
	}
	dns := make([]string, MaxDNS+1)
	for i := range dns {
		dns[i] = fmt.Sprintf("10.20.0.%d", i+1)
	}
	_, err = Parse([]byte(strings.Replace(base, "DNS = 10.8.0.1, fd42::1", "DNS="+strings.Join(dns, ","), 1)))
	if !errors.As(err, &validation) || validation.Code != "dns_limit" {
		t.Fatalf("DNS limit: %v", err)
	}
}

func TestSecretsNeverAppearInFormattingJSONOrErrors(t *testing.T) {
	profile := mustParse(t, config("0.0.0.0/0", "vpn.example.org:51820"))
	for _, value := range []any{profile, *profile, profile.Plan()} {
		for _, format := range []string{"%v", "%+v", "%#v", "%s", "%q", "%x"} {
			assertNoSecrets(t, fmt.Sprintf(format, value))
		}
		data, err := json.Marshal(value)
		if err != nil {
			t.Fatal(err)
		}
		assertNoSecrets(t, string(data))
	}
	for _, input := range []string{"[" + testPrivate + "]", config("0.0.0.0/0", "vpn.example.org:51820") + testPSK + "=" + testPrivate, strings.Replace(config("0.0.0.0/0", "vpn.example.org:51820"), testPrivate, testPrivate+"PRIVATE_CANARY", 1)} {
		_, err := Parse([]byte(input))
		if err == nil {
			t.Fatal("expected validation failure")
		}
		data, _ := json.Marshal(err)
		assertNoSecrets(t, err.Error()+string(data))
		if strings.Contains(string(data), "PRIVATE_CANARY") {
			t.Fatal("invalid input echoed")
		}
	}
}

func TestSecretAccidentallyPastedAsPublicKeyIsNotPublished(t *testing.T) {
	for _, secret := range []string{testPrivate, testPSK} {
		input := strings.Replace(config("0.0.0.0/0", "vpn.example.org:51820"), testPublic, secret, 1)
		profile, err := Parse([]byte(input))
		var validation *ValidationError
		if profile != nil || !errors.As(err, &validation) || validation.Code != "secret_in_public_key" {
			t.Fatalf("secret in public field was not rejected: %v", err)
		}
		assertNoSecrets(t, err.Error())
	}
}

func assertNoSecrets(t *testing.T, text string) {
	t.Helper()
	for _, secret := range []string{testPrivate, testPSK} {
		if strings.Contains(text, secret) {
			t.Fatal("secret leaked")
		}
	}
}

func TestPlanDeepCopyAndDestroyConcurrent(t *testing.T) {
	profile := mustParse(t, config("0.0.0.0/0", "198.51.100.8:51820"))
	plan := profile.Plan()
	plan.InterfaceAddresses[0] = "mutated"
	plan.DNS[0] = "mutated"
	plan.Peers[0].AllowedIPs[0] = "mutated"
	*plan.Peers[0].PersistentKeepalive = 999
	*plan.ListenPort = 999
	*plan.MTU = 999
	plan.EndpointExclusions[0] = "mutated"
	plan.LeakRisks[0] = "mutated"
	plan.RequiredCapabilities[0] = "mutated"
	plan.Warnings[0] = "mutated"
	data, _ := json.Marshal(profile.Plan())
	if strings.Contains(string(data), "mutated") || strings.Contains(string(data), "999") {
		t.Fatal("caller changed the stored plan")
	}
	var group sync.WaitGroup
	for i := 0; i < 16; i++ {
		group.Go(func() {
			for j := 0; j < 10; j++ {
				profile.Destroy()
				_ = profile.Plan()
				_ = fmt.Sprintf("%#v", profile)
			}
		})
	}
	group.Wait()
	if profile.state.private != [32]byte{} || profile.state.preshared[0] != [32]byte{} {
		t.Fatal("retained keys were not cleared")
	}
}

func TestFamilyCoverageBoundaries(t *testing.T) {
	cases := []struct {
		prefixes       []string
		ipv4, expected bool
	}{
		{[]string{"0.0.0.0/0"}, true, true},
		{[]string{"0.0.0.0/1", "128.0.0.0/1"}, true, true},
		{[]string{"0.0.0.0/1", "128.0.0.0/2"}, true, false},
		{[]string{"0.0.0.1/32", "128.0.0.0/1"}, true, false},
		{[]string{"::/0"}, false, true},
		{[]string{"::/1", "8000::/1"}, false, true},
		{[]string{"::/2", "8000::/1"}, false, false},
		{[]string{"::/0"}, true, false},
		{nil, false, false},
	}
	for _, test := range cases {
		var prefixes []netip.Prefix
		for _, value := range test.prefixes {
			prefixes = append(prefixes, netip.MustParsePrefix(value))
		}
		if got := coversFamily(prefixes, test.ipv4); got != test.expected {
			t.Errorf("%v: got %v", test.prefixes, got)
		}
	}
}

func FuzzParse(f *testing.F) {
	for _, value := range []string{"", "[Interface]", config("0.0.0.0/0, ::/0", "[2001:db8::1]:51820"), config("10.0.0.0/8", "vpn.example.org:51820"), "[Interface]\nPostUp=echo never\n", "\x00\xff"} {
		f.Add([]byte(value))
	}
	f.Fuzz(func(t *testing.T, input []byte) {
		profile, err := Parse(input)
		if err != nil {
			if profile != nil {
				t.Fatal("failed Parse returned a profile")
			}
			var validation *ValidationError
			if !errors.As(err, &validation) {
				t.Fatal("untyped parse error")
			}
			return
		}
		defer profile.Destroy()
		plan := profile.Plan()
		if plan.Applied || plan.KillSwitchActive || len(plan.Peers) == 0 || len(plan.Peers) > MaxPeers {
			t.Fatal("invalid plan invariant")
		}
		data, err := json.Marshal(profile)
		if err != nil || string(data) != `{"redacted":true}` {
			t.Fatal("profile serialization must be redacted")
		}
		if _, err := json.Marshal(plan); err != nil {
			t.Fatal("plan must be serializable")
		}
	})
}
