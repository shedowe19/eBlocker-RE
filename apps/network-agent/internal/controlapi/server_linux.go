package controlapi

import (
	"context"
	"errors"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"syscall"
	"time"

	"github.com/eblocker/eblocker/apps/network-agent/internal/server"
)

type Identity struct {
	UID uint32
	GID uint32
}
type peerKey struct{}
type peerConn struct {
	net.Conn
	identity Identity
}

func credentials(connection *net.UnixConn) (Identity, error) {
	raw, err := connection.SyscallConn()
	if err != nil {
		return Identity{}, err
	}
	var peer *syscall.Ucred
	var socketErr error
	if err := raw.Control(func(fd uintptr) {
		peer, socketErr = syscall.GetsockoptUcred(int(fd), syscall.SOL_SOCKET, syscall.SO_PEERCRED)
	}); err != nil {
		return Identity{}, err
	}
	if socketErr != nil || peer == nil || peer.Pid <= 0 {
		return Identity{}, errors.New("peer credentials unavailable")
	}
	return Identity{UID: peer.Uid, GID: peer.Gid}, nil
}

type Listener struct {
	*server.Listener
	identity Identity
}

// Listen keeps the existing listener's no-stale-unlink and safe-ancestor checks,
// then adds private directory and exact socket UID/GID/mode requirements.
func Listen(path string, identity Identity) (*Listener, error) {
	info, err := os.Lstat(filepath.Dir(path))
	if err != nil {
		return nil, errors.New("private socket directory unavailable")
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok || !info.IsDir() || (info.Mode().Perm() != 0750 && info.Mode().Perm() != 0700) || stat.Uid != uint32(os.Geteuid()) || stat.Gid != uint32(os.Getegid()) {
		return nil, errors.New("private socket directory ownership or mode mismatch")
	}
	l, err := server.Listen(path)
	if err != nil {
		return nil, errors.New("private socket unavailable")
	}
	info, err = os.Lstat(path)
	if err == nil {
		stat, ok = info.Sys().(*syscall.Stat_t)
	}
	if err != nil || !ok || info.Mode()&os.ModeSocket == 0 || info.Mode().Perm() != 0660 || stat.Uid != uint32(os.Geteuid()) || stat.Gid != uint32(os.Getegid()) {
		_ = l.Close()
		return nil, errors.New("private socket ownership or mode mismatch")
	}
	return &Listener{Listener: l, identity: identity}, nil
}

func (l *Listener) Accept() (net.Conn, error) {
	for {
		connection, err := l.AcceptUnix()
		if err != nil {
			return nil, err
		}
		peer, err := credentials(connection)
		if err != nil || peer != l.identity {
			_ = connection.Close()
			continue
		}
		return &peerConn{Conn: connection, identity: peer}, nil
	}
}

func Serve(ctx context.Context, l *Listener, h *Handler) error {
	service := &http.Server{Handler: h, ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 40 * time.Second, IdleTimeout: 15 * time.Second, MaxHeaderBytes: 8 << 10,
		ErrorLog:    log.New(io.Discard, "", 0), // net/http errors must not print request/configuration fragments.
		BaseContext: func(net.Listener) context.Context { return ctx },
		ConnContext: func(ctx context.Context, connection net.Conn) context.Context {
			if peer, ok := connection.(*peerConn); ok {
				return context.WithValue(ctx, peerKey{}, peer.identity)
			}
			return ctx
		},
	}
	result := make(chan error, 1)
	go func() { result <- service.Serve(l) }()
	var err error
	select {
	case err = <-result:
	case <-ctx.Done():
		shutdown, cancel := context.WithTimeout(context.Background(), 6*time.Second)
		defer cancel()
		if service.Shutdown(shutdown) != nil {
			_ = service.Close()
		}
		err = <-result
	}
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
