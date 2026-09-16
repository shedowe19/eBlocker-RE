package wgkernel

import (
	"errors"
	"net"
	"slices"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/vishvananda/netlink"
)

// Model the RTM_NEWLINK behavior of kernels that accept but ignore IFLA_IFALIAS.
// The higher-level fakeKernel cannot catch this native ownership regression.
type fakeLinkCreator struct {
	item           netlink.Link
	operations     []string
	createErr      error
	aliasErr       error
	aliasOnCreate  bool
	aliasBeforeErr bool
	onAlias        func(netlink.Link)
}

func (f *fakeLinkCreator) LinkAdd(item netlink.Link) error {
	f.operations = append(f.operations, "create")
	if f.createErr != nil {
		return f.createErr
	}
	item.Attrs().Index = 19
	attrs := *item.Attrs()
	if !f.aliasOnCreate {
		attrs.Alias = ""
	}
	f.item = &netlink.Wireguard{LinkAttrs: attrs}
	return nil
}
func (f *fakeLinkCreator) LinkByIndex(index int) (netlink.Link, error) {
	if f.item == nil || f.item.Attrs().Index != index {
		return nil, errNotFound
	}
	return f.item, nil
}
func (f *fakeLinkCreator) LinkSetAlias(item netlink.Link, alias string) error {
	f.operations = append(f.operations, "alias")
	if f.aliasErr == nil || f.aliasBeforeErr {
		f.item.Attrs().Alias = alias
	}
	if f.onAlias != nil {
		f.onAlias(f.item)
	}
	return f.aliasErr
}
func (f *fakeLinkCreator) LinkDel(item netlink.Link) error {
	f.operations = append(f.operations, "delete")
	f.item = nil
	return nil
}

func TestNativeCreationSetsOwnershipWhenKernelIgnoresCreateAlias(t *testing.T) {
	for _, aliasOnCreate := range []bool{false, true} {
		f := &fakeLinkCreator{aliasOnCreate: aliasOnCreate}
		got, err := createOwnedLink(f, testTarget.InterfaceName, ownerPrefix+testTarget.OwnershipID, 1420)
		if err != nil || !owned(testTarget, got) || got.up {
			t.Fatalf("created link is not owned: %#v, %v", got, err)
		}
		want := []string{"create", "alias"}
		if aliasOnCreate {
			want = want[:1]
		}
		if !slices.Equal(f.operations, want) {
			t.Fatalf("unexpected native creation operations: %v", f.operations)
		}
		if f.item.Attrs().MTU != 1420 || f.item.Attrs().TxQLen != -1 {
			t.Fatal("MTU or kernel-default queue length changed")
		}
	}
}

func TestNativeCreationCleansOnlyItsOwnUnmarkedLinkOnAliasFailure(t *testing.T) {
	failure := errors.New("alias operation failed")
	for _, appliedBeforeError := range []bool{false, true} {
		f := &fakeLinkCreator{aliasErr: failure, aliasBeforeErr: appliedBeforeError}
		_, err := createOwnedLink(f, testTarget.InterfaceName, ownerPrefix+testTarget.OwnershipID, 1420)
		if !errors.Is(err, failure) || f.item != nil || !slices.Equal(f.operations, []string{"create", "alias", "delete"}) {
			t.Fatalf("failed creation leaked its new interface: %v %v", err, f.operations)
		}
	}
	for name, mutate := range map[string]func(netlink.Link){
		"foreign-alias": func(item netlink.Link) { item.Attrs().Alias = "foreign" },
		"renamed":       func(item netlink.Link) { item.Attrs().Name = "foreign0" },
		"up":            func(item netlink.Link) { item.Attrs().Flags |= net.FlagUp },
		"index":         func(item netlink.Link) { item.Attrs().Index++ },
	} {
		t.Run(name, func(t *testing.T) {
			f := &fakeLinkCreator{aliasErr: failure, onAlias: mutate}
			_, err := createOwnedLink(f, testTarget.InterfaceName, ownerPrefix+testTarget.OwnershipID, 1420)
			if !errors.Is(err, failure) || f.item == nil || slices.Contains(f.operations, "delete") {
				t.Fatalf("foreign link was cleaned after a race: %v %v", err, f.operations)
			}
		})
	}
}

func TestNativeCreationDoesNotAdoptExistingLinkOrTrustAliasAcknowledgement(t *testing.T) {
	failure := errors.New("exclusive create failed")
	f := &fakeLinkCreator{createErr: failure, item: &netlink.Wireguard{}}
	if _, err := createOwnedLink(f, testTarget.InterfaceName, ownerPrefix+testTarget.OwnershipID, 1420); !errors.Is(err, failure) || !slices.Equal(f.operations, []string{"create"}) {
		t.Fatalf("existing link was adopted: %v %v", err, f.operations)
	}
	f = &fakeLinkCreator{onAlias: func(item netlink.Link) { item.Attrs().Alias = "foreign" }}
	if _, err := createOwnedLink(f, testTarget.InterfaceName, ownerPrefix+testTarget.OwnershipID, 1420); !errors.Is(err, manager.ErrOwnershipMismatch) || slices.Contains(f.operations, "delete") {
		t.Fatalf("foreign marker accepted or removed after acknowledgement: %v %v", err, f.operations)
	}
}
