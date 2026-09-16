package wgkernel

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"net/netip"
	"slices"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard/policy"
	"github.com/google/nftables"
	"github.com/google/nftables/expr"
	mdnetlink "github.com/mdlayher/netlink"
	"github.com/mdlayher/netlink/nltest"
	"golang.org/x/sys/unix"
)

// This interpreter evaluates the actual compiled nft expressions, independently
// of the pure policy contract. It never opens a socket or executes kernel code.
func evalProgram(t *testing.T, g guardProgram, p policy.Packet) bool {
	t.Helper()
	var rules []*nftables.Rule
	for i, c := range g.chains {
		if c.Name == p.Hook {
			rules = g.rules[i]
		}
	}
	network := make([]byte, 40)
	transport := make([]byte, 8)
	family := byte(unix.NFPROTO_IPV6)
	if p.Destination.Is4() {
		family = unix.NFPROTO_IPV4
		copy(network[16:], p.Destination.AsSlice())
	} else {
		copy(network[24:], p.Destination.AsSlice())
		network[7] = p.HopLimit
	}
	protocol := map[string]byte{"udp": unix.IPPROTO_UDP, "tcp": unix.IPPROTO_TCP, "icmpv6": unix.IPPROTO_ICMPV6}[p.Protocol]
	binary.BigEndian.PutUint16(transport, p.SourcePort)
	binary.BigEndian.PutUint16(transport[2:], p.DestinationPort)
	if protocol == unix.IPPROTO_ICMPV6 {
		transport[0] = p.ICMPv6Type
	}
	for _, r := range rules {
		register := make([]byte, 16)
		matched := true
		for _, raw := range r.Exprs {
			if !matched {
				break
			}
			switch e := raw.(type) {
			case *expr.Meta:
				clear(register)
				switch e.Key {
				case expr.MetaKeyNFPROTO:
					register[0] = family
				case expr.MetaKeyL4PROTO:
					register[0] = protocol
				case expr.MetaKeyMARK:
					binary.NativeEndian.PutUint32(register, p.Mark)
				case expr.MetaKeyOIFNAME:
					copy(register, p.OutputInterface)
				default:
					t.Fatalf("unexpected meta %v", e.Key)
				}
			case *expr.Payload:
				source := network
				if e.Base == expr.PayloadBaseTransportHeader {
					source = transport
				}
				clear(register)
				copy(register, source[e.Offset:e.Offset+e.Len])
			case *expr.Bitwise:
				for i := range int(e.Len) {
					register[i] = (register[i] & e.Mask[i]) ^ e.Xor[i]
				}
			case *expr.Cmp:
				if e.Op != expr.CmpOpEq {
					t.Fatal("unexpected comparison")
				}
				matched = bytes.Equal(register[:len(e.Data)], e.Data)
			case *expr.Verdict:
				return e.Kind == expr.VerdictAccept
			default:
				t.Fatalf("unexpected expression %T", raw)
			}
		}
	}
	return false
}
func TestCompiledFirewallMatchesPolicyForBothFamiliesAndHooks(t *testing.T) {
	p := fullPlan(t)
	g, err := compileGuard(p)
	if err != nil {
		t.Fatal(err)
	}
	cases := 0
	for _, hook := range []string{"output", "forward"} {
		for _, iface := range []string{"lo", "eth0", "other0", p.Owner.InterfaceName} {
			for _, destination := range []string{"198.51.100.50", "203.0.113.50", "192.0.2.10", "255.255.255.255", "2001:db8:1::10", "2001:db8:2::10", "fe80::1", "ff02::1:2", "ff02::1", "ff05::1"} {
				for _, protocol := range []string{"udp", "tcp", "icmpv6"} {
					for _, ports := range [][2]uint16{{12345, 443}, {12345, 51820}, {12345, 53}, {12345, 853}, {68, 67}, {67, 68}, {546, 547}, {547, 546}} {
						for _, mark := range []uint32{0, p.FirewallMark, p.FirewallMark + 1} {
							dest := netip.MustParseAddr(destination)
							source := netip.MustParseAddr("192.0.2.2")
							if dest.Is6() {
								source = netip.MustParseAddr("2001:db8:1::2")
							}
							packet := policy.Packet{Hook: hook, Source: source, Destination: dest, OutputInterface: iface, Protocol: protocol, SourcePort: ports[0], DestinationPort: ports[1], Mark: mark, ICMPv6Type: 135, HopLimit: 255}
							got := evalProgram(t, g, packet)
							want := p.Evaluate(packet).Accept
							if got != want {
								t.Fatalf("compiled policy drift: %#v got %v want %v", packet, got, want)
							}
							cases++
						}
					}
				}
			}
		}
	}
	if cases != 5760 {
		t.Fatal(cases)
	}
	// Wrong hop limits and unrelated multicast ICMPv6 are not NDP allowances.
	for _, kind := range []uint8{128, 129, 132, 133, 134, 135, 136, 137} {
		for _, hop := range []uint8{1, 64, 254, 255} {
			packet := policy.Packet{Hook: "output", Source: netip.MustParseAddr("2001:db8:1::2"), Destination: netip.MustParseAddr("ff02::1"), OutputInterface: "eth0", Protocol: "icmpv6", ICMPv6Type: kind, HopLimit: hop}
			if evalProgram(t, g, packet) != p.Evaluate(packet).Accept {
				t.Fatalf("NDP drift: %v %v", kind, hop)
			}
		}
	}
}
func captureGuard(t *testing.T, g guardProgram) []mdnetlink.Message {
	t.Helper()
	var result []mdnetlink.Message
	stop := errors.New("stop at fake transport")
	c, err := nftables.New(nftables.WithTestDial(func(messages []mdnetlink.Message) ([]mdnetlink.Message, error) {
		result = append(result, messages...)
		return nil, stop
	}))
	if err != nil {
		t.Fatal(err)
	}
	queueGuard(c, g)
	if err = c.Flush(); !errors.Is(err, stop) {
		t.Fatal(err)
	}
	return result
}
func TestGuardIsOneExclusiveAtomicNativeBatch(t *testing.T) {
	g, err := compileGuard(fullPlan(t))
	if err != nil {
		t.Fatal(err)
	}
	messages := captureGuard(t, g)
	if len(messages) < 5 || messages[0].Header.Type != unix.NFNL_MSG_BATCH_BEGIN || messages[len(messages)-1].Header.Type != unix.NFNL_MSG_BATCH_END {
		t.Fatal("missing atomic boundaries")
	}
	table := messages[1]
	if int(table.Header.Type)&0xff != unix.NFT_MSG_NEWTABLE || table.Header.Flags&mdnetlink.Excl == 0 {
		t.Fatal("non-exclusive table creation")
	}
	for _, m := range messages[1 : len(messages)-1] {
		switch int(m.Header.Type) & 0xff {
		case unix.NFT_MSG_NEWTABLE, unix.NFT_MSG_NEWCHAIN, unix.NFT_MSG_NEWRULE:
		default:
			t.Fatalf("unexpected mutation: %v", m.Header.Type)
		}
	}
	for _, c := range g.chains {
		if c.Policy == nil || *c.Policy != nftables.ChainPolicyDrop || c.Table.Family != nftables.TableFamilyINet {
			t.Fatal("non-drop or single-stack guard")
		}
	}
}
func attrString(data []byte, kind uint16) string {
	attrs, _ := mdnetlink.UnmarshalAttributes(data)
	for _, a := range attrs {
		if a.Type == kind {
			return strings.TrimRight(string(a.Data), "\x00")
		}
	}
	return ""
}
func readbackFake(t *testing.T, captured []mdnetlink.Message) *nftables.Conn {
	t.Helper()
	c, err := nftables.New(nftables.WithTestDial(func(request []mdnetlink.Message) ([]mdnetlink.Message, error) {
		if len(request) == 0 {
			return nil, io.EOF
		}
		req := request[0]
		kind := int(req.Header.Type) & 0xff
		var response []mdnetlink.Message
		expected := -1
		switch kind {
		case unix.NFT_MSG_GETTABLE:
			expected = unix.NFT_MSG_NEWTABLE
		case unix.NFT_MSG_GETCHAIN:
			expected = unix.NFT_MSG_NEWCHAIN
		case unix.NFT_MSG_GETRULE:
			expected = unix.NFT_MSG_NEWRULE
		case unix.NFT_MSG_GETSET, unix.NFT_MSG_GETOBJ, nftables.NFT_MSG_GETFLOWTABLE:
			return nil, io.EOF
		default:
			t.Fatalf("unexpected query %v", kind)
		}
		for _, m := range captured {
			if int(m.Header.Type)&0xff != expected {
				continue
			}
			if kind == unix.NFT_MSG_GETRULE && attrString(req.Data[4:], unix.NFTA_RULE_CHAIN) != attrString(m.Data[4:], unix.NFTA_RULE_CHAIN) {
				continue
			}
			m.Header.Sequence = req.Header.Sequence
			m.Header.PID = req.Header.PID
			m.Header.Flags = 0
			response = append(response, m)
		}
		if len(response) == 0 {
			return nil, io.EOF
		}
		response = append(response, mdnetlink.Message{Header: mdnetlink.Header{Sequence: req.Header.Sequence, PID: req.Header.PID}})
		return nltest.Multipart(response)
	}))
	if err != nil {
		t.Fatal(err)
	}
	return c
}
func TestGuardReadbackParsesNativeExpressionsAndRejectsTampering(t *testing.T) {
	p := fullPlan(t)
	g, err := compileGuard(p)
	if err != nil {
		t.Fatal(err)
	}
	captured := captureGuard(t, g)
	exists, verified, err := guardReadback(readbackFake(t, captured), p)
	if err != nil || !exists || !verified {
		t.Fatalf("native roundtrip failed: %v %v %v", exists, verified, err)
	}
	for _, change := range []string{"missing-rule", "foreign-marker", "accept-policy", "foreign-chain", "rule-order"} {
		t.Run(change, func(t *testing.T) {
			bad, _ := compileGuard(p)
			switch change {
			case "missing-rule":
				bad.rules[0] = bad.rules[0][1:]
			case "foreign-marker":
				bad.rules[0][0].UserData = []byte("foreign")
			case "accept-policy":
				accept := nftables.ChainPolicyAccept
				bad.chains[0].Policy = &accept
			case "foreign-chain":
				bad.chains = append(bad.chains, &nftables.Chain{Name: "foreign", Table: bad.table})
				bad.rules = append(bad.rules, nil)
			case "rule-order":
				slices.Reverse(bad.rules[0])
			}
			exists, verified, err := guardReadback(readbackFake(t, captureGuard(t, bad)), p)
			if err != nil || !exists || verified {
				t.Fatalf("tamper accepted: %v %v %v", exists, verified, err)
			}
		})
	}
}

