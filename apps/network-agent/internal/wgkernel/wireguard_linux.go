package wgkernel

import (
	"encoding/base64"
	"encoding/binary"
	"errors"
	"net/netip"
	"sort"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
	"github.com/mdlayher/genetlink"
	"github.com/mdlayher/netlink"
	"golang.org/x/sys/unix"
)

// Linux WireGuard UAPI: include/uapi/linux/wireguard.h. These attributes are
// encoded directly to avoid any userspace WireGuard fallback or shell protocol.
const (
	wgGetDevice      = 0
	wgSetDevice      = 1
	wgVersion        = 1
	deviceIndex      = 1
	devicePrivateKey = 3
	deviceFlags      = 5
	deviceListenPort = 6
	devicePeers      = 8
	peerPublicKey    = 1
	peerPresharedKey = 2
	peerFlags        = 3
	peerEndpoint     = 4
	peerKeepalive    = 5
	peerHandshake    = 6
	peerRx           = 7
	peerTx           = 8
	peerAllowedIPs   = 9
	ipFamily         = 1
	ipAddress        = 2
	ipMask           = 3
	maxDeviceMessage = 60 << 10
)

var errMalformedDevice = errors.New("invalid WireGuard kernel response")

func encodeDevice(index int, config wireguard.RuntimeProfile) ([]byte, error) {
	key := config.PrivateKeyBytes()
	defer clear(key[:])
	if key == [32]byte{} {
		return nil, manager.ErrUnsupportedProfile
	}
	plan := config.Plan()
	device := netlink.NewAttributeEncoder()
	device.Uint32(deviceIndex, uint32(index))
	device.Bytes(devicePrivateKey, key[:])
	device.Uint32(deviceFlags, 1) // WGDEVICE_F_REPLACE_PEERS on our new device only.
	if plan.ListenPort != nil {
		device.Uint16(deviceListenPort, *plan.ListenPort)
	}
	peers := netlink.NewAttributeEncoder()
	for i, peer := range plan.Peers {
		encoded, err := encodePeer(i, peer, config)
		if err != nil {
			return nil, err
		}
		defer clear(encoded)
		peers.Bytes(uint16(i+1)|netlink.Nested, encoded)
	}
	encodedPeers, err := peers.Encode()
	if err != nil {
		return nil, manager.ErrUnsupportedProfile
	}
	defer clear(encodedPeers)
	device.Bytes(devicePeers|netlink.Nested, encodedPeers)
	encoded, err := device.Encode()
	if err != nil || len(encoded) > maxDeviceMessage {
		clear(encoded)
		return nil, manager.ErrUnsupportedProfile
	}
	return encoded, nil
}

func encodePeer(index int, plan wireguard.PeerPlan, config wireguard.RuntimeProfile) ([]byte, error) {
	peer := netlink.NewAttributeEncoder()
	key, err := base64.StdEncoding.DecodeString(plan.PublicKey)
	if err != nil || len(key) != 32 {
		return nil, manager.ErrUnsupportedProfile
	}
	peer.Bytes(peerPublicKey, key)
	psk, present := config.PresharedKeyBytes(index)
	defer clear(psk[:])
	if present {
		peer.Bytes(peerPresharedKey, psk[:])
	}
	peer.Uint32(peerFlags, 2) // WGPEER_F_REPLACE_ALLOWEDIPS.
	if plan.Endpoint != "" {
		endpoint, err := netip.ParseAddrPort(plan.Endpoint)
		if err != nil {
			return nil, manager.ErrUnsupportedProfile
		}
		peer.Bytes(peerEndpoint, encodeEndpoint(endpoint))
	}
	if plan.PersistentKeepalive != nil {
		peer.Uint16(peerKeepalive, *plan.PersistentKeepalive)
	}
	allowed := netlink.NewAttributeEncoder()
	for i, text := range plan.AllowedIPs {
		prefix, err := netip.ParsePrefix(text)
		if err != nil {
			return nil, manager.ErrUnsupportedProfile
		}
		item := netlink.NewAttributeEncoder()
		family := uint16(unix.AF_INET6)
		if prefix.Addr().Is4() {
			family = unix.AF_INET
		}
		item.Uint16(ipFamily, family)
		item.Bytes(ipAddress, prefix.Masked().Addr().AsSlice())
		item.Uint8(ipMask, uint8(prefix.Bits()))
		encoded, err := item.Encode()
		if err != nil {
			return nil, manager.ErrUnsupportedProfile
		}
		allowed.Bytes(uint16(i+1)|netlink.Nested, encoded)
	}
	encodedAllowed, err := allowed.Encode()
	if err != nil {
		return nil, manager.ErrUnsupportedProfile
	}
	peer.Bytes(peerAllowedIPs|netlink.Nested, encodedAllowed)
	encoded, err := peer.Encode()
	if err != nil {
		return nil, manager.ErrUnsupportedProfile
	}
	return encoded, nil
}

