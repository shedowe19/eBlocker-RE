package policy

import (
	"encoding/json"
	"net/netip"
	"slices"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard"
)

const configuration = "[Interface]\nPrivateKey=AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\nAddress=10.20.0.2/32,fd20::2/128\n[Peer]\nPublicKey=AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\nAllowedIPs=0.0.0.0/0,::/0\nEndpoint=198.51.100.1:53\n"

func fixture(t *testing.T) (Owner, wireguard.Plan, Environment, Plan) {
	t.Helper()
	profile, err := wireguard.Parse([]byte(configuration))
	if err != nil {
		t.Fatal(err)
	}
	defer profile.Destroy()
	owner := Owner{OwnershipID: "1234567890abcdef1234567890abcdef", InterfaceName: "ebwg1234567890"}
	env := Environment{LocalNetworks: []string{"192.168.1.0/24", "2001:db8:1::/64"}, LocalInterfaces: []string{"eth0", "eth1"}, Endpoints: []Endpoint{{Address: "198.51.100.1", Port: 53, InterfaceName: "eth0"}}}
	plan, err := Build(owner, profile.Plan(), env)
	if err != nil {
		t.Fatal(err)
	}
	return owner, profile.Plan(), env, plan
}

func TestCanonicalPlanBindsAllOwnershipAndPolicyFields(t *testing.T) {
	owner, config, env, plan := fixture(t)
	slices.Reverse(env.LocalNetworks)
	slices.Reverse(env.LocalInterfaces)
	env.LocalNetworks = append(env.LocalNetworks, "192.168.1.12/24")
	again, err := Build(owner, config, env)
	if err != nil || again.Digest != plan.Digest {
		t.Fatal("equivalent environment is not canonical", err)
	}
	if plan.RouteTable != 0xeb345678 || plan.FirewallMark != plan.RouteTable || !plan.IPv4Default || !plan.IPv6Default {
		t.Fatal("incorrect policy resource identifiers")
	}
	if err := plan.Validate(owner, config); err != nil {
		t.Fatal(err)
	}
	plan.FirewallMark++
	if plan.ValidDigest() || plan.Validate(owner, config) == nil {
		t.Fatal("altered policy accepted")
	}
	plan.Digest = plan.digest()
	if plan.Validate(owner, config) == nil {
		t.Fatal("rehashed noncanonical policy accepted")
	}
}

func TestUnsupportedPolicyFailsBeforeMutation(t *testing.T) {
	for _, name := range []string{"dns", "hostname", "missing_endpoint", "foreign_endpoint", "duplicate_endpoint", "split", "broad_lan", "multicast_lan", "bad_owner", "bad_interface", "duplicate_route"} {
		t.Run(name, func(t *testing.T) {
			owner, config, env, _ := fixture(t)
			switch name {
			case "dns":
				config.DNS = []string{"1.1.1.1"}
			case "hostname":
				config.Peers[0].Endpoint = "vpn.example:53"
			case "missing_endpoint":
				env.Endpoints = nil
			case "foreign_endpoint":
				env.Endpoints[0].Address = "198.51.100.2"
			case "duplicate_endpoint":
				env.Endpoints = append(env.Endpoints, env.Endpoints[0])
			case "split":
				config.DefaultRouteIPv4 = false
				config.DefaultRouteIPv6 = false
			case "broad_lan":
				env.LocalNetworks = []string{"0.0.0.0/0"}
			case "multicast_lan":
				env.LocalNetworks = []string{"ff02::/64"}
			case "bad_owner":
				owner.OwnershipID = "bad"
			case "bad_interface":
				env.LocalInterfaces = []string{owner.InterfaceName}
			case "duplicate_route":
				config.Peers[0].AllowedIPs = append(config.Peers[0].AllowedIPs, "10.0.0.0/8")
			}
			if _, err := Build(owner, config, env); err == nil {
				t.Fatal("unsafe policy accepted")
			}
		})
	}
}

func TestSingleFamilyFullTunnelStillGuardsBothFamilies(t *testing.T) {
	for _, allowed := range []string{"0.0.0.0/0", "::/0", "0.0.0.0/1,128.0.0.0/1,::/0"} {
		t.Run(allowed, func(t *testing.T) {
			owner, _, env, _ := fixture(t)
			profile, err := wireguard.Parse([]byte(strings.Replace(configuration, "0.0.0.0/0,::/0", allowed, 1)))
			if err != nil {
				t.Fatal(err)
			}
			defer profile.Destroy()
			plan, err := Build(owner, profile.Plan(), env)
			if err != nil {
				t.Fatal(err)
			}
			for _, destination := range []string{"1.1.1.1", "2606:4700:4700::1111"} {
				addr := netip.MustParseAddr(destination)
				if plan.Evaluate(Packet{Hook: "output", Source: addr, Destination: addr, OutputInterface: "eth0", Protocol: "tcp", DestinationPort: 443}).Accept {
					t.Fatal("untunneled family escaped")
				}
			}
		})
	}
}

func TestCompleteFreshAttestationRequired(t *testing.T) {
	_, _, _, plan := fixture(t)
	att := Attestation{Owner: plan.Owner, Digest: plan.Digest, RoutingVerified: true, FirewallVerified: true, MarkVerified: true, EndpointsVerified: true, IPv4PoliciesVerified: true, IPv6PoliciesVerified: true, KillSwitchActive: true}
	if !att.Matches(plan) {
		t.Fatal("complete attestation rejected")
	}
	encoded, _ := json.Marshal(att)
	var fields map[string]any
	_ = json.Unmarshal(encoded, &fields)
	for key, value := range fields {
		if _, ok := value.(bool); !ok {
			continue
		}
		t.Run(key, func(t *testing.T) {
			fields[key] = false
			data, _ := json.Marshal(fields)
			var incomplete Attestation
			_ = json.Unmarshal(data, &incomplete)
			if incomplete.Matches(plan) {
				t.Fatal("incomplete attestation accepted")
			}
			fields[key] = true
		})
	}
	att.Digest = strings.Repeat("0", 64)
	if att.Matches(plan) {
		t.Fatal("different policy accepted")
	}
}

