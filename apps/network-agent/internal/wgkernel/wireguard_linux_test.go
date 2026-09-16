package wgkernel

import (
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"errors"
	"net/netip"
	"strings"
	"testing"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/mdlayher/genetlink"
	"github.com/mdlayher/netlink"
	"golang.org/x/sys/unix"
)

func TestWireGuardEncodingHasExactKernelABI(t *testing.T) {
	err := withProfile(t, runtimeConfig(), func(profile wireguard.RuntimeProfile) error {
		data, err := encodeDevice(19, profile)
		if err != nil {
			return err
		}
		defer clear(data)
		attrs, err := uapiAttributes(data)
		if err != nil {
			return err
		}
		values := map[uint16][]byte{}
		for _, attr := range attrs {
			values[attr.kind] = attr.data
		}
		if binary.NativeEndian.Uint32(values[deviceIndex]) != 19 || base64.StdEncoding.EncodeToString(values[devicePrivateKey]) != keyOf(17) || binary.NativeEndian.Uint32(values[deviceFlags]) != 1 || binary.NativeEndian.Uint16(values[deviceListenPort]) != 51820 {
			t.Fatal("invalid device attributes")
		}
		peers, err := uapiAttributes(values[devicePeers])
		if err != nil {
			return err
		}
		if len(peers) != 1 {
			t.Fatal("invalid peer count")
		}
		peer, err := uapiAttributes(peers[0].data)
		if err != nil {
			return err
		}
		values = map[uint16][]byte{}
		for _, attr := range peer {
			values[attr.kind] = attr.data
		}
		if base64.StdEncoding.EncodeToString(values[peerPublicKey]) != publicOf(83) || base64.StdEncoding.EncodeToString(values[peerPresharedKey]) != keyOf(35) || binary.NativeEndian.Uint32(values[peerFlags]) != 2 || binary.NativeEndian.Uint16(values[peerKeepalive]) != 25 {
			t.Fatal("invalid peer attributes")
		}
		if endpoint := values[peerEndpoint]; len(endpoint) != 16 || binary.NativeEndian.Uint16(endpoint) != unix.AF_INET || binary.BigEndian.Uint16(endpoint[2:]) != 51820 || netip.AddrFrom4([4]byte(endpoint[4:8])).String() != "198.51.100.50" {
			t.Fatal("invalid IPv4 sockaddr")
		}
		prefixes, err := uapiAttributes(values[peerAllowedIPs])
		if err != nil {
			return err
		}
		if len(prefixes) != 2 {
			t.Fatal("IPv4/IPv6 allowed prefixes missing")
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	endpoint := encodeEndpoint(netip.MustParseAddrPort("[2001:db8::abc]:65535"))
	if len(endpoint) != 28 || binary.NativeEndian.Uint16(endpoint) != unix.AF_INET6 || binary.BigEndian.Uint16(endpoint[2:]) != 65535 || netip.AddrFrom16([16]byte(endpoint[8:24])).String() != "2001:db8::abc" {
		t.Fatal("invalid IPv6 sockaddr")
	}
}

func observationMessage(t *testing.T, includeCounters bool) genetlink.Message {
	t.Helper()
	device := netlink.NewAttributeEncoder()
	device.Uint32(deviceIndex, 19)
	private, _ := base64.StdEncoding.DecodeString(keyOf(17))
	device.Bytes(devicePrivateKey, private)
	peer := netlink.NewAttributeEncoder()
	public, _ := base64.StdEncoding.DecodeString(publicOf(83))
	peer.Bytes(peerPublicKey, public)
	psk, _ := base64.StdEncoding.DecodeString(keyOf(35))
	peer.Bytes(peerPresharedKey, psk)
	if includeCounters {
		handshake := make([]byte, 16)
		binary.NativeEndian.PutUint64(handshake, 1234567890)
		binary.NativeEndian.PutUint64(handshake[8:], 987654321)
		peer.Bytes(peerHandshake, handshake)
		peer.Uint64(peerRx, 100)
		peer.Uint64(peerTx, 200)
	}
	peerData, err := peer.Encode()
	if err != nil {
		t.Fatal(err)
	}
	peers := netlink.NewAttributeEncoder()
	peers.Bytes(1|netlink.Nested, peerData)
	peersData, err := peers.Encode()
	if err != nil {
		t.Fatal(err)
	}
	device.Bytes(devicePeers|netlink.Nested, peersData)
	data, err := device.Encode()
	if err != nil {
		t.Fatal(err)
	}
	return genetlink.Message{Header: genetlink.Header{Command: wgGetDevice, Version: wgVersion}, Data: data}
}

func TestObservationMergesFragmentsAndNeverExportsSecrets(t *testing.T) {
	peers, err := decodePeers(19, []genetlink.Message{observationMessage(t, true), observationMessage(t, false)})
	if err != nil {
		t.Fatal(err)
	}
	if len(peers) != 1 || peers[0].LastHandshakeUnix != 1234567890 || peers[0].ReceiveBytes != 100 || peers[0].TransmitBytes != 200 {
		t.Fatal("multipart observation lost counters")
	}
	data, err := json.Marshal(peers)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(string(data), keyOf(17)) || strings.Contains(string(data), keyOf(35)) || strings.Contains(string(data), "private") || strings.Contains(string(data), "preshared") {
		t.Fatal("kernel key material was exported")
	}
}

func TestMalformedKernelResponsesRejected(t *testing.T) {
	message := observationMessage(t, true)
	if _, err := decodePeers(20, []genetlink.Message{message}); err == nil {
		t.Fatal("wrong device index accepted")
	}
	message.Header.Version = 2
	if _, err := decodePeers(19, []genetlink.Message{message}); err == nil {
		t.Fatal("unknown protocol version accepted")
	}
	for _, data := range [][]byte{nil, {1, 2, 3}, {3, 0, 1, 0}, {255, 255, 1, 0}} {
		message := genetlink.Message{Header: genetlink.Header{Command: wgGetDevice, Version: wgVersion}, Data: data}
		if _, err := decodePeers(19, []genetlink.Message{message}); err == nil {
			t.Fatalf("malformed response accepted: %x", data)
		}
	}
}

func TestRawNetlinkDebuggingBlocksSecretTransport(t *testing.T) {
	t.Setenv("NLDEBUG", "1")
	connection, _, err := wireGuardHandle(t.Context())
	if connection != nil || !errors.Is(err, manager.ErrUnavailable) {
		t.Fatal("raw netlink debugging must block transport before any socket is opened")
	}
}

func FuzzWireGuardKernelResponse(f *testing.F) {
	f.Add([]byte{8, 0, 1, 0, 19, 0, 0, 0})
	f.Add([]byte{4, 0, 8, 128})
	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = decodePeers(19, []genetlink.Message{{Header: genetlink.Header{Command: wgGetDevice, Version: wgVersion}, Data: data}})
	})
}