func encodeEndpoint(endpoint netip.AddrPort) []byte {
	if endpoint.Addr().Is4() {
		result := make([]byte, 16)
		binary.NativeEndian.PutUint16(result, unix.AF_INET)
		binary.BigEndian.PutUint16(result[2:], endpoint.Port())
		copy(result[4:8], endpoint.Addr().AsSlice())
		return result
	}
	result := make([]byte, 28)
	binary.NativeEndian.PutUint16(result, unix.AF_INET6)
	binary.BigEndian.PutUint16(result[2:], endpoint.Port())
	copy(result[8:24], endpoint.Addr().AsSlice())
	return result
}

type uapiAttribute struct {
	kind uint16
	data []byte
}

// Borrow the response slices without copying private-key attributes. The caller
// clears the complete response after extracting public counters and timestamps.
func uapiAttributes(data []byte) ([]uapiAttribute, error) {
	var result []uapiAttribute
	for len(data) > 0 {
		if len(data) < 4 {
			return nil, errMalformedDevice
		}
		length := int(binary.NativeEndian.Uint16(data))
		aligned := (length + 3) &^ 3
		if length < 4 || aligned > len(data) {
			return nil, errMalformedDevice
		}
		result = append(result, uapiAttribute{binary.NativeEndian.Uint16(data[2:]) & 0x3fff, data[4:length]})
		data = data[aligned:]
	}
	return result, nil
}

func decodePeers(index int, messages []genetlink.Message) ([]manager.PeerObservation, error) {
	if len(messages) == 0 {
		return nil, errMalformedDevice
	}
	peers := map[string]manager.PeerObservation{}
	total := 0
	for messageIndex, message := range messages {
		total += len(message.Data)
		if total > 4<<20 || message.Header.Command != wgGetDevice || message.Header.Version != wgVersion {
			return nil, errMalformedDevice
		}
		attrs, err := uapiAttributes(message.Data)
		if err != nil {
			return nil, err
		}
		foundIndex := false
		for _, attr := range attrs {
			switch attr.kind {
			case deviceIndex:
				if len(attr.data) != 4 || binary.NativeEndian.Uint32(attr.data) != uint32(index) {
					return nil, errMalformedDevice
				}
				foundIndex = true
			case devicePeers:
				items, err := uapiAttributes(attr.data)
				if err != nil {
					return nil, err
				}
				for _, item := range items {
					if err := mergePeer(peers, item.data); err != nil {
						return nil, err
					}
				}
			}
		}
		// Linux emits device-level attributes only in the initial fragment.
		// Subsequent messages continue that device's peers/AllowedIPs. Require
		// the first fragment to identify our device, and reject any conflicting
		// index above if a later fragment supplies one.
		if messageIndex == 0 && !foundIndex {
			return nil, errMalformedDevice
		}
	}
	result := make([]manager.PeerObservation, 0, len(peers))
	for _, peer := range peers {
		result = append(result, peer)
	}
	sort.Slice(result, func(i, j int) bool { return result[i].PublicKey < result[j].PublicKey })
	return result, nil
}

func mergePeer(peers map[string]manager.PeerObservation, data []byte) error {
	attrs, err := uapiAttributes(data)
	if err != nil {
		return err
	}
	key := ""
	for _, attr := range attrs {
		if attr.kind == peerPublicKey {
			if len(attr.data) != 32 || key != "" {
				return errMalformedDevice
			}
			key = base64.StdEncoding.EncodeToString(attr.data)
		}
	}
	if key == "" {
		return errMalformedDevice
	}
	peer := peers[key]
	peer.PublicKey = key
	for _, attr := range attrs {
		switch attr.kind {
		case peerHandshake:
			if len(attr.data) != 16 {
				return errMalformedDevice
			}
			seconds := int64(binary.NativeEndian.Uint64(attr.data))
			nanos := int64(binary.NativeEndian.Uint64(attr.data[8:]))
			if seconds < 0 || nanos < 0 || nanos >= 1e9 {
				return errMalformedDevice
			}
			peer.LastHandshakeUnix = seconds
		case peerRx, peerTx:
			if len(attr.data) != 8 {
				return errMalformedDevice
			}
			value := binary.NativeEndian.Uint64(attr.data)
			if attr.kind == peerRx {
				peer.ReceiveBytes = value
			} else {
				peer.TransmitBytes = value
			}
		}
	}
	peers[key] = peer
	if len(peers) > wireguard.MaxPeers {
		return errMalformedDevice
	}
	return nil
}