func TestGuardDeletionBindsGenerationAndNeverFlushes(t *testing.T) {
	p := fullPlan(t)
	batch, err := guardDeleteBatch(p, 0x01020304)
	if err != nil {
		t.Fatal(err)
	}
	if len(batch) != 3 || batch[0].Header.Type != unix.NFNL_MSG_BATCH_BEGIN || batch[2].Header.Type != unix.NFNL_MSG_BATCH_END {
		t.Fatal("non-atomic delete")
	}
	attrs, err := mdnetlink.UnmarshalAttributes(batch[0].Data[4:])
	if err != nil || len(attrs) != 1 || attrs[0].Type != unix.NFNL_BATCH_GENID || !bytes.Equal(attrs[0].Data, []byte{1, 2, 3, 4}) {
		t.Fatal("missing network-order generation", err)
	}
	if batch[1].Data[0] != unix.NFPROTO_INET || attrString(batch[1].Data[4:], unix.NFTA_TABLE_NAME) != p.NFTTable {
		t.Fatal("unscoped delete")
	}
	for _, errno := range []int{0, int(unix.ERESTART)} {
		c := nltest.Dial(func(request []mdnetlink.Message) ([]mdnetlink.Message, error) {
			return nltest.Error(errno, request[1:2])
		})
		err := deleteGuardGeneration(c, p, 5)
		c.Close()
		if errno == 0 && err != nil {
			t.Fatal(err)
		}
		if errno != 0 && err == nil {
			t.Fatal("generation conflict hidden")
		}
	}
}
func TestGenerationReadbackValidatesNativeMessage(t *testing.T) {
	for _, test := range []struct {
		name                                 string
		wrongType, missing, duplicate, short bool
	}{{name: "valid"}, {name: "wrong-type", wrongType: true}, {name: "missing", missing: true}, {name: "duplicate", duplicate: true}, {name: "short", short: true}} {
		t.Run(test.name, func(t *testing.T) {
			c := nltest.Dial(func(request []mdnetlink.Message) ([]mdnetlink.Message, error) {
				a := mdnetlink.NewAttributeEncoder()
				a.ByteOrder = binary.BigEndian
				if !test.missing {
					a.Uint32(unix.NFTA_GEN_ID, 123)
				}
				if test.duplicate {
					a.Uint32(unix.NFTA_GEN_ID, 124)
				}
				attrs, _ := a.Encode()
				data := append([]byte{unix.NFPROTO_UNSPEC, 0, 0, 0}, attrs...)
				if test.short {
					data = []byte{1}
				}
				header := request[0].Header
				header.Type = mdnetlink.HeaderType(unix.NFNL_SUBSYS_NFTABLES<<8 | unix.NFT_MSG_NEWGEN)
				if test.wrongType {
					header.Type++
				}
				return []mdnetlink.Message{{Header: header, Data: data}}, nil
			})
			defer c.Close()
			value, err := nftGeneration(c)
			if test.name == "valid" {
				if err != nil || value != 123 {
					t.Fatal(value, err)
				}
			} else if err == nil {
				t.Fatal("malformed generation accepted")
			}
		})
	}
}
