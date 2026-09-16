package wgkernel

import (
	"bytes"
	"context"
	"encoding/binary"
	"fmt"
	"net"
	"net/netip"
	"os"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/eblocker/eblocker/libs/wireguard/policy"
	"github.com/google/nftables"
	"github.com/google/nftables/expr"
	mdnetlink "github.com/mdlayher/netlink"
	"golang.org/x/sys/unix"
)

func nftConnection(ctx context.Context) (*nftables.Conn, error) {
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	if debugConfiguredAtStartup || os.Getenv("NLDEBUG") != "" {
		return nil, manager.ErrUnavailable
	}
	// NETLINK_NETFILTER can otherwise request subsystem module loading. The
	// administrator must provision nftables before this runtime is invoked.
	if _, err := os.Stat("/sys/module/nf_tables"); err != nil {
		return nil, manager.ErrUnavailable
	}
	deadline := time.Now().Add(2 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	return nftables.New(nftables.WithSockOptions(func(c *mdnetlink.Conn) error { return c.SetDeadline(deadline) }))
}

type guardProgram struct {
	table  *nftables.Table
	chains []*nftables.Chain
	rules  [][]*nftables.Rule
}

func compileGuard(p policy.Plan) (guardProgram, error) {
	if !p.ValidDigest() || len(p.LocalInterfaces)*len(p.LocalNetworks) > 512 {
		return guardProgram{}, manager.ErrUnsupportedProfile
	}
	table := &nftables.Table{Name: p.NFTTable, Family: nftables.TableFamilyINet}
	g := guardProgram{table: table}
	for _, name := range []string{"output", "forward"} {
		hook := nftables.ChainHookOutput
		if name == "forward" {
			hook = nftables.ChainHookForward
		}
		drop := nftables.ChainPolicyDrop
		chain := &nftables.Chain{Name: name, Table: table, Type: nftables.ChainTypeFilter, Hooknum: hook, Priority: nftables.ChainPriorityFilter, Policy: &drop}
		g.chains = append(g.chains, chain)
		var rules []*nftables.Rule
		add := func(match []expr.Any, verdict expr.VerdictKind) {
			match = append(match, &expr.Verdict{Kind: verdict})
			rules = append(rules, &nftables.Rule{Table: table, Chain: chain, Exprs: match, UserData: []byte(fmt.Sprintf("%s:%s:%d", p.OwnershipMarker(), name, len(rules)))})
		}
		if name == "output" {
			add(matchInterface("lo"), expr.VerdictAccept)
		}
		add(matchInterface(p.Owner.InterfaceName), expr.VerdictAccept)
		if name == "output" {
			for _, endpoint := range p.Endpoints {
				address, err := netip.ParseAddr(endpoint.Address)
				if err != nil {
					return guardProgram{}, manager.ErrUnsupportedProfile
				}
				mark := make([]byte, 4)
				binary.NativeEndian.PutUint32(mark, p.FirewallMark)
				m := matchInterface(endpoint.InterfaceName)
				m = append(m, matchMeta(expr.MetaKeyMARK, mark)...)
				m = append(m, matchPrefix(netip.PrefixFrom(address, address.BitLen()))...)
				m = append(m, matchProtocol(unix.IPPROTO_UDP)...)
				m = append(m, matchPort(2, endpoint.Port)...)
				add(m, expr.VerdictAccept)
			}
		}
		// The encrypted, exactly marked WireGuard UDP tuple is allowed above even
		// when its remote port is 53/853. All other plaintext DNS/DoT is blocked.
		for _, protocol := range []byte{unix.IPPROTO_UDP, unix.IPPROTO_TCP} {
			for _, port := range []uint16{53, 853} {
				m := matchProtocol(protocol)
				m = append(m, matchPort(2, port)...)
				add(m, expr.VerdictDrop)
			}
		}
		for _, name := range p.LocalInterfaces {
			for _, text := range p.LocalNetworks {
				prefix, err := netip.ParsePrefix(text)
				if err != nil {
					return guardProgram{}, manager.ErrUnsupportedProfile
				}
				m := matchInterface(name)
				m = append(m, matchPrefix(prefix)...)
				add(m, expr.VerdictAccept)
			}
		}
		if name == "output" {
			for _, iface := range p.LocalInterfaces {
				// DHCP server and client preserve appliance/LAN setup. These exceptions
				// cannot send arbitrary traffic to a remote routed destination.
				for _, ports := range [][2]uint16{{68, 67}, {67, 68}} {
					m := matchInterface(iface)
					m = append(m, matchPrefix(netip.MustParsePrefix("255.255.255.255/32"))...)
					m = append(m, matchProtocol(unix.IPPROTO_UDP)...)
					m = append(m, matchPort(0, ports[0])...)
					m = append(m, matchPort(2, ports[1])...)
					add(m, expr.VerdictAccept)
				}
				for _, entry := range []struct {
					prefix   string
					src, dst uint16
				}{{"fe80::/10", 546, 547}, {"ff02::1:2/128", 546, 547}, {"fe80::/10", 547, 546}} {
					m := matchInterface(iface)
					m = append(m, matchPrefix(netip.MustParsePrefix(entry.prefix))...)
					m = append(m, matchProtocol(unix.IPPROTO_UDP)...)
					m = append(m, matchPort(0, entry.src)...)
					m = append(m, matchPort(2, entry.dst)...)
					add(m, expr.VerdictAccept)
				}
				for _, prefix := range []string{"fe80::/10", "ff02::/16"} {
					for kind := byte(133); kind <= 136; kind++ {
						m := matchInterface(iface)
						m = append(m, matchPrefix(netip.MustParsePrefix(prefix))...)
						m = append(m, matchProtocol(unix.IPPROTO_ICMPV6)...)
						m = append(m, matchPayload(expr.PayloadBaseNetworkHeader, 7, []byte{255})...)
						m = append(m, matchPayload(expr.PayloadBaseTransportHeader, 0, []byte{kind})...)
						add(m, expr.VerdictAccept)
					}
				}
			}
		}
		g.rules = append(g.rules, rules)
	}
	return g, nil
}
func matchMeta(key expr.MetaKey, data []byte) []expr.Any {
	return []expr.Any{&expr.Meta{Key: key, Register: 1}, &expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: data}}
}
func matchInterface(name string) []expr.Any {
	data := make([]byte, 16)
	copy(data, name)
	return matchMeta(expr.MetaKeyOIFNAME, data)
}
func matchProtocol(protocol byte) []expr.Any { return matchMeta(expr.MetaKeyL4PROTO, []byte{protocol}) }
func matchPayload(base expr.PayloadBase, offset uint32, data []byte) []expr.Any {
	return []expr.Any{&expr.Payload{DestRegister: 1, Base: base, Offset: offset, Len: uint32(len(data))}, &expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: data}}
}
func matchPort(offset uint32, port uint16) []expr.Any {
	data := make([]byte, 2)
	binary.BigEndian.PutUint16(data, port)
	return matchPayload(expr.PayloadBaseTransportHeader, offset, data)
}
func matchPrefix(prefix netip.Prefix) []expr.Any {
	family := byte(unix.NFPROTO_IPV6)
	offset := uint32(24)
	if prefix.Addr().Is4() {
		family = unix.NFPROTO_IPV4
		offset = 16
	}
	network := prefix.Masked().Addr().AsSlice()
	size := uint32(len(network))
	m := matchMeta(expr.MetaKeyNFPROTO, []byte{family})
	m = append(m, &expr.Payload{DestRegister: 1, Base: expr.PayloadBaseNetworkHeader, Offset: offset, Len: size}, &expr.Bitwise{SourceRegister: 1, DestRegister: 1, Len: size, Mask: net.CIDRMask(prefix.Bits(), prefix.Addr().BitLen()), Xor: make([]byte, size)}, &expr.Cmp{Op: expr.CmpOpEq, Register: 1, Data: network})
	return m
}
func queueGuard(c *nftables.Conn, g guardProgram) {
	c.CreateTable(g.table)
	for i, chain := range g.chains {
		c.AddChain(chain)
		for _, rule := range g.rules[i] {
			c.AddRule(rule)
		}
	}
}
func (nativeFullKernel) installGuard(ctx context.Context, p policy.Plan) error {
	g, err := compileGuard(p)
	if err != nil {
		return err
	}
	c, err := nftConnection(ctx)
	if err != nil {
		return err
	}
	queueGuard(c, g)
	return c.Flush()
}