func TestPacketPolicyPreventsLeaksWhilePreservingExplicitLANServices(t *testing.T) {
	_, _, _, plan := fixture(t)
	base := Packet{Hook: "output", Source: netip.MustParseAddr("192.168.1.2"), Destination: netip.MustParseAddr("1.1.1.1"), OutputInterface: "eth0", Protocol: "tcp", SourcePort: 40000, DestinationPort: 443}
	tests := []struct {
		name   string
		change func(*Packet)
		allow  bool
	}{
		{"wan_https", func(p *Packet) {}, false},
		{"wan_ipv6", func(p *Packet) {
			p.Source = netip.MustParseAddr("2001:db8:1::2")
			p.Destination = netip.MustParseAddr("2606:4700::1")
		}, false},
		{"tunnel", func(p *Packet) { p.OutputInterface = plan.Owner.InterfaceName }, true},
		{"tunnel_dns", func(p *Packet) {
			p.OutputInterface = plan.Owner.InterfaceName
			p.Protocol = "udp"
			p.DestinationPort = 53
		}, true},
		{"loopback_dns", func(p *Packet) {
			p.OutputInterface = "lo"
			p.Destination = netip.MustParseAddr("127.0.0.53")
			p.DestinationPort = 53
		}, true},
		{"lan_gui", func(p *Packet) { p.Destination = netip.MustParseAddr("192.168.1.3") }, true},
		{"lan_ipv6_gui", func(p *Packet) {
			p.Source = netip.MustParseAddr("2001:db8:1::2")
			p.Destination = netip.MustParseAddr("2001:db8:1::3")
		}, true},
		{"lan_wrong_interface", func(p *Packet) { p.Destination = netip.MustParseAddr("192.168.1.3"); p.OutputInterface = "eth9" }, false},
		{"lan_dns", func(p *Packet) { p.Destination = netip.MustParseAddr("192.168.1.3"); p.DestinationPort = 53 }, false},
		{"lan_dot", func(p *Packet) { p.Destination = netip.MustParseAddr("192.168.1.3"); p.DestinationPort = 853 }, false},
		{"broad_mark", func(p *Packet) { p.Mark = plan.FirewallMark }, false},
		{"dhcp_client", func(p *Packet) {
			p.Protocol = "udp"
			p.SourcePort = 68
			p.DestinationPort = 67
			p.Destination = netip.MustParseAddr("255.255.255.255")
		}, true},
		{"dhcp_server", func(p *Packet) {
			p.Protocol = "udp"
			p.SourcePort = 67
			p.DestinationPort = 68
			p.Destination = netip.MustParseAddr("255.255.255.255")
		}, true},
		{"forged_dhcp", func(p *Packet) { p.Protocol = "udp"; p.SourcePort = 68; p.DestinationPort = 67 }, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			p := base
			tc.change(&p)
			if got := plan.Evaluate(p); got.Accept != tc.allow {
				t.Fatalf("got %+v want allow %v", got, tc.allow)
			}
		})
	}
	endpoint := base
	endpoint.Protocol = "udp"
	endpoint.Mark = plan.FirewallMark
	endpoint.Destination = netip.MustParseAddr("198.51.100.1")
	endpoint.DestinationPort = 53
	if !plan.Evaluate(endpoint).Accept {
		t.Fatal("exact outer transport on DNS port was blocked")
	}
	for _, mutate := range []func(*Packet){func(p *Packet) { p.Mark++ }, func(p *Packet) { p.DestinationPort++ }, func(p *Packet) { p.OutputInterface = "eth1" }, func(p *Packet) { p.Hook = "forward" }, func(p *Packet) { p.Destination = netip.MustParseAddr("198.51.100.2") }} {
		p := endpoint
		mutate(&p)
		if plan.Evaluate(p).Accept {
			t.Fatal("broadened endpoint exemption")
		}
	}
	ndp := Packet{Hook: "output", Source: netip.MustParseAddr("2001:db8:1::2"), Destination: netip.MustParseAddr("ff02::1:ff00:3"), OutputInterface: "eth0", Protocol: "icmpv6", ICMPv6Type: 135, HopLimit: 255}
	if !plan.Evaluate(ndp).Accept {
		t.Fatal("neighbor discovery blocked")
	}
	for _, mutate := range []func(*Packet){func(p *Packet) { p.HopLimit = 64 }, func(p *Packet) { p.ICMPv6Type = 128 }, func(p *Packet) { p.Hook = "forward" }, func(p *Packet) { p.Destination = netip.MustParseAddr("ff05::1") }} {
		p := ndp
		mutate(&p)
		if plan.Evaluate(p).Accept {
			t.Fatal("broad neighbor discovery exemption")
		}
	}
	for _, server := range []bool{false, true} {
		p := ndp
		p.Protocol = "udp"
		p.SourcePort = 546
		p.DestinationPort = 547
		p.Destination = netip.MustParseAddr("ff02::1:2")
		if server {
			p.SourcePort = 547
			p.DestinationPort = 546
			p.Destination = netip.MustParseAddr("fe80::3")
		}
		if !plan.Evaluate(p).Accept {
			t.Fatal("local DHCPv6 blocked")
		}
	}
	plan.Digest = "corrupt"
	if plan.Evaluate(base).Accept {
		t.Fatal("corrupt policy accepted")
	}
}
