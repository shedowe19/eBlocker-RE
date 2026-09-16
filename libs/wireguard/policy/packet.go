package policy

import (
	"net/netip"
	"slices"
)

// Packet is the semantic firewall contract used by deterministic tests. It is
// neither a packet capture nor a substitute for comparing real nft expressions.
type Packet struct {
	Hook            string
	Source          netip.Addr
	Destination     netip.Addr
	InputInterface  string
	OutputInterface string
	Protocol        string
	SourcePort      uint16
	DestinationPort uint16
	Mark            uint32
	ICMPv6Type      uint8
	HopLimit        uint8
}

type Decision struct {
	Accept bool
	Reason string
}

// Evaluate documents the required output/forward rule order. No generic mark,
// conntrack-established, or unprotected-family exception exists. Kernel adapters
// must implement this contract and attest their actual installed expressions.
func (p Plan) Evaluate(packet Packet) Decision {
	drop := func(reason string) Decision { return Decision{Reason: reason} }
	accept := func(reason string) Decision { return Decision{Accept: true, Reason: reason} }
	if !p.ValidDigest() || packet.Hook != "output" && packet.Hook != "forward" || !packet.Source.IsValid() || !packet.Destination.IsValid() || packet.Source.Is4() != packet.Destination.Is4() || packet.Source.Is4In6() || packet.Destination.Is4In6() {
		return drop("invalid_policy_or_packet")
	}
	if packet.Hook == "output" && packet.OutputInterface == "lo" {
		return accept("loopback")
	}
	if packet.OutputInterface == p.Owner.InterfaceName {
		return accept("wireguard_egress")
	}
	if packet.Hook == "output" && packet.Protocol == "udp" && packet.Mark == p.FirewallMark {
		for _, endpoint := range p.Endpoints {
			address, err := netip.ParseAddr(endpoint.Address)
			if err != nil {
				return drop("invalid_policy_or_packet")
			}
			if packet.Destination == address && packet.DestinationPort == endpoint.Port && packet.OutputInterface == endpoint.InterfaceName {
				return accept("authenticated_transport_path")
			}
		}
	}
	if (packet.Protocol == "tcp" || packet.Protocol == "udp") && (packet.DestinationPort == 53 || packet.DestinationPort == 853) {
		return drop("outside_tunnel_dns")
	}
	if slices.Contains(p.LocalInterfaces, packet.OutputInterface) {
		for _, text := range p.LocalNetworks {
			prefix, err := netip.ParsePrefix(text)
			if err != nil {
				return drop("invalid_policy_or_packet")
			}
			if prefix.Contains(packet.Destination) {
				return accept("explicit_local_network")
			}
		}
		if packet.Hook == "output" {
			if packet.Destination.Is4() && packet.Destination == netip.MustParseAddr("255.255.255.255") && packet.Protocol == "udp" && ((packet.SourcePort == 68 && packet.DestinationPort == 67) || (packet.SourcePort == 67 && packet.DestinationPort == 68)) {
				return accept("local_dhcpv4")
			}
			if packet.Destination.Is6() {
				linkScoped := packet.Destination.IsLinkLocalUnicast() || netip.MustParsePrefix("ff02::/16").Contains(packet.Destination)
				if linkScoped && packet.HopLimit == 255 && packet.Protocol == "icmpv6" && (packet.ICMPv6Type == 133 || packet.ICMPv6Type == 134 || packet.ICMPv6Type == 135 || packet.ICMPv6Type == 136) {
					return accept("local_ipv6_neighbor_discovery")
				}
				if packet.Protocol == "udp" && ((packet.SourcePort == 546 && packet.DestinationPort == 547 && (packet.Destination.IsLinkLocalUnicast() || packet.Destination == netip.MustParseAddr("ff02::1:2"))) || (packet.SourcePort == 547 && packet.DestinationPort == 546 && packet.Destination.IsLinkLocalUnicast())) {
					return accept("local_dhcpv6")
				}
			}
		}
	}
	return drop("outside_tunnel")
}
