package network

import (
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"syscall"
	"time"
)

const maxNetlinkResponse = 4 << 20

// dump performs read-only requests with bounded receive time and memory. A dump
// interrupted by a changing kernel table is an error, never a partial snapshot.
func dump(ctx context.Context, protocol int, messageType uint16, payload []byte) ([]syscall.NetlinkMessage, error) {
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	fd, err := syscall.Socket(syscall.AF_NETLINK, syscall.SOCK_RAW|syscall.SOCK_CLOEXEC, protocol)
	if err != nil {
		return nil, err
	}
	defer syscall.Close(fd)
	if err := syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_RCVTIMEO, &syscall.Timeval{Usec: 100000}); err != nil {
		return nil, err
	}
	if err := syscall.SetsockoptTimeval(fd, syscall.SOL_SOCKET, syscall.SO_SNDTIMEO, &syscall.Timeval{Usec: 100000}); err != nil {
		return nil, err
	}
	if err := syscall.Bind(fd, &syscall.SockaddrNetlink{Family: syscall.AF_NETLINK}); err != nil {
		return nil, err
	}
	local, err := syscall.Getsockname(fd)
	if err != nil {
		return nil, err
	}
	pid := local.(*syscall.SockaddrNetlink).Pid
	request := make([]byte, syscall.NLMSG_HDRLEN+len(payload))
	binary.NativeEndian.PutUint32(request, uint32(len(request)))
	binary.NativeEndian.PutUint16(request[4:], messageType)
	binary.NativeEndian.PutUint16(request[6:], syscall.NLM_F_REQUEST|syscall.NLM_F_DUMP)
	binary.NativeEndian.PutUint32(request[8:], 1)
	copy(request[syscall.NLMSG_HDRLEN:], payload)
	if err := syscall.Sendto(fd, request, 0, &syscall.SockaddrNetlink{Family: syscall.AF_NETLINK}); err != nil {
		return nil, err
	}
	var result []syscall.NetlinkMessage
	buffer := make([]byte, 1<<16)
	total := 0
	for {
		if err := ctx.Err(); err != nil {
			return nil, err
		}
		n, _, flags, from, err := syscall.Recvmsg(fd, buffer, nil, 0)
		if errors.Is(err, syscall.EINTR) || errors.Is(err, syscall.EAGAIN) {
			continue
		}
		if err != nil {
			return nil, err
		}
		peer, ok := from.(*syscall.SockaddrNetlink)
		if !ok || peer.Pid != 0 {
			return nil, errors.New("unexpected netlink sender")
		}
		if flags&syscall.MSG_TRUNC != 0 || n < syscall.NLMSG_HDRLEN {
			return nil, errors.New("truncated netlink response")
		}
		total += n
		if total > maxNetlinkResponse {
			return nil, errors.New("netlink response exceeds limit")
		}
		// Parser data refers to its buffer, so retain an independent datagram.
		messages, err := syscall.ParseNetlinkMessage(append([]byte(nil), buffer[:n]...))
		if err != nil {
			return nil, err
		}
		for _, msg := range messages {
			if msg.Header.Seq != 1 || msg.Header.Pid != pid {
				return nil, errors.New("unexpected netlink response identity")
			}
			if msg.Header.Flags&0x10 != 0 {
				return nil, errors.New("netlink dump interrupted; retry observation")
			}
			if msg.Header.Type == syscall.NLMSG_ERROR || msg.Header.Type == syscall.NLMSG_DONE {
				if msg.Header.Type == syscall.NLMSG_ERROR && len(msg.Data) < 4 {
					return nil, errors.New("truncated netlink error")
				}
				if len(msg.Data) >= 4 {
					code := int32(binary.NativeEndian.Uint32(msg.Data))
					if code < 0 {
						return nil, syscall.Errno(-code)
					}
					if code > 0 {
						return nil, errors.New("invalid netlink error")
					}
				}
				if msg.Header.Type == syscall.NLMSG_DONE {
					return result, nil
				}
				continue
			}
			if msg.Header.Type == syscall.NLMSG_OVERRUN {
				return nil, errors.New("netlink dump overrun")
			}
			result = append(result, msg)
		}
	}
}

type attribute struct {
	kind  uint16
	value []byte
}

func attributes(data []byte) ([]attribute, error) {
	var result []attribute
	for len(data) != 0 {
		if len(data) < 4 {
			return nil, errors.New("truncated netlink attribute")
		}
		length := int(binary.NativeEndian.Uint16(data))
		aligned := (length + 3) &^ 3
		if length < 4 || length > len(data) || aligned > len(data) {
			return nil, errors.New("invalid netlink attribute length")
		}
		result = append(result, attribute{binary.NativeEndian.Uint16(data[2:]) & 0x3fff, data[4:length]})
		data = data[aligned:]
	}
	return result, nil
}

func uint32Attribute(value []byte) (uint32, error) {
	if len(value) != 4 {
		return 0, fmt.Errorf("invalid netlink integer length %d", len(value))
	}
	return binary.NativeEndian.Uint32(value), nil
}