func sameChain(a, b *nftables.Chain) bool {
	return a.Name == b.Name && a.Table.Name == b.Table.Name && a.Table.Family == b.Table.Family && a.Hooknum != nil && b.Hooknum != nil && *a.Hooknum == *b.Hooknum && a.Priority != nil && b.Priority != nil && *a.Priority == *b.Priority && a.Policy != nil && b.Policy != nil && *a.Policy == *b.Policy && a.Type == b.Type && a.Device == b.Device
}
func sameRule(a, b *nftables.Rule) bool {
	if !bytes.Equal(a.UserData, b.UserData) || len(a.Exprs) != len(b.Exprs) {
		return false
	}
	for i, e := range a.Exprs {
		left, err := expr.Marshal(byte(nftables.TableFamilyINet), e)
		if err != nil {
			return false
		}
		right, err := expr.Marshal(byte(nftables.TableFamilyINet), b.Exprs[i])
		if err != nil || !bytes.Equal(left, right) {
			return false
		}
	}
	return true
}
func guardReadback(c *nftables.Conn, p policy.Plan) (bool, bool, error) {
	expected, err := compileGuard(p)
	if err != nil {
		return false, false, err
	}
	tables, err := c.ListTables()
	if err != nil {
		return false, false, err
	}
	var table *nftables.Table
	for _, t := range tables {
		if t.Name == p.NFTTable && t.Family == nftables.TableFamilyINet {
			table = t
		}
	}
	if table == nil {
		return false, false, nil
	}
	if table.Flags != 0 {
		return true, false, nil
	}
	chains, err := c.ListChains()
	if err != nil {
		return true, false, err
	}
	var actual []*nftables.Chain
	for _, chain := range chains {
		if chain.Table.Name == p.NFTTable && chain.Table.Family == nftables.TableFamilyINet {
			actual = append(actual, chain)
		}
	}
	if len(actual) != len(expected.chains) {
		return true, false, nil
	}
	for i, chain := range expected.chains {
		var found *nftables.Chain
		for _, candidate := range actual {
			if candidate.Name == chain.Name {
				found = candidate
			}
		}
		if found == nil || !sameChain(found, chain) {
			return true, false, nil
		}
		rules, err := c.GetRules(table, found)
		if err != nil {
			return true, false, err
		}
		if len(rules) != len(expected.rules[i]) {
			return true, false, nil
		}
		for j, rule := range rules {
			if !sameRule(rule, expected.rules[i][j]) {
				return true, false, nil
			}
		}
	}
	sets, err := c.GetSets(table)
	if err != nil {
		return true, false, err
	}
	objects, err := c.GetObjects(table)
	if err != nil {
		return true, false, err
	}
	flows, err := c.ListFlowtables(table)
	if err != nil {
		return true, false, err
	}
	return true, len(sets) == 0 && len(objects) == 0 && len(flows) == 0, nil
}
func (nativeFullKernel) guard(ctx context.Context, p policy.Plan) (bool, bool, error) {
	c, err := nftConnection(ctx)
	if err != nil {
		return false, false, err
	}
	transport, err := nftNativeTransport(ctx)
	if err != nil {
		return false, false, err
	}
	defer transport.Close()
	before, err := nftGeneration(transport)
	if err != nil {
		return false, false, err
	}
	exists, verified, err := guardReadback(c, p)
	if err != nil {
		return exists, false, err
	}
	after, err := nftGeneration(transport)
	if err != nil {
		return exists, false, err
	}
	if before != after {
		return exists, false, manager.ErrUnavailable
	}
	return exists, verified, nil
}
func (nativeFullKernel) removeGuard(ctx context.Context, p policy.Plan) error {
	c, err := nftConnection(ctx)
	if err != nil {
		return err
	}
	// Bind the readback and deletion to one kernel generation. A concurrent
	// replacement or added foreign rule makes the atomic batch fail instead of
	// deleting a table that merely reuses the same name.
	transport, err := nftNativeTransport(ctx)
	if err != nil {
		return err
	}
	defer transport.Close()
	generation, err := nftGeneration(transport)
	if err != nil {
		return err
	}
	exists, verified, err := guardReadback(c, p)
	if err != nil {
		return err
	}
	if !exists {
		return nil
	}
	if !verified {
		return manager.ErrOwnershipMismatch
	}
	return deleteGuardGeneration(transport, p, generation)
}

