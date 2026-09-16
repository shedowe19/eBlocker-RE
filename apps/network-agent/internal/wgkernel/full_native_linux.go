package wgkernel

import (
	"context"
	"encoding/binary"
	"net"
	"net/netip"
	"os"
	"reflect"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
	"github.com/mdlayher/genetlink"
	mdnetlink "github.com/mdlayher/netlink"
	"github.com/vishvananda/netlink"
	"github.com/vishvananda/netlink/nl"
	"golang.org/x/sys/unix"
)

type nativeFullKernel struct{}

func (nativeFullKernel) environment(ctx context.Context, t manager.Target, config wireguard.Plan) (policy.Environment, error) {
	h, err := routeHandle(ctx)
	if err != nil {
		return policy.Environment{}, err
	}
	defer h.Close()
	if err = standardRules(ctx, nil, false); err != nil {
		return policy.Environment{}, err
	}
	links, err := h.LinkList()
	if err != nil {
		return policy.Environment{}, err
	}
	if err = checkTopology(h, links, ""); err != nil {
		return policy.Environment{}, err
	}
	if err = checkReversePathFiltering(links); err != nil {
		return policy.Environment{}, err
	}
	addresses, err := h.AddrList(nil, netlink.FAMILY_ALL)
	if err != nil {
		return policy.Environment{}, err
	}
	routes, err := h.RouteListFiltered(netlink.FAMILY_ALL, &netlink.Route{Table: unix.RT_TABLE_MAIN}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return policy.Environment{}, err
	}
	env := localEnvironment(links, addresses, routes)
	seen := map[netip.AddrPort]bool{}
	for _, peer := range config.Peers {
		endpoint, err := netip.ParseAddrPort(peer.Endpoint)
		if err != nil || !endpoint.Addr().IsGlobalUnicast() || endpoint.Addr().IsLinkLocalUnicast() {
			return env, manager.ErrUnsupportedProfile
		}
		if seen[endpoint] {
			continue
		}
		seen[endpoint] = true
		name, err := endpointUplink(h, endpoint.Addr(), 0)
		if err != nil {
			return env, err
		}
		env.Endpoints = append(env.Endpoints, policy.Endpoint{Address: endpoint.Addr().String(), Port: endpoint.Port(), InterfaceName: name})
	}
	return env, nil
}
func localEnvironment(links []netlink.Link, addresses []netlink.Addr, routes []netlink.Route) policy.Environment {
	env := policy.Environment{}
	names := map[int]string{}
	for _, item := range links {
		if item.Attrs().Name != "lo" && item.Attrs().Flags&net.FlagUp != 0 {
			names[item.Attrs().Index] = item.Attrs().Name
			env.LocalInterfaces = append(env.LocalInterfaces, item.Attrs().Name)
		}
	}
	for _, address := range addresses {
		if names[address.LinkIndex] == "" || address.IPNet == nil {
			continue
		}
		prefix, err := prefixFromIPNet(address.IPNet)
		if err != nil || !prefix.Addr().IsGlobalUnicast() || prefix.Addr().IsLinkLocalUnicast() {
			continue
		}
		prefix = prefix.Masked()
		for _, route := range routes {
			if route.LinkIndex != address.LinkIndex || route.Protocol != unix.RTPROT_KERNEL || route.Table != unix.RT_TABLE_MAIN || route.Type != unix.RTN_UNICAST || route.Dst == nil || len(route.Gw) != 0 || len(route.MultiPath) != 0 {
				continue
			}
			if route.Scope != netlink.SCOPE_LINK && !(prefix.Addr().Is6() && route.Scope == netlink.SCOPE_UNIVERSE) {
				continue
			}
			actual, err := prefixFromIPNet(route.Dst)
			if err == nil && actual.Masked() == prefix {
				env.LocalNetworks = append(env.LocalNetworks, prefix.String())
				break
			}
		}
	}
	slices.Sort(env.LocalInterfaces)
	env.LocalInterfaces = slices.Compact(env.LocalInterfaces)
	slices.Sort(env.LocalNetworks)
	env.LocalNetworks = slices.Compact(env.LocalNetworks)
	return env
}
func checkTopology(h *netlink.Handle, links []netlink.Link, own string) error {
	for _, item := range links {
		attrs := item.Attrs()
		if attrs.Name == own {
			continue
		}
		if attrs.MasterIndex != 0 || attrs.PhysSwitchID != 0 || attrs.Xdp != nil && (attrs.Xdp.Attached || attrs.Xdp.ProgId != 0) || len(attrs.Vfs) != 0 {
			return manager.ErrUnsupportedProfile
		}
		// Bridges, VRFs, other VPNs, tunnel encapsulation and hardware switching need
		// their own audited datapath; this runtime cannot claim coverage for them.
		if item.Type() != "device" && item.Type() != "veth" {
			return manager.ErrUnsupportedProfile
		}
		filters, err := h.FilterList(item, 0)
		if err != nil {
			return err
		}
		if len(filters) != 0 {
			return manager.ErrUnsupportedProfile
		}
	}
	return nil
}

