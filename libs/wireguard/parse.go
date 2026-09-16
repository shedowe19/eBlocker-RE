package wireguard

import (
	"bytes"
	"crypto/ecdh"
	"encoding/base64"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"unicode/utf8"
)

type parsedPeer struct {
	plan     PeerPlan
	prefixes []netip.Prefix
	endpoint netip.Addr
	line     int
}

// Parse accepts the safe subset described in README.md. It performs no DNS
// lookup, I/O, subprocess execution, or network mutation.
func Parse(config []byte) (*Profile, error) {
	if len(config) == 0 || len(config) > MaxConfigBytes {
		return nil, invalid("config_size", 0, "", "Configuration must contain 1 to 65536 bytes.")
	}
	if !utf8.Valid(config) || bytes.IndexByte(config, 0) >= 0 {
		return nil, invalid("invalid_encoding", 0, "", "Configuration must be UTF-8 without NUL bytes.")
	}
	profile := &Profile{state: &profileState{plan: Plan{
		InterfaceAddresses: []string{}, DNS: []string{}, Peers: []PeerPlan{},
		EndpointExclusions: []string{}, RequiredCapabilities: []string{"CAP_NET_ADMIN"},
		LeakRisks: []string{}, Warnings: []string{},
	}}}
	completed := false
	defer func() {
		if !completed {
			profile.Destroy()
		}
	}()
	var peers []parsedPeer
	var addresses []netip.Prefix
	seen := map[string]bool{}
	section := ""
	interfaceSeen := false
	totalPrefixes := 0
	for index, raw := range bytes.Split(config, []byte{'\n'}) {
		lineNumber := index + 1
		line := strings.TrimSpace(string(bytes.SplitN(raw, []byte{'#'}, 2)[0]))
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") {
			switch line {
			case "[Interface]":
				if interfaceSeen || section != "" {
					return nil, invalid("section_order", lineNumber, "", "Exactly one Interface section must precede all Peer sections.")
				}
				interfaceSeen = true
				section = "Interface"
			case "[Peer]":
				if !interfaceSeen {
					return nil, invalid("section_order", lineNumber, "", "Interface must precede Peer sections.")
				}
				if len(peers) >= MaxPeers {
					return nil, invalid("peer_limit", lineNumber, "", "A maximum of 128 peers is supported.")
				}
				section = "Peer"
				peers = append(peers, parsedPeer{line: lineNumber, plan: PeerPlan{AllowedIPs: []string{}}})
				profile.state.preshared = append(profile.state.preshared, [32]byte{})
			default:
				return nil, invalid("unknown_section", lineNumber, "", "Only Interface and Peer sections are supported.")
			}
			seen = map[string]bool{}
			continue
		}
		key, value, found := strings.Cut(line, "=")
		key, value = strings.TrimSpace(key), strings.TrimSpace(value)
		if !found || section == "" {
			return nil, invalid("invalid_assignment", lineNumber, "", "Expected a field assignment inside a section.")
		}
		switch key {
		case "PreUp", "PostUp", "PreDown", "PostDown", "SaveConfig", "Table", "FwMark":
			return nil, invalid("unsupported_directive", lineNumber, key, "Hooks, automatic saving, and custom routing directives are not permitted.")
		}
		if !knownField(section, key) {
			return nil, invalid("unknown_field", lineNumber, "", "Unsupported field in this section.")
		}
		if value == "" {
			return nil, invalid("empty_value", lineNumber, key, "A value is required.")
		}
		listField := key == "Address" || key == "DNS" || key == "AllowedIPs"
		if seen[key] && !listField {
			return nil, invalid("duplicate_field", lineNumber, key, "A singleton field may only occur once per section.")
		}
		seen[key] = true
		if section == "Interface" {
			switch key {
			case "PrivateKey":
				decoded, err := parseKey(value, lineNumber, key)
				if err != nil {
					return nil, err
				}
				profile.state.private = decoded
				clear(decoded[:])
			case "Address":
				for _, part := range strings.Split(value, ",") {
					if len(addresses) >= MaxAddresses {
						return nil, invalid("address_limit", lineNumber, key, "A maximum of 64 interface addresses is supported.")
					}
					prefix, err := parsePrefix(strings.TrimSpace(part), true)
					if err != nil || !usableIP(prefix.Addr()) {
						return nil, invalid("invalid_address", lineNumber, key, "Expected a unicast IPv4 or IPv6 address with an optional CIDR mask.")
					}
					for _, previous := range addresses {
						if previous.Addr() == prefix.Addr() {
							return nil, invalid("duplicate_address", lineNumber, key, "Interface addresses must be unique.")
						}
					}
					addresses = append(addresses, prefix)
					profile.state.plan.InterfaceAddresses = append(profile.state.plan.InterfaceAddresses, prefix.String())
				}
			case "DNS":
				for _, part := range strings.Split(value, ",") {
					if len(profile.state.plan.DNS) >= MaxDNS {
						return nil, invalid("dns_limit", lineNumber, key, "A maximum of 16 DNS addresses is supported.")
					}
					ip, err := netip.ParseAddr(strings.TrimSpace(part))
					if err != nil || !usableIP(ip) {
						return nil, invalid("invalid_dns", lineNumber, key, "DNS accepts unicast IP addresses only; search domains are not supported.")
					}
					for _, previous := range profile.state.plan.DNS {
						if previous == ip.String() {
							return nil, invalid("duplicate_dns", lineNumber, key, "DNS addresses must be unique.")
						}
					}
					profile.state.plan.DNS = append(profile.state.plan.DNS, ip.String())
				}
			case "ListenPort", "MTU":
				minimum := uint64(0)
				if key == "MTU" {
					minimum = 576
				}
				number, err := parseUint16(value, minimum, lineNumber, key)
				if err != nil {
					return nil, err
				}
				if key == "ListenPort" {
					profile.state.plan.ListenPort = &number
				} else {
					profile.state.plan.MTU = &number
				}
			}
			continue
		}
		peer := &peers[len(peers)-1]
		switch key {
		case "PublicKey", "PresharedKey":
			decoded, err := parseKey(value, lineNumber, key)
			if err != nil {
				return nil, err
			}
			if key == "PublicKey" {
				peer.plan.PublicKey = base64.StdEncoding.EncodeToString(decoded[:])
			} else {
				profile.state.preshared[len(peers)-1] = decoded
				peer.plan.HasPresharedKey = true
			}
			clear(decoded[:])
		case "AllowedIPs":
			for _, part := range strings.Split(value, ",") {
				if totalPrefixes >= MaxPrefixes {
					return nil, invalid("prefix_limit", lineNumber, key, "A maximum of 1024 allowed prefixes is supported.")
				}
				prefix, err := parsePrefix(strings.TrimSpace(part), false)
				if err != nil {
					return nil, invalid("invalid_prefix", lineNumber, key, "Allowed IPs must be IPv4 or IPv6 CIDR prefixes.")
				}
				prefix = prefix.Masked()
				peer.prefixes = append(peer.prefixes, prefix)
				peer.plan.AllowedIPs = append(peer.plan.AllowedIPs, prefix.String())
				totalPrefixes++
			}
		case "Endpoint":
			endpoint, ip, err := parseEndpoint(value, lineNumber)
			if err != nil {
				return nil, err
			}
			peer.plan.Endpoint, peer.endpoint = endpoint, ip
		case "PersistentKeepalive":
			if value == "off" {
				value = "0"
			}
			number, err := parseUint16(value, 0, lineNumber, key)
			if err != nil {
				return nil, err
			}
			peer.plan.PersistentKeepalive = &number
		}
	}
	if !interfaceSeen || profile.state.private == [32]byte{} {
		return nil, invalid("missing_private_key", 0, "PrivateKey", "Exactly one Interface section with a private key is required.")
	}
	if len(addresses) == 0 {
		return nil, invalid("missing_address", 0, "Address", "At least one interface address is required.")
	}
	if len(peers) == 0 {
		return nil, invalid("missing_peer", 0, "", "At least one Peer section is required.")
	}
	if err := validatePlan(profile.state, addresses, peers); err != nil {
		return nil, err
	}
	completed = true
	return profile, nil
}