func nftNativeTransport(ctx context.Context) (*mdnetlink.Conn, error) {
	if _, err := nftConnection(ctx); err != nil {
		return nil, err
	}
	c, err := mdnetlink.Dial(unix.NETLINK_NETFILTER, nil)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(2 * time.Second)
	if d, ok := ctx.Deadline(); ok && d.Before(deadline) {
		deadline = d
	}
	if err = c.SetDeadline(deadline); err != nil {
		c.Close()
		return nil, err
	}
	return c, nil
}
func nftGeneration(c *mdnetlink.Conn) (uint32, error) {
	messages, err := c.Execute(mdnetlink.Message{Header: mdnetlink.Header{Type: mdnetlink.HeaderType(unix.NFNL_SUBSYS_NFTABLES<<8 | unix.NFT_MSG_GETGEN), Flags: mdnetlink.Request}, Data: []byte{unix.NFPROTO_UNSPEC, 0, 0, 0}})
	if err != nil {
		return 0, err
	}
	if len(messages) != 1 || messages[0].Header.Type != mdnetlink.HeaderType(unix.NFNL_SUBSYS_NFTABLES<<8|unix.NFT_MSG_NEWGEN) || len(messages[0].Data) < 4 {
		return 0, errMalformedDevice
	}
	a, err := mdnetlink.NewAttributeDecoder(messages[0].Data[4:])
	if err != nil {
		return 0, err
	}
	a.ByteOrder = binary.BigEndian
	var generation uint32
	found := false
	for a.Next() {
		if a.Type() == unix.NFTA_GEN_ID {
			if found {
				return 0, errMalformedDevice
			}
			generation = a.Uint32()
			found = true
		}
	}
	if err = a.Err(); err != nil {
		return 0, err
	}
	if !found {
		return 0, errMalformedDevice
	}
	return generation, nil
}
func guardDeleteBatch(p policy.Plan, generation uint32) ([]mdnetlink.Message, error) {
	if !p.ValidDigest() {
		return nil, manager.ErrOwnershipMismatch
	}
	a := mdnetlink.NewAttributeEncoder()
	a.ByteOrder = binary.BigEndian
	a.Uint32(unix.NFNL_BATCH_GENID, generation)
	attrs, err := a.Encode()
	if err != nil {
		return nil, err
	}
	table := mdnetlink.NewAttributeEncoder()
	table.String(unix.NFTA_TABLE_NAME, p.NFTTable)
	tableAttrs, err := table.Encode()
	if err != nil {
		return nil, err
	}
	return []mdnetlink.Message{
		{Header: mdnetlink.Header{Type: unix.NFNL_MSG_BATCH_BEGIN, Flags: mdnetlink.Request}, Data: append([]byte{unix.NFPROTO_UNSPEC, 0, 0, unix.NFNL_SUBSYS_NFTABLES}, attrs...)},
		{Header: mdnetlink.Header{Type: mdnetlink.HeaderType(unix.NFNL_SUBSYS_NFTABLES<<8 | unix.NFT_MSG_DELTABLE), Flags: mdnetlink.Request | mdnetlink.Acknowledge}, Data: append([]byte{unix.NFPROTO_INET, 0, 0, 0}, tableAttrs...)},
		{Header: mdnetlink.Header{Type: unix.NFNL_MSG_BATCH_END, Flags: mdnetlink.Request}, Data: []byte{unix.NFPROTO_UNSPEC, 0, 0, unix.NFNL_SUBSYS_NFTABLES}},
	}, nil
}
func deleteGuardGeneration(c *mdnetlink.Conn, p policy.Plan, generation uint32) error {
	batch, err := guardDeleteBatch(p, generation)
	if err != nil {
		return err
	}
	sent, err := c.SendMessages(batch)
	if err != nil {
		return err
	}
	replies, err := c.Receive()
	if err != nil {
		return err
	}
	// One operation, one successful ACK. Error ACKs (including a changed
	// generation) are already surfaced by netlink.Receive.
	if len(replies) != 1 || replies[0].Header.Type != mdnetlink.Error || replies[0].Header.Sequence != sent[1].Header.Sequence || len(replies[0].Data) < 4 || binary.NativeEndian.Uint32(replies[0].Data) != 0 {
		return errMalformedDevice
	}
	return nil
}
