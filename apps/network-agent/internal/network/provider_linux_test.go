package network

import (
	"context"
	"encoding/binary"
	"net/netip"
	"os"
	"syscall"
	"testing"
)

func attr(kind uint16, value []byte) []byte {
	b := make([]byte, (len(value)+4+3)&^3)
	binary.NativeEndian.PutUint16(b, uint16(len(value)+4))
	binary.NativeEndian.PutUint16(b[2:], kind)
	copy(b[4:], value)
	return b
}
func integer(value uint32) []byte {
	b := make([]byte, 4)
	binary.NativeEndian.PutUint32(b, value)
	return b
}

func TestRouteParsingIPv4AndIPv6(t *testing.T) {
	for _, test := range []struct {
		family               byte
		destination, gateway string
		prefix               uint8
		want                 string
	}{
		{syscall.AF_INET, "10.0.0.9", "10.0.0.1", 24, "10.0.0.0/24"},
		{syscall.AF_INET6, "::", "fe80::1", 0, "::/0"},
	} {
		data := make([]byte, 12)
		data[0] = test.family
		data[1] = test.prefix
		data[4] = 254
		data[5] = 4
		data[7] = 1
		data = append(data, attr(syscall.RTA_DST, netip.MustParseAddr(test.destination).AsSlice())...)
		data = append(data, attr(syscall.RTA_GATEWAY, netip.MustParseAddr(test.gateway).AsSlice())...)
		data = append(data, attr(syscall.RTA_OIF, integer(7))...)
		data = append(data, attr(syscall.RTA_PRIORITY, integer(100))...)
		data = append(data, attr(syscall.RTA_TABLE, integer(1000))...)
		route, err := parseRoute(data)
		if err != nil {
			t.Fatal(err)
		}
		if route.Destination != test.want || route.Gateway != test.gateway || route.InterfaceIndex != 7 || route.Priority != 100 || route.Table != 1000 || route.Protocol != 4 || route.Type != 1 {
			t.Fatalf("bad route: %#v", route)
		}
	}
}

func TestMultipathAndCrossFamilyGateway(t *testing.T) {
	hop := make([]byte, 8)
	hop[3] = 255
	binary.NativeEndian.PutUint32(hop[4:], 8)
	hop = append(hop, attr(syscall.RTA_GATEWAY, netip.MustParseAddr("2001:db8::9").AsSlice())...)
	binary.NativeEndian.PutUint16(hop, uint16(len(hop)))
	data := make([]byte, 12)
	data[0] = syscall.AF_INET6
	data = append(data, attr(syscall.RTA_MULTIPATH, hop)...)
	route, err := parseRoute(data)
	if err != nil {
		t.Fatal(err)
	}
	if len(route.NextHops) != 1 || route.NextHops[0].Weight != 256 || route.NextHops[0].InterfaceIndex != 8 || route.NextHops[0].Gateway != "2001:db8::9" {
		t.Fatalf("bad next hops: %#v", route.NextHops)
	}
	via := []byte{0, 0}
	binary.NativeEndian.PutUint16(via, syscall.AF_INET6)
	via = append(via, netip.MustParseAddr("fe80::a").AsSlice()...)
	data = make([]byte, 12)
	data[0] = syscall.AF_INET
	data = append(data, attr(18, via)...)
	route, err = parseRoute(data)
	if err != nil || route.Gateway != "fe80::a" {
		t.Fatalf("cross-family via: %#v %v", route, err)
	}
}

func TestMalformedRoutesAreRejected(t *testing.T) {
	valid := make([]byte, 12)
	valid[0] = syscall.AF_INET6
	badPrefix := append([]byte(nil), valid...)
	badPrefix[1] = 129
	for _, data := range [][]byte{nil, {1, 2, 3}, badPrefix, append(append([]byte(nil), valid...), 1), append(append([]byte(nil), valid...), attr(syscall.RTA_GATEWAY, []byte{1, 2, 3})...), append(append([]byte(nil), valid...), attr(syscall.RTA_TABLE, []byte{1})...), append(append([]byte(nil), valid...), attr(syscall.RTA_MULTIPATH, []byte{255, 255, 0, 0, 0, 0, 0, 0})...)} {
		if _, err := parseRoute(data); err == nil {
			t.Fatalf("malformed route accepted: %x", data)
		}
	}
}

func TestCancelledObservations(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	provider := LinuxProvider{}
	if _, err := provider.Interfaces(ctx); err == nil {
		t.Fatal("interfaces ignored cancellation")
	}
	if _, err := provider.Routes(ctx); err == nil {
		t.Fatal("routes ignored cancellation")
	}
	if _, err := provider.WireGuard(ctx); err == nil {
		t.Fatal("capabilities ignored cancellation")
	}
}

// This deliberately exercises the actual read-only kernel path. No fake is used
// in production; environments without Netlink must report the limitation.
func TestLinuxReadOnlyObservation(t *testing.T) {
	if os.Getenv("EBLOCKER_AGENT_INTEGRATION") != "1" {
		t.Skip("set EBLOCKER_AGENT_INTEGRATION=1 for real Linux Netlink observation; sandbox forbids AF_NETLINK")
	}
	provider := LinuxProvider{}
	interfaces, err := provider.Interfaces(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if len(interfaces) == 0 {
		t.Fatal("no interfaces observed")
	}
	if _, err := provider.Routes(t.Context()); err != nil {
		t.Fatal(err)
	}
	wg, err := provider.WireGuard(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if wg.Management {
		t.Fatal("read-only provider exposed management")
	}
}

func FuzzRouteParsing(f *testing.F) {
	f.Add([]byte{syscall.AF_INET6, 0, 0, 0, 254, 4, 0, 1, 0, 0, 0, 0})
	f.Add([]byte{syscall.AF_INET, 24, 0, 0, 254, 4, 0, 1, 0, 0, 0, 0, 8, 0, 1, 0, 10, 0, 0, 0})
	f.Fuzz(func(t *testing.T, data []byte) { _, _ = parseRoute(data) })
}