func knownField(section, key string) bool {
	if section == "Interface" {
		switch key {
		case "PrivateKey", "Address", "DNS", "ListenPort", "MTU":
			return true
		}
	} else {
		switch key {
		case "PublicKey", "PresharedKey", "AllowedIPs", "Endpoint", "PersistentKeepalive":
			return true
		}
	}
	return false
}

func parseKey(value string, line int, field string) ([32]byte, error) {
	var key [32]byte
	if len(value) != 44 {
		return key, invalid("invalid_key", line, field, "Keys must be canonical base64 encoding of exactly 32 bytes and must not be all zero.")
	}
	decoded, err := base64.StdEncoding.Strict().DecodeString(value)
	defer clear(decoded)
	if err != nil || len(decoded) != 32 || base64.StdEncoding.EncodeToString(decoded) != value {
		return key, invalid("invalid_key", line, field, "Keys must be canonical base64 encoding of exactly 32 bytes and must not be all zero.")
	}
	copy(key[:], decoded)
	if key == [32]byte{} {
		return key, invalid("invalid_key", line, field, "All-zero keys are not permitted.")
	}
	if field == "PublicKey" {
		// NewPublicKey only checks length for X25519. ECDH also rejects low-order
		// points that would produce an all-zero shared secret (RFC 7748).
		probe := [32]byte{9}
		private, _ := ecdh.X25519().NewPrivateKey(probe[:])
		public, _ := ecdh.X25519().NewPublicKey(key[:])
		shared, err := private.ECDH(public)
		clear(shared)
		if err != nil {
			clear(key[:])
			return key, invalid("invalid_key", line, field, "Peer public key is not a usable X25519 key.")
		}
	}
	return key, nil
}

