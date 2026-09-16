package server

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"
)

// Listen refuses existing files, sockets and symlinks, including stale sockets.
// Automatically unlinking a stale pathname can delete a concurrently started
// service. The operator must verify and remove a stale socket before restarting.
func Listen(path string) (*Listener, error) {
	if !filepath.IsAbs(path) || filepath.Clean(path) != path || len(path) > 103 {
		return nil, errors.New("socket path must be absolute, clean and at most 103 bytes")
	}
	parentPath := filepath.Dir(path)
	canonicalParent, err := filepath.EvalSymlinks(parentPath)
	if err != nil || canonicalParent != parentPath {
		return nil, errors.New("socket directory must exist without symlink components")
	}
	parent, err := os.Lstat(parentPath)
	if err != nil {
		return nil, fmt.Errorf("inspect socket directory: %w", err)
	}
	stat, ok := parent.Sys().(*syscall.Stat_t)
	if !parent.IsDir() || parent.Mode()&0022 != 0 || !ok || (stat.Uid != uint32(os.Geteuid()) && stat.Uid != 0) {
		return nil, errors.New("socket directory must be owned by this user or root, must not be a symlink, and must not be group/world writable")
	}
	for ancestor := filepath.Dir(parentPath); ; ancestor = filepath.Dir(ancestor) {
		info, err := os.Lstat(ancestor)
		if err != nil {
			return nil, fmt.Errorf("inspect socket path ancestor: %w", err)
		}
		owner, ok := info.Sys().(*syscall.Stat_t)
		// Sticky root-owned /tmp is safe for a child directory owned by this
		// process; another user cannot rename that child. Non-sticky writable
		// ancestors could allow replacement of the checked parent directory.
		if !info.IsDir() || !ok || (owner.Uid != 0 && owner.Uid != uint32(os.Geteuid())) ||
			(info.Mode()&0022 != 0 && info.Mode()&os.ModeSticky == 0) {
			return nil, errors.New("socket path has an untrusted or writable ancestor")
		}
		if ancestor == string(filepath.Separator) {
			break
		}
	}
	if _, err := os.Lstat(path); err == nil {
		return nil, errors.New("socket path already exists; refusing to replace it")
	} else if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	listener, err := net.ListenUnix("unix", &net.UnixAddr{Name: path, Net: "unix"})
	if err != nil {
		return nil, err
	}
	listener.SetUnlinkOnClose(false)
	info, err := os.Lstat(path)
	if err != nil {
		_ = listener.Close()
		return nil, err
	}
	result := &Listener{UnixListener: listener, path: path, info: info}
	if err := os.Chmod(path, 0660); err != nil {
		_ = result.Close()
		return nil, err
	}
	return result, nil
}

type Listener struct {
	*net.UnixListener
	path string
	info os.FileInfo
	once sync.Once
	err  error
}

func (l *Listener) Close() error {
	l.once.Do(func() {
		l.err = l.UnixListener.Close()
		current, err := os.Lstat(l.path)
		if err == nil && os.SameFile(l.info, current) {
			if removeErr := os.Remove(l.path); removeErr != nil && l.err == nil {
				l.err = removeErr
			}
		} else if err != nil && !errors.Is(err, os.ErrNotExist) && l.err == nil {
			l.err = err
		}
	})
	return l.err
}

func Serve(ctx context.Context, listener *Listener, handler http.Handler) error {
	server := &http.Server{Handler: handler, ReadHeaderTimeout: 2 * time.Second, ReadTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 << 10, BaseContext: func(net.Listener) context.Context { return ctx }}
	result := make(chan error, 1)
	go func() { result <- server.Serve(listener) }()
	var err error
	select {
	case err = <-result:
	case <-ctx.Done():
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if shutdownErr := server.Shutdown(shutdownCtx); shutdownErr != nil {
			_ = server.Close()
		}
		err = <-result
	}
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}