// Strict reverse-path filtering needs additional global/conntrack changes for
// marked WireGuard transport replies. Reject it rather than modify host sysctls.
func checkReversePathFiltering(links []netlink.Link) error {
	read := func(name string) (int, error) {
		data, err := os.ReadFile("/proc/sys/net/ipv4/conf/" + name + "/rp_filter")
		if err != nil {
			return 0, err
		}
		value, err := strconv.Atoi(strings.TrimSpace(string(data)))
		if err != nil || value < 0 || value > 2 {
			return 0, manager.ErrUnsupportedProfile
		}
		return value, nil
	}
	all, err := read("all")
	if err != nil {
		return err
	}
	for _, item := range links {
		if item.Attrs().Name == "lo" || item.Attrs().Flags&net.FlagUp == 0 {
			continue
		}
		value, err := read(item.Attrs().Name)
		if err != nil {
			return err
		}
		if max(all, value) == 1 {
			return manager.ErrUnsupportedProfile
		}
	}
	return nil
}
func endpointUplink(h *netlink.Handle, address netip.Addr, mark uint32) (string, error) {
	routes, err := h.RouteGetWithOptions(net.IP(address.AsSlice()), &netlink.RouteGetOptions{Mark: mark})
	if err != nil {
		return "", err
	}
	if len(routes) != 1 || routes[0].Type != unix.RTN_UNICAST || routes[0].LinkIndex <= 0 || len(routes[0].MultiPath) != 0 || routes[0].Encap != nil {
		return "", manager.ErrUnsupportedProfile
	}
	item, err := h.LinkByIndex(routes[0].LinkIndex)
	if err != nil {
		return "", err
	}
	if item.Attrs().Name == "lo" || item.Attrs().Flags&net.FlagUp == 0 || (item.Type() != "device" && item.Type() != "veth") {
		return "", manager.ErrUnsupportedProfile
	}
	return item.Attrs().Name, nil
}
func (nativeFullKernel) preflight(ctx context.Context, p policy.Plan) error {
	if _, err := compileGuard(p); err != nil {
		return err
	}
	if err := standardRules(ctx, nil, false); err != nil {
		return err
	}
	h, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer h.Close()
	routes, err := h.RouteListFiltered(netlink.FAMILY_ALL, &netlink.Route{Table: int(p.RouteTable)}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return err
	}
	if len(routes) != 0 {
		return manager.ErrOwnershipMismatch
	}
	return checkNftEnvironment(ctx, p, false)
}
func checkNftEnvironment(ctx context.Context, p policy.Plan, allowOwn bool) error {
	c, err := nftConnection(ctx)
	if err != nil {
		return err
	}
	transport, err := nftNativeTransport(ctx)
	if err != nil {
		return err
	}
	defer transport.Close()
	before, err := nftGeneration(transport)
	if err != nil {
		return err
	}
	tables, err := c.ListTables()
	if err != nil {
		return err
	}
	for _, table := range tables {
		if table.Name == p.NFTTable && !allowOwn {
			return manager.ErrOwnershipMismatch
		}
		flows, err := c.ListFlowtables(table)
		if err != nil {
			return err
		}
		if len(flows) != 0 {
			return manager.ErrUnsupportedProfile
		}
	}
	after, err := nftGeneration(transport)
	if err != nil {
		return err
	}
	if before != after {
		return manager.ErrUnavailable
	}
	return nil
}
func expectedRules(p policy.Plan, family int) []netlink.Rule {
	main := netlink.NewRule()
	main.Family = family
	main.Table = unix.RT_TABLE_MAIN
	main.Priority = int(p.MainRulePriority)
	main.SuppressPrefixlen = 0
	main.Protocol = unix.RTPROT_STATIC
	main.Type = unix.RTN_UNICAST
	tunnel := netlink.NewRule()
	tunnel.Family = family
	tunnel.Table = int(p.RouteTable)
	tunnel.Priority = int(p.TunnelRulePriority)
	tunnel.Mark = p.FirewallMark
	mask := uint32(0xffffffff)
	tunnel.Mask = &mask
	tunnel.Invert = true
	tunnel.Protocol = unix.RTPROT_STATIC
	tunnel.Type = unix.RTN_UNICAST
	return []netlink.Rule{*main, *tunnel}
}
func standardRule(r netlink.Rule) bool {
	baseline := netlink.NewRule()
	baseline.Family = r.Family
	baseline.Type = unix.RTN_UNICAST
	baseline.Protocol = r.Protocol
	switch {
	case r.Priority == 0 && r.Table == unix.RT_TABLE_LOCAL:
		baseline.Priority = 0
		baseline.Table = unix.RT_TABLE_LOCAL
	case r.Priority == 32766 && r.Table == unix.RT_TABLE_MAIN:
		baseline.Priority = 32766
		baseline.Table = unix.RT_TABLE_MAIN
	case r.Priority == 32767 && r.Table == unix.RT_TABLE_DEFAULT:
		baseline.Priority = 32767
		baseline.Table = unix.RT_TABLE_DEFAULT
	default:
		return false
	}
	return reflect.DeepEqual(r, *baseline)
}
func classifyRules(rules []netlink.Rule, p *policy.Plan, requireAll bool) error {
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		standard := map[int]bool{}
		found := map[int]bool{}
		for _, r := range rules {
			if r.Family != family {
				continue
			}
			if standardRule(r) {
				if standard[r.Priority] {
					return manager.ErrUnsupportedProfile
				}
				standard[r.Priority] = true
				continue
			}
			matched := false
			if p != nil {
				for _, want := range expectedRules(*p, family) {
					if reflect.DeepEqual(r, want) && !found[r.Priority] {
						matched = true
						found[r.Priority] = true
						break
					}
				}
			}
			if !matched {
				return manager.ErrOwnershipMismatch
			}
		}
		if !standard[0] || !standard[32766] {
			return manager.ErrUnsupportedProfile
		}
		if requireAll && (p == nil || !found[int(p.MainRulePriority)] || !found[int(p.TunnelRulePriority)]) {
			return manager.ErrUnavailable
		}
	}
	return nil
}
func standardRules(ctx context.Context, p *policy.Plan, requireAll bool) error {
	rules, err := readPolicyRules(ctx)
	if err != nil {
		return err
	}
	return classifyRules(rules, p, requireAll)
}

