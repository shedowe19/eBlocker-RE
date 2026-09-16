// Package policy defines the public, deterministic contract for a guarded
// WireGuard full tunnel. It does not perform kernel or firewall operations.
package policy

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/netip"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/eblocker/eblocker/libs/wireguard"
)

var ErrUnsupported = errors.New("full-tunnel policy is unsupported or inconsistent")

type Owner struct {
	InterfaceName string `json:"interfaceName"`
	OwnershipID   string `json:"ownershipId"`
}

// Endpoint pins an already resolved WireGuard peer to the observed original
// uplink. It permits only outer UDP bearing this tunnel's exact socket mark.
type Endpoint struct {
	Address       string `json:"address"`
	Port          uint16 `json:"port"`
	InterfaceName string `json:"interfaceName"`
}

// Environment must come from a fresh native kernel observation. LocalNetworks
// are explicit management/LAN exceptions, never arbitrary existing route tables.
type Environment struct {
	LocalNetworks   []string   `json:"localNetworks"`
	LocalInterfaces []string   `json:"localInterfaces"`
	Endpoints       []Endpoint `json:"endpoints"`
}

type Plan struct {
	Version            int        `json:"version"`
	Owner              Owner      `json:"owner"`
	RouteTable         uint32     `json:"routeTable"`
	FirewallMark       uint32     `json:"firewallMark"`
	MainRulePriority   uint32     `json:"mainRulePriority"`
	TunnelRulePriority uint32     `json:"tunnelRulePriority"`
	NFTTable           string     `json:"nftTable"`
	AllowedIPs         []string   `json:"allowedIPs"`
	Endpoints          []Endpoint `json:"endpoints"`
	LocalNetworks      []string   `json:"localNetworks"`
	LocalInterfaces    []string   `json:"localInterfaces"`
	IPv4Default        bool       `json:"ipv4Default"`
	IPv6Default        bool       `json:"ipv6Default"`
	Digest             string     `json:"digest"`
}

// Attestation is populated only after a native backend has compared the actual
// device mark, both AF policy-rule pairs, routes, and complete owned nft chains
// against the journaled plan. A digest/ownership match alone is insufficient.
type Attestation struct {
	Owner                Owner  `json:"owner"`
	Digest               string `json:"digest"`
	RoutingVerified      bool   `json:"routingVerified"`
	FirewallVerified     bool   `json:"firewallVerified"`
	MarkVerified         bool   `json:"markVerified"`
	EndpointsVerified    bool   `json:"endpointsVerified"`
	IPv4PoliciesVerified bool   `json:"ipv4PoliciesVerified"`
	IPv6PoliciesVerified bool   `json:"ipv6PoliciesVerified"`
	KillSwitchActive     bool   `json:"killSwitchActive"`
}

func (a Attestation) Matches(plan Plan) bool {
	return a.Owner == plan.Owner && a.Digest == plan.Digest && plan.ValidDigest() && a.RoutingVerified && a.FirewallVerified && a.MarkVerified && a.EndpointsVerified && a.IPv4PoliciesVerified && a.IPv6PoliciesVerified && a.KillSwitchActive
}

var ownerPattern = regexp.MustCompile(`^[0-9a-f]{32}$`)
var interfacePattern = regexp.MustCompile(`^[a-zA-Z0-9_.:-]{1,15}$`)