func usableIP(ip netip.Addr) bool {
	return ip.IsValid() && !ip.Is4In6() && ip.Zone() == "" && !ip.IsUnspecified() && !ip.IsMulticast() && ip != netip.MustParseAddr("255.255.255.255")
}

func parsePrefix(value string, allowAddress bool) (netip.Prefix, error) {
	prefix, err := netip.ParsePrefix(value)
	if err != nil && allowAddress {
		if ip, ipErr := netip.ParseAddr(value); ipErr == nil {
			prefix, err = netip.PrefixFrom(ip, ip.BitLen()), nil
		}
	}
	if err != nil || !prefix.IsValid() || prefix.Addr().Is4In6() || prefix.Addr().Zone() != "" {
		return netip.Prefix{}, invalid("invalid_prefix", 0, "", "Expected an unscoped IPv4 or IPv6 prefix.")
	}
	return prefix, nil
}

func parseUint16(value string, minimum uint64, line int, field string) (uint16, error) {
	for _, c := range value {
		if c < '0' || c > '9' {
			return 0, invalid("invalid_number", line, field, "Expected an unsigned decimal number in the supported range.")
		}
	}
	number, err := strconv.ParseUint(value, 10, 16)
	if err != nil || number < minimum {
		return 0, invalid("invalid_number", line, field, "Expected an unsigned decimal number in the supported range.")
	}
	return uint16(number), nil
}

func parseEndpoint(value string, line int) (string, netip.Addr, error) {
	fail := func() (string, netip.Addr, error) {
		return "", netip.Addr{}, invalid("invalid_endpoint", line, "Endpoint", "Expected a DNS hostname, IPv4 address, or bracketed IPv6 address with a port from 1 to 65535.")
	}
	host, port, err := net.SplitHostPort(value)
	if err != nil {
		return fail()
	}
	number, err := parseUint16(port, 1, line, "Endpoint")
	if err != nil {
		return fail()
	}
	ip, err := netip.ParseAddr(host)
	if err == nil {
		if !usableIP(ip) || ip.IsLoopback() || (ip.Is4() && strings.HasPrefix(value, "[")) {
			return fail()
		}
		return net.JoinHostPort(ip.String(), strconv.Itoa(int(number))), ip, nil
	}
	// ASCII DNS only: no URI syntax, IDN ambiguity, shell metacharacters, or
	// single numeric/address-like names that failed strict IP parsing.
	if strings.HasPrefix(value, "[") || !validHostname(host) {
		return fail()
	}
	return net.JoinHostPort(strings.ToLower(strings.TrimSuffix(host, ".")), strconv.Itoa(int(number))), netip.Addr{}, nil
}

func validHostname(host string) bool {
	host = strings.TrimSuffix(host, ".")
	if len(host) == 0 || len(host) > 253 {
		return false
	}
	allNumeric := true
	for _, label := range strings.Split(host, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return false
		}
		for _, c := range label {
			if c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' {
				allNumeric = false
				continue
			}
			if c >= '0' && c <= '9' {
				continue
			}
			if c == '-' {
				allNumeric = false
				continue
			}
			return false
		}
	}
	return !allNumeric
}