// Decode the native rule action and reject unknown selectors. The general
// rtnetlink library currently omits the action on reads, which would otherwise
// mistake a foreign unreachable/blackhole rule for an owned lookup rule.
func readPolicyRules(ctx context.Context) ([]netlink.Rule, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	c, err := mdnetlink.Dial(unix.NETLINK_ROUTE, nil)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	deadline := time.Now().Add(2 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	if err = c.SetDeadline(deadline); err != nil {
		return nil, err
	}
	var result []netlink.Rule
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		data := make([]byte, 12)
		data[0] = byte(family)
		messages, err := c.Execute(mdnetlink.Message{Header: mdnetlink.Header{Type: unix.RTM_GETRULE, Flags: mdnetlink.Request | mdnetlink.Dump}, Data: data})
		if err != nil {
			return nil, err
		}
		if len(messages) > 4096 {
			return nil, manager.ErrUnsupportedProfile
		}
		for _, m := range messages {
			if m.Header.Flags&mdnetlink.DumpInterrupted != 0 {
				return nil, manager.ErrUnavailable
			}
			r, err := decodePolicyRule(m.Data)
			if err != nil {
				return nil, err
			}
			result = append(result, r)
		}
	}
	// mdlayher/netlink strips NLMSG_DONE, including its DUMP_INTR flag. Cross-
	// check against the rtnetlink library, which explicitly reports interrupted
	// dumps, before trusting absence of any foreign rule. Keep our native action
	// decoder because the second library omits that field.
	h, err := routeHandle(ctx)
	if err != nil {
		return nil, err
	}
	defer h.Close()
	var checked []netlink.Rule
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		rules, err := h.RuleList(family)
		if err != nil {
			return nil, err
		}
		checked = append(checked, rules...)
	}
	if !samePolicyRuleDump(result, checked) {
		return nil, manager.ErrUnavailable
	}
	return result, nil
}

