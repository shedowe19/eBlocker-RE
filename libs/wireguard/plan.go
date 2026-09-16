package wireguard

import (
	"crypto/ecdh"
	"crypto/subtle"
	"encoding/base64"
	"net/netip"
	"slices"
)

func validatePlan(state *profileState, addresses []netip.Prefix, peers []parsedPeer) error {
	private, _ := ecdh.X25519().NewPrivateKey(state.private[:])
	public := base64.StdEncoding.EncodeToString(private.PublicKey().Bytes())
	keys := map[string]bool{}
	var prefixes []netip.Prefix
	for _, peer := range peers {
		if peer.plan.PublicKey == "" {
			return invalid("missing_public_key", peer.line, "PublicKey", "Each peer requires a public key.")
		}
		if peer.plan.PublicKey == public {
			return invalid("self_peer", peer.line, "PublicKey", "A peer must not use the interface's own public key.")
		}
		// Prevent accidental private/preshared material from being published in
		// the supposedly public DTO when it was pasted into a PublicKey field.
		decoded, _ := base64.StdEncoding.DecodeString(peer.plan.PublicKey)
		secretInPublicField := subtle.ConstantTimeCompare(decoded, state.private[:])
		for _, preshared := range state.preshared {
			secretInPublicField |= subtle.ConstantTimeCompare(decoded, preshared[:])
		}
		clear(decoded)
		if secretInPublicField == 1 {
			return invalid("secret_in_public_key", peer.line, "PublicKey", "Public-key fields must not repeat private or preshared key material.")
		}
		if keys[peer.plan.PublicKey] {
			return invalid("duplicate_peer", peer.line, "PublicKey", "Peer public keys must be unique.")
		}
		keys[peer.plan.PublicKey] = true
		if len(peer.prefixes) == 0 {
			return invalid("missing_allowed_ips", peer.line, "AllowedIPs", "Each peer requires at least one allowed prefix.")
		}
		for _, prefix := range peer.prefixes {
			if state.plan.MTU != nil && *state.plan.MTU < 1280 && prefix.Addr().Is6() {
				return invalid("ipv6_mtu", 0, "MTU", "IPv6 tunnel traffic requires an MTU of at least 1280.")
			}
			for _, previous := range prefixes {
				if prefix.Overlaps(previous) {
					return invalid("overlapping_allowed_ips", peer.line, "AllowedIPs", "Allowed prefixes must not overlap within or between peers.")
				}
			}
			prefixes = append(prefixes, prefix)
		}
		for _, address := range addresses {
			if state.plan.MTU != nil && *state.plan.MTU < 1280 && address.Addr().Is6() {
				return invalid("ipv6_mtu", 0, "MTU", "IPv6 interface addresses require an MTU of at least 1280.")
			}
			if peer.endpoint.IsValid() && peer.endpoint == address.Addr() {
				return invalid("endpoint_loop", peer.line, "Endpoint", "A peer endpoint must not be an address assigned to this interface.")
			}
		}
		state.plan.Peers = append(state.plan.Peers, peer.plan)
	}
	state.plan.DefaultRouteIPv4 = coversFamily(prefixes, true)
	state.plan.DefaultRouteIPv6 = coversFamily(prefixes, false)
	state.plan.LeakRisks = append(state.plan.LeakRisks, "no_kill_switch")
	if !state.plan.DefaultRouteIPv4 {
		state.plan.LeakRisks = append(state.plan.LeakRisks, "ipv4_not_fully_tunneled")
	}
	if !state.plan.DefaultRouteIPv6 {
		state.plan.LeakRisks = append(state.plan.LeakRisks, "ipv6_not_fully_tunneled")
	}
	if len(state.plan.DNS) == 0 {
		state.plan.LeakRisks = append(state.plan.LeakRisks, "dns_not_configured")
	} else {
		for _, server := range state.plan.DNS {
			if !containsAddress(prefixes, netip.MustParseAddr(server)) {
				state.plan.LeakRisks = append(state.plan.LeakRisks, "dns_outside_tunnel")
				break
			}
		}
	}
	state.plan.Warnings = append(state.plan.Warnings, "Validation only: no tunnel, routes, DNS settings, or kill switch have been applied.")
	for _, peer := range peers {
		if peer.plan.Endpoint == "" {
			appendWarning(&state.plan, "A peer has no configured endpoint and requires an authenticated incoming handshake before outbound traffic can use it.")
		} else if !peer.endpoint.IsValid() {
			appendWarning(&state.plan, "Hostname endpoints require DNS resolution, validation, and bypass-route planning before any routes are applied.")
		} else {
			needsBypass := containsAddress(prefixes, peer.endpoint)
			for _, address := range addresses {
				needsBypass = needsBypass || address.Contains(peer.endpoint)
			}
			if needsBypass && !slices.Contains(state.plan.EndpointExclusions, peer.endpoint.String()) {
				state.plan.EndpointExclusions = append(state.plan.EndpointExclusions, peer.endpoint.String())
				appendWarning(&state.plan, "Literal endpoints covered by tunnel or connected routes require verified bypass routes through the original uplink to prevent routing loops.")
			}
		}
	}
	return nil
}

func appendWarning(plan *Plan, message string) {
	if !slices.Contains(plan.Warnings, message) {
		plan.Warnings = append(plan.Warnings, message)
	}
}

func containsAddress(prefixes []netip.Prefix, ip netip.Addr) bool {
	for _, prefix := range prefixes {
		if prefix.Contains(ip) {
			return true
		}
	}
	return false
}

// coversFamily recognizes both /0 and equivalent nonoverlapping split routes,
// for example 0.0.0.0/1 plus 128.0.0.0/1. Overlaps were rejected beforehand.
func coversFamily(prefixes []netip.Prefix, ipv4 bool) bool {
	var family []netip.Prefix
	for _, prefix := range prefixes {
		if prefix.Addr().Is4() == ipv4 {
			family = append(family, prefix)
		}
	}
	slices.SortFunc(family, func(a, b netip.Prefix) int { return a.Addr().Compare(b.Addr()) })
	next := netip.IPv6Unspecified()
	if ipv4 {
		next = netip.IPv4Unspecified()
	}
	for _, prefix := range family {
		if prefix.Addr() != next {
			return false
		}
		next = lastAddress(prefix).Next()
		if !next.IsValid() {
			return true
		}
	}
	return false
}

func lastAddress(prefix netip.Prefix) netip.Addr {
	if prefix.Addr().Is4() {
		bytes := prefix.Addr().As4()
		for bit := prefix.Bits(); bit < 32; bit++ {
			bytes[bit/8] |= 1 << (7 - bit%8)
		}
		return netip.AddrFrom4(bytes)
	}
	bytes := prefix.Addr().As16()
	for bit := prefix.Bits(); bit < 128; bit++ {
		bytes[bit/8] |= 1 << (7 - bit%8)
	}
	return netip.AddrFrom16(bytes)
}
