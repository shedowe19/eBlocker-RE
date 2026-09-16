package wgkernel

import (
	"encoding/binary"
	"net"
	"net/netip"
	"reflect"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard/policy"
	"github.com/mdlayher/genetlink"
	mdnetlink "github.com/mdlayher/netlink"
	"github.com/vishvananda/netlink"
	"github.com/vishvananda/netlink/nl"
	"golang.org/x/sys/unix"
)

func baselineRules() []netlink.Rule {
	var result []netlink.Rule
	for _, family := range []int{unix.AF_INET, unix.AF_INET6} {
		for _, entry := range [][2]int{{0, unix.RT_TABLE_LOCAL}, {32766, unix.RT_TABLE_MAIN}, {32767, unix.RT_TABLE_DEFAULT}} {
			r := netlink.NewRule()
			r.Priority = entry[0]
			r.Table = entry[1]
			r.Family = family
			r.Type = unix.RTN_UNICAST
			r.Protocol = unix.RTPROT_KERNEL
			result = append(result, *r)
		}
	}
	return result
}
func TestPolicyRulesRejectForeignSelectorsAndNeedBothFamilies(t *testing.T) {
	p := fullPlan(t)
	base := baselineRules()
	if err := classifyRules(base, nil, false); err != nil {
		t.Fatal(err)
	}
	complete := append(base, expectedRules(p, unix.AF_INET)...)
	complete = append(complete, expectedRules(p, unix.AF_INET6)...)
	if err := classifyRules(complete, &p, true); err != nil {
		t.Fatal(err)
	}
	for _, change := range []string{"foreign-rule", "duplicate", "wrong-action", "mask", "mark", "priority", "table", "family", "missing-ipv6", "suppress", "protocol"} {
		t.Run(change, func(t *testing.T) {
			rules := append([]netlink.Rule(nil), complete...)
			switch change {
			case "foreign-rule":
				r := *netlink.NewRule()
				r.Priority = 12000
				r.Table = 123
				r.Family = unix.AF_INET
				rules = append(rules, r)
			case "duplicate":
				rules = append(rules, rules[len(rules)-1])
			case "wrong-action":
				rules[6].Type = unix.RTN_BLACKHOLE
			case "mask":
				v := uint32(255)
				rules[7].Mask = &v
			case "mark":
				rules[7].Mark++
			case "priority":
				rules[6].Priority++
			case "table":
				rules[6].Table++
			case "family":
				rules[6].Family = unix.AF_INET6
			case "missing-ipv6":
				rules = rules[:8]
			case "suppress":
				rules[6].SuppressPrefixlen = -1
			case "protocol":
				rules[6].Protocol = unix.RTPROT_BOOT
			}
			if classifyRules(rules, &p, true) == nil {
				t.Fatal("foreign or incomplete rules accepted")
			}
		})
	}
	// Rollback permits any own partial prefix, but never a foreign selector.
	for _, rules := range [][]netlink.Rule{base, append(base, expectedRules(p, unix.AF_INET)[0])} {
		if err := classifyRules(rules, &p, false); err != nil {
			t.Fatal(err)
		}
	}
}
func ruleWire(t *testing.T, r netlink.Rule) []byte {
	t.Helper()
	header := make([]byte, 12)
	header[0] = byte(r.Family)
	header[7] = r.Type
	if r.Invert {
		binary.NativeEndian.PutUint32(header[8:], 2)
	}
	a := mdnetlink.NewAttributeEncoder()
	a.Uint32(unix.RTA_TABLE, uint32(r.Table))
	a.Uint32(nl.FRA_PRIORITY, uint32(r.Priority))
	a.Uint8(nl.FRA_PROTOCOL, r.Protocol)
	if r.Mask != nil {
		a.Uint32(nl.FRA_FWMARK, r.Mark)
		a.Uint32(nl.FRA_FWMASK, *r.Mask)
	}
	a.Uint32(nl.FRA_SUPPRESS_PREFIXLEN, uint32(r.SuppressPrefixlen))
	data, err := a.Encode()
	if err != nil {
		t.Fatal(err)
	}
	return append(header, data...)
}
func TestNativeRuleDecoderPreservesActionAndRejectsUnknownSelectors(t *testing.T) {
	p := fullPlan(t)
	for _, r := range append(baselineRules(), expectedRules(p, unix.AF_INET6)...) {
		decoded, err := decodePolicyRule(ruleWire(t, r))
		if err != nil || !reflect.DeepEqual(r, decoded) {
			t.Fatalf("roundtrip %v: %#v %v", r, decoded, err)
		}
	}
	want := expectedRules(p, unix.AF_INET)[0]
	data := ruleWire(t, want)
	data[7] = unix.RTN_BLACKHOLE
	decoded, err := decodePolicyRule(data)
	if err != nil || decoded.Type != unix.RTN_BLACKHOLE || reflect.DeepEqual(want, decoded) {
		t.Fatal("foreign action lost")
	}
	for _, change := range []string{"short", "source", "flags", "unknown", "duplicate", "badlength"} {
		t.Run(change, func(t *testing.T) {
			data := ruleWire(t, want)
			switch change {
			case "short":
				data = data[:11]
			case "source":
				data[2] = 32
			case "flags":
				data[8] = 4
			case "unknown":
				attrs, _ := mdnetlink.MarshalAttributes([]mdnetlink.Attribute{{Type: nl.FRA_GOTO, Data: make([]byte, 4)}})
				data = append(data, attrs...)
			case "duplicate":
				attrs, _ := mdnetlink.MarshalAttributes([]mdnetlink.Attribute{{Type: nl.FRA_PRIORITY, Data: make([]byte, 4)}})
				data = append(data, attrs...)
			case "badlength":
				data = data[:len(data)-1]
			}
			if _, err := decodePolicyRule(data); err == nil {
				t.Fatal("malformed rule accepted")
			}
		})
	}
}
func TestPrivateRouteReadbackHandlesDefaultsAndRejectsForeignEntries(t *testing.T) {
	p := fullPlan(t)
	item := link{index: 99}
	var routes []netlink.Route
	for _, text := range p.AllowedIPs {
		prefix := netip.MustParsePrefix(text)
		r := privateRoute(item, p, prefix)
		if prefix.Addr().Is4() {
			r.Family = unix.AF_INET
		} else {
			r.Family = unix.AF_INET6
		}
		r.Dst = nil
		routes = append(routes, r)
	}
	if !validPrivateRoutes(routes, item, p, true) {
		t.Fatal("kernel nil default Dst rejected")
	}
	if validPrivateRoutes(routes[:1], item, p, true) {
		t.Fatal("missing family accepted")
	}
	if !validPrivateRoutes(routes[:1], item, p, false) {
		t.Fatal("partial rollback rejected")
	}
	for _, change := range []string{"index", "table", "protocol", "scope", "type", "metric", "gateway", "multipath", "duplicate", "orphan"} {
		t.Run(change, func(t *testing.T) {
			copy := append([]netlink.Route(nil), routes...)
			owner := item
			switch change {
			case "index":
				copy[0].LinkIndex++
			case "table":
				copy[0].Table++
			case "protocol":
				copy[0].Protocol = unix.RTPROT_BOOT
			case "scope":
				copy[0].Scope = netlink.SCOPE_UNIVERSE
			case "type":
				copy[0].Type = unix.RTN_UNREACHABLE
			case "metric":
				copy[0].Priority++
			case "gateway":
				copy[0].Gw = net.ParseIP("192.0.2.1")
			case "multipath":
				copy[0].MultiPath = []*netlink.NexthopInfo{{LinkIndex: 3}}
			case "duplicate":
				copy = append(copy, copy[0])
			case "orphan":
				owner.index = 0
			}
			if validPrivateRoutes(copy, owner, p, false) {
				t.Fatal("foreign route accepted for cleanup")
			}
		})
	}
}
func TestLocalExceptionsRequireAddressesAndIdenticalConnectedKernelRoutes(t *testing.T) {
	links := []netlink.Link{&netlink.Device{LinkAttrs: netlink.LinkAttrs{Index: 2, Name: "eth0", Flags: net.FlagUp}}, &netlink.Device{LinkAttrs: netlink.LinkAttrs{Index: 1, Name: "lo", Flags: net.FlagUp}}}
	addr := func(text string) netlink.Addr {
		p := netip.MustParsePrefix(text)
		return netlink.Addr{LinkIndex: 2, IPNet: &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())}}
	}
	addresses := []netlink.Addr{addr("192.0.2.2/24"), addr("2001:db8:1::2/64"), addr("fe80::2/64")}
	var routes []netlink.Route
	for _, text := range []string{"192.0.2.0/24", "2001:db8:1::/64", "fe80::/64", "10.0.0.0/8"} {
		p := netip.MustParsePrefix(text)
		scope := netlink.SCOPE_LINK
		if p.Addr().Is6() {
			scope = netlink.SCOPE_UNIVERSE
		}
		routes = append(routes, netlink.Route{LinkIndex: 2, Dst: &net.IPNet{IP: p.Addr().AsSlice(), Mask: net.CIDRMask(p.Bits(), p.Addr().BitLen())}, Protocol: unix.RTPROT_KERNEL, Table: unix.RT_TABLE_MAIN, Type: unix.RTN_UNICAST, Scope: scope})
	}
	got := localEnvironment(links, addresses, routes)
	want := policy.Environment{LocalNetworks: []string{"192.0.2.0/24", "2001:db8:1::/64"}, LocalInterfaces: []string{"eth0"}}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("unsafe local derivation: %#v", got)
	}
	routes[0].Protocol = unix.RTPROT_STATIC
	got = localEnvironment(links, addresses, routes)
	if len(got.LocalNetworks) != 1 {
		t.Fatal("static route treated as local network")
	}
}
func TestWireGuardMarkReadbackIsBoundedAndMatchesDevice(t *testing.T) {
	p := fullPlan(t)
	makeMessage := func(index int, mark bool) genetlink.Message {
		a := mdnetlink.NewAttributeEncoder()
		a.Uint32(deviceIndex, uint32(index))
		if mark {
			a.Uint32(7, p.FirewallMark)
		}
		data, _ := a.Encode()
		return genetlink.Message{Header: genetlink.Header{Version: wgVersion}, Data: data}
	}
	got, err := decodeMark(99, []genetlink.Message{makeMessage(99, true)})
	if err != nil || got != p.FirewallMark {
		t.Fatal(got, err)
	}
	for _, messages := range [][]genetlink.Message{nil, {makeMessage(99, false)}, {makeMessage(100, true)}, {makeMessage(99, true), makeMessage(99, true)}, {{Header: genetlink.Header{Version: wgVersion}, Data: []byte{1}}}} {
		if _, err := decodeMark(99, messages); err == nil {
			t.Fatal("invalid mark response accepted")
		}
	}
}
func FuzzNativePolicyRuleDecoder(f *testing.F) {
	f.Add([]byte{})
	f.Add(make([]byte, 12))
	f.Fuzz(func(t *testing.T, data []byte) {
		if len(data) > 1<<20 {
			return
		}
		_, _ = decodePolicyRule(data)
	})
}

func TestInterruptedOrInconsistentPolicyDumpCannotHideForeignRule(t *testing.T) {
	native := baselineRules()
	checked := append([]netlink.Rule(nil), native...)
	for i := range checked {
		checked[i].Type = 0
	}
	if !samePolicyRuleDump(native, checked) {
		t.Fatal("consistent dump rejected")
	}
	if samePolicyRuleDump(native[:len(native)-1], checked) || samePolicyRuleDump(native, checked[:len(checked)-1]) {
		t.Fatal("partial dump accepted")
	}
	checked[0].Priority = 1
	if samePolicyRuleDump(native, checked) {
		t.Fatal("changed selector accepted")
	}
	checked = append([]netlink.Rule(nil), native...)
	for i := range checked {
		checked[i].Type = 0
	}
	checked[1] = checked[0]
	if samePolicyRuleDump(native, checked) {
		t.Fatal("duplicate replaced rule accepted")
	}
}