func samePolicyRuleDump(native, checked []netlink.Rule) bool {
	if len(native) != len(checked) {
		return false
	}
	used := make([]bool, len(checked))
	for _, r := range native {
		r.Type = 0
		found := false
		for i, other := range checked {
			if !used[i] && reflect.DeepEqual(r, other) {
				used[i] = true
				found = true
				break
			}
		}
		if !found {
			return false
		}
	}
	return true
}
func decodePolicyRule(data []byte) (netlink.Rule, error) {
	r := netlink.NewRule()
	if len(data) < 12 {
		return *r, errMalformedDevice
	}
	r.Family = int(data[0])
	if r.Family != unix.AF_INET && r.Family != unix.AF_INET6 {
		return *r, manager.ErrUnsupportedProfile
	}
	r.Table = int(data[4])
	r.Type = data[7]
	r.Priority = 0
	if data[1] != 0 || data[2] != 0 || data[3] != 0 || data[5] != 0 || data[6] != 0 {
		return *r, manager.ErrUnsupportedProfile
	}
	flags := binary.NativeEndian.Uint32(data[8:])
	if flags & ^uint32(2) != 0 {
		return *r, manager.ErrUnsupportedProfile
	}
	r.Invert = flags&2 != 0
	attrs, err := mdnetlink.UnmarshalAttributes(data[12:])
	if err != nil {
		return *r, err
	}
	seen := map[uint16]bool{}
	for _, a := range attrs {
		if seen[a.Type] {
			return *r, errMalformedDevice
		}
		seen[a.Type] = true
		if a.Type == nl.FRA_PROTOCOL {
			if len(a.Data) != 1 {
				return *r, errMalformedDevice
			}
			r.Protocol = a.Data[0]
			continue
		}
		if len(a.Data) != 4 {
			return *r, manager.ErrUnsupportedProfile
		}
		v := binary.NativeEndian.Uint32(a.Data)
		switch a.Type {
		case unix.RTA_TABLE:
			r.Table = int(v)
		case nl.FRA_PRIORITY:
			r.Priority = int(v)
		case nl.FRA_FWMARK:
			r.Mark = v
		case nl.FRA_FWMASK:
			r.Mask = &v
		case nl.FRA_SUPPRESS_PREFIXLEN:
			if v != 0xffffffff {
				r.SuppressPrefixlen = int(v)
			}
		case nl.FRA_SUPPRESS_IFGROUP:
			if v != 0xffffffff {
				r.SuppressIfgroup = int(v)
			}
		default:
			return *r, manager.ErrUnsupportedProfile
		}
	}
	return *r, nil
}
func privateRoute(item link, p policy.Plan, prefix netip.Prefix) netlink.Route {
	scope := netlink.SCOPE_LINK
	// IPv6 route dumps use universe scope for ordinary unicast device routes.
	if prefix.Addr().Is6() {
		scope = netlink.SCOPE_UNIVERSE
	}
	return netlink.Route{LinkIndex: item.index, Dst: &net.IPNet{IP: prefix.Masked().Addr().AsSlice(), Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen())}, Table: int(p.RouteTable), Protocol: unix.RTPROT_STATIC, Scope: scope, Type: unix.RTN_UNICAST, Priority: 32760}
}
func (nativeFullKernel) installRouting(ctx context.Context, item link, p policy.Plan) error {
	h, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer h.Close()
	if _, err = verifyLink(h, item); err != nil {
		return err
	}
	if err = standardRules(ctx, nil, false); err != nil {
		return err
	}
	for _, text := range p.AllowedIPs {
		prefix, err := netip.ParsePrefix(text)
		if err != nil {
			return err
		}
		r := privateRoute(item, p, prefix)
		if err = h.RouteAdd(&r); err != nil {
			return err
		}
	}
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		for _, r := range expectedRules(p, family) {
			if err = h.RuleAdd(&r); err != nil {
				return err
			}
		}
	}
	return nil
}
func validPrivateRoutes(routes []netlink.Route, item link, p policy.Plan, requireAll bool) bool {
	expected := map[string]bool{}
	for _, prefix := range p.AllowedIPs {
		expected[prefix] = false
	}
	for _, r := range routes {
		if item.index == 0 || r.LinkIndex != item.index || r.Table != int(p.RouteTable) || r.Protocol != unix.RTPROT_STATIC || r.Type != unix.RTN_UNICAST || r.Priority != 32760 || len(r.Gw) != 0 || len(r.MultiPath) != 0 || r.Encap != nil || r.NewDst != nil || r.Via != nil || r.MPLSDst != nil || r.ILinkIndex != 0 || r.Tos != 0 || r.Realm != 0 || len(r.Src) != 0 {
			return false
		}
		var prefix netip.Prefix
		if r.Dst == nil {
			if r.Family == unix.AF_INET {
				prefix = netip.MustParsePrefix("0.0.0.0/0")
			} else if r.Family == unix.AF_INET6 {
				prefix = netip.MustParsePrefix("::/0")
			} else {
				return false
			}
		} else {
			var err error
			prefix, err = prefixFromIPNet(r.Dst)
			if err != nil {
				return false
			}
			prefix = prefix.Masked()
		}
		if prefix.Addr().Is4() && r.Scope != netlink.SCOPE_LINK || prefix.Addr().Is6() && r.Scope != netlink.SCOPE_UNIVERSE {
			return false
		}
		found, ok := expected[prefix.String()]
		if !ok || found {
			return false
		}
		expected[prefix.String()] = true
	}
	if requireAll {
		for _, found := range expected {
			if !found {
				return false
			}
		}
	}
	return true
}
func (nativeFullKernel) routing(ctx context.Context, item link, p policy.Plan) (bool, bool, error) {
	if err := standardRules(ctx, &p, true); err != nil {
		return false, false, err
	}
	h, err := routeHandle(ctx)
	if err != nil {
		return false, false, err
	}
	defer h.Close()
	if _, err = verifyLink(h, item); err != nil {
		return false, false, err
	}
	routes, err := h.RouteListFiltered(netlink.FAMILY_ALL, &netlink.Route{Table: int(p.RouteTable)}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return false, false, err
	}
	valid := validPrivateRoutes(routes, item, p, true)
	return valid, valid, nil
}
func (nativeFullKernel) endpoints(ctx context.Context, p policy.Plan) (bool, error) {
	h, err := routeHandle(ctx)
	if err != nil {
		return false, err
	}
	defer h.Close()
	links, err := h.LinkList()
	if err != nil {
		return false, err
	}
	if err = checkTopology(h, links, p.Owner.InterfaceName); err != nil {
		return false, err
	}
	var external []netlink.Link
	for _, item := range links {
		if item.Attrs().Name != p.Owner.InterfaceName {
			external = append(external, item)
		}
	}
	if err = checkReversePathFiltering(external); err != nil {
		return false, err
	}
	addresses, err := h.AddrList(nil, netlink.FAMILY_ALL)
	if err != nil {
		return false, err
	}
	routes, err := h.RouteListFiltered(netlink.FAMILY_ALL, &netlink.Route{Table: unix.RT_TABLE_MAIN}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return false, err
	}
	environment := localEnvironment(external, addresses, routes)
	if !slices.Equal(environment.LocalInterfaces, p.LocalInterfaces) || !slices.Equal(environment.LocalNetworks, p.LocalNetworks) {
		return false, nil
	}
	if err = checkNftEnvironment(ctx, p, true); err != nil {
		return false, err
	}
	for _, e := range p.Endpoints {
		address, err := netip.ParseAddr(e.Address)
		if err != nil {
			return false, err
		}
		name, err := endpointUplink(h, address, p.FirewallMark)
		if err != nil {
			return false, err
		}
		if name != e.InterfaceName {
			return false, nil
		}
	}
	return true, nil
}
func (nativeFullKernel) removeRouting(ctx context.Context, item link, p policy.Plan) error {
	// Do not delete any rule unless the full owned guard is still present.
	if exists, verified, err := (nativeFullKernel{}).guard(ctx, p); err != nil {
		return err
	} else if !exists || !verified {
		return manager.ErrOwnershipMismatch
	}
	rules, err := readPolicyRules(ctx)
	if err != nil {
		return err
	}
	if err = classifyRules(rules, &p, false); err != nil {
		return err
	}
	h, err := routeHandle(ctx)
	if err != nil {
		return err
	}
	defer h.Close()
	if item.index != 0 {
		if _, err = verifyLink(h, item); err != nil {
			return err
		}
	}
	routes, err := h.RouteListFiltered(netlink.FAMILY_ALL, &netlink.Route{Table: int(p.RouteTable)}, netlink.RT_FILTER_TABLE)
	if err != nil {
		return err
	}
	if !validPrivateRoutes(routes, item, p, false) {
		return manager.ErrOwnershipMismatch
	}
	for _, r := range rules {
		if r.Priority == int(p.MainRulePriority) || r.Priority == int(p.TunnelRulePriority) {
			if err = h.RuleDel(&r); err != nil {
				return err
			}
		}
	}
	for _, r := range routes {
		if err = h.RouteDel(&r); err != nil {
			return err
		}
	}
	return nil
}
func (nativeFullKernel) mark(ctx context.Context, item link) (uint32, error) {
	c, family, err := wireGuardHandle(ctx)
	if err != nil {
		return 0, err
	}
	defer c.Close()
	encoder := mdnetlink.NewAttributeEncoder()
	encoder.Uint32(deviceIndex, uint32(item.index))
	data, err := encoder.Encode()
	if err != nil {
		return 0, err
	}
	messages, err := c.Execute(genetlink.Message{Header: genetlink.Header{Command: wgGetDevice, Version: wgVersion}, Data: data}, family, mdnetlink.Request|mdnetlink.Dump)
	defer func() {
		for _, m := range messages {
			clear(m.Data)
		}
	}()
	if err != nil {
		return 0, err
	}
	return decodeMark(item.index, messages)
}
func decodeMark(index int, messages []genetlink.Message) (uint32, error) {
	var value uint32
	found := false
	indexFound := false
	size := 0
	for _, m := range messages {
		size += len(m.Data)
		if size > 4<<20 || m.Header.Version != wgVersion {
			return 0, errMalformedDevice
		}
		attrs, err := uapiAttributes(m.Data)
		if err != nil {
			return 0, err
		}
		for _, a := range attrs {
			switch a.kind {
			case deviceIndex:
				if len(a.data) != 4 || binary.NativeEndian.Uint32(a.data) != uint32(index) {
					return 0, errMalformedDevice
				}
				indexFound = true
			case 7:
				if len(a.data) != 4 || found {
					return 0, errMalformedDevice
				}
				value = binary.NativeEndian.Uint32(a.data)
				found = true
			}
		}
	}
	if !found || !indexFound {
		return 0, errMalformedDevice
	}
	return value, nil
}