// Build fixes resource identifiers before mutation. Native preflight must still
// reject collisions, foreign policy rules, bridges/offload/flowtables, and any
// unsupported topology; deterministic identifiers do not confer ownership.
func Build(owner Owner, config wireguard.Plan, environment Environment) (Plan, error) {
	if !ownerPattern.MatchString(owner.OwnershipID) || owner.InterfaceName != "ebwg"+owner.OwnershipID[:10] || (!config.DefaultRouteIPv4 && !config.DefaultRouteIPv6) || len(config.DNS) != 0 || len(config.Peers) == 0 || len(config.Peers) > wireguard.MaxPeers {
		return Plan{}, ErrUnsupported
	}
	token, _ := hex.DecodeString(owner.OwnershipID)
	resourceID := uint32(0xeb000000) | (binary.BigEndian.Uint32(token) & 0x00ffffff)
	result := Plan{Version: 1, Owner: owner, RouteTable: resourceID, FirewallMark: resourceID, MainRulePriority: 11000, TunnelRulePriority: 11001, NFTTable: "ebwg_" + owner.OwnershipID, AllowedIPs: []string{}, Endpoints: []Endpoint{}, LocalNetworks: []string{}, LocalInterfaces: []string{}, IPv4Default: config.DefaultRouteIPv4, IPv6Default: config.DefaultRouteIPv6}
	if len(environment.LocalNetworks) > 256 || len(environment.LocalInterfaces) > 128 || len(environment.Endpoints) > wireguard.MaxPeers {
		return Plan{}, ErrUnsupported
	}
	for _, name := range environment.LocalInterfaces {
		if !validExternalInterface(name, owner) {
			return Plan{}, ErrUnsupported
		}
		result.LocalInterfaces = append(result.LocalInterfaces, name)
	}
	slices.Sort(result.LocalInterfaces)
	result.LocalInterfaces = slices.Compact(result.LocalInterfaces)
	for _, text := range environment.LocalNetworks {
		prefix, err := netip.ParsePrefix(text)
		if err != nil || prefix.Addr().Is4In6() || prefix.Addr().Zone() != "" || !validLocalNetwork(prefix) {
			return Plan{}, ErrUnsupported
		}
		result.LocalNetworks = append(result.LocalNetworks, prefix.Masked().String())
	}
	slices.Sort(result.LocalNetworks)
	result.LocalNetworks = slices.Compact(result.LocalNetworks)
	if len(result.LocalNetworks)*len(result.LocalInterfaces) > 512 {
		return Plan{}, ErrUnsupported
	}
	if len(result.LocalNetworks) > 0 && len(result.LocalInterfaces) == 0 {
		return Plan{}, ErrUnsupported
	}
	endpointSet := map[netip.AddrPort]bool{}
	for _, peer := range config.Peers {
		endpoint, err := netip.ParseAddrPort(peer.Endpoint)
		if err != nil || !validEndpoint(endpoint.Addr()) || endpoint.Port() == 0 {
			return Plan{}, ErrUnsupported
		}
		endpointSet[endpoint] = true
		for _, text := range peer.AllowedIPs {
			prefix, err := netip.ParsePrefix(text)
			if err != nil || prefix.Addr().Is4In6() || prefix.Addr().Zone() != "" {
				return Plan{}, ErrUnsupported
			}
			result.AllowedIPs = append(result.AllowedIPs, prefix.Masked().String())
		}
	}
	if len(result.AllowedIPs) == 0 || len(result.AllowedIPs) > wireguard.MaxPrefixes {
		return Plan{}, ErrUnsupported
	}
	slices.Sort(result.AllowedIPs)
	for i, text := range result.AllowedIPs {
		prefix := netip.MustParsePrefix(text)
		for _, previous := range result.AllowedIPs[:i] {
			if prefix.Overlaps(netip.MustParsePrefix(previous)) {
				return Plan{}, ErrUnsupported
			}
		}
	}
	for _, endpoint := range environment.Endpoints {
		address, err := netip.ParseAddr(endpoint.Address)
		if err != nil || !validEndpoint(address) || endpoint.Port == 0 || !validExternalInterface(endpoint.InterfaceName, owner) {
			return Plan{}, ErrUnsupported
		}
		key := netip.AddrPortFrom(address, endpoint.Port)
		if !endpointSet[key] {
			return Plan{}, ErrUnsupported
		}
		delete(endpointSet, key)
		endpoint.Address = address.String()
		result.Endpoints = append(result.Endpoints, endpoint)
	}
	if len(endpointSet) != 0 {
		return Plan{}, ErrUnsupported
	}
	slices.SortFunc(result.Endpoints, func(a, b Endpoint) int { return strings.Compare(endpointIdentity(a), endpointIdentity(b)) })
	result.Digest = result.digest()
	return result, nil
}

func endpointIdentity(endpoint Endpoint) string {
	return endpoint.Address + ":" + strconv.Itoa(int(endpoint.Port)) + ":" + endpoint.InterfaceName
}
func validExternalInterface(name string, owner Owner) bool {
	return interfacePattern.MatchString(name) && name != "lo" && name != owner.InterfaceName
}
func validEndpoint(address netip.Addr) bool {
	return address.IsValid() && address.IsGlobalUnicast() && !address.Is4In6() && !address.IsLinkLocalUnicast() && address.Zone() == ""
}

func validLocalNetwork(prefix netip.Prefix) bool {
	if prefix.Addr().Is4() {
		if prefix.Bits() < 8 {
			return false
		}
		for _, protected := range []string{"0.0.0.0/8", "127.0.0.0/8", "224.0.0.0/4", "240.0.0.0/4"} {
			if prefix.Overlaps(netip.MustParsePrefix(protected)) {
				return false
			}
		}
		return true
	}
	if prefix.Bits() < 48 {
		return false
	}
	for _, protected := range []string{"::/128", "::1/128", "::ffff:0:0/96", "ff00::/8"} {
		if prefix.Overlaps(netip.MustParsePrefix(protected)) {
			return false
		}
	}
	return true
}

func (p Plan) Environment() Environment {
	return Environment{LocalNetworks: slices.Clone(p.LocalNetworks), LocalInterfaces: slices.Clone(p.LocalInterfaces), Endpoints: slices.Clone(p.Endpoints)}
}

// Validate checks the complete canonical plan against the imported profile and
// owner, including all resource identifiers and the integrity digest.
func (p Plan) Validate(owner Owner, config wireguard.Plan) error {
	expected, err := Build(owner, config, p.Environment())
	if err != nil {
		return err
	}
	actualBytes, _ := json.Marshal(p)
	expectedBytes, _ := json.Marshal(expected)
	if !slices.Equal(actualBytes, expectedBytes) {
		return ErrUnsupported
	}
	return nil
}

func (p Plan) digest() string {
	p.Digest = ""
	data, _ := json.Marshal(p)
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
func (p Plan) ValidDigest() bool { return len(p.Digest) == 64 && p.Digest == p.digest() }
func (p Plan) OwnershipMarker() string {
	return "eblocker-wireguard:" + p.Owner.OwnershipID + ":" + p.Digest
}
