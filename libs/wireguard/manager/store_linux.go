package manager

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"syscall"
)

var profileID = regexp.MustCompile(`^[a-z][a-z0-9-]{0,31}$`)
var ownershipID = regexp.MustCompile(`^[a-f0-9]{32}$`)

type journal struct {
	Version int    `json:"version"`
	Status  Status `json:"status"`
}
type store struct {
	directory string
	lock      *os.File
}

func openStore(directory string) (*store, error) {
	if !filepath.IsAbs(directory) || filepath.Clean(directory) != directory {
		return nil, failure("storage_failed")
	}
	if err := os.MkdirAll(directory, 0700); err != nil {
		return nil, failure("storage_failed")
	}
	canonical, err := filepath.EvalSymlinks(directory)
	if err != nil || canonical != directory {
		return nil, failure("storage_failed")
	}
	for path := directory; ; path = filepath.Dir(path) {
		info, err := os.Lstat(path)
		if err != nil || !info.IsDir() {
			return nil, failure("storage_failed")
		}
		stat, ok := info.Sys().(*syscall.Stat_t)
		if !ok || (stat.Uid != uint32(os.Geteuid()) && stat.Uid != 0) {
			return nil, failure("storage_failed")
		}
		if path == directory {
			if info.Mode().Perm() != 0700 || stat.Uid != uint32(os.Geteuid()) {
				return nil, failure("storage_failed")
			}
		} else if info.Mode().Perm()&0022 != 0 && info.Mode()&os.ModeSticky == 0 {
			return nil, failure("storage_failed")
		}
		if path == string(filepath.Separator) {
			break
		}
	}
	s := &store{directory: directory}
	lock, err := os.OpenFile(filepath.Join(directory, ".lock"), os.O_RDWR|os.O_CREATE|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return nil, failure("storage_failed")
	}
	if err := privateFile(lock); err != nil {
		_ = lock.Close()
		return nil, failure("storage_failed")
	}
	if err := syscall.Flock(int(lock.Fd()), syscall.LOCK_EX|syscall.LOCK_NB); err != nil {
		_ = lock.Close()
		return nil, failure("storage_failed")
	}
	s.lock = lock
	// Persist newly created directory entries too, before a journal can authorize
	// kernel mutation. Syncing only a file does not make its new parent durable.
	for path := directory; ; path = filepath.Dir(path) {
		parent, err := os.Open(path)
		if err != nil {
			_ = lock.Close()
			return nil, failure("storage_failed")
		}
		err = parent.Sync()
		_ = parent.Close()
		if err != nil {
			_ = lock.Close()
			return nil, failure("storage_failed")
		}
		if path == string(filepath.Separator) {
			break
		}
	}
	return s, nil
}

func privateFile(file *os.File) error {
	info, err := file.Stat()
	if err != nil {
		return err
	}
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !info.Mode().IsRegular() || info.Mode().Perm() != 0600 || !ok || stat.Uid != uint32(os.Geteuid()) || stat.Nlink != 1 {
		return failure("storage_failed")
	}
	return nil
}

func (s *store) read(name string, maximum int64) ([]byte, error) {
	file, err := os.OpenFile(filepath.Join(s.directory, name), os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	if err := privateFile(file); err != nil {
		return nil, err
	}
	data, err := io.ReadAll(io.LimitReader(file, maximum+1))
	if err != nil || int64(len(data)) > maximum {
		clear(data)
		return nil, failure("storage_failed")
	}
	return data, nil
}

func randomID() (string, error) {
	var token [16]byte
	if _, err := rand.Read(token[:]); err != nil {
		return "", err
	}
	return hex.EncodeToString(token[:]), nil
}

func (s *store) write(name string, data []byte) error {
	previous, err := os.OpenFile(filepath.Join(s.directory, name), os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if err == nil {
		err = privateFile(previous)
		_ = previous.Close()
	}
	if err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	id, err := randomID()
	if err != nil {
		return err
	}
	path := filepath.Join(s.directory, ".tmp-"+id)
	file, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL|syscall.O_NOFOLLOW, 0600)
	if err != nil {
		return err
	}
	defer os.Remove(path)
	written, writeErr := file.Write(data)
	if writeErr == nil && written != len(data) {
		writeErr = io.ErrShortWrite
	}
	if writeErr == nil {
		writeErr = file.Sync()
	}
	closeErr := file.Close()
	if writeErr != nil {
		return writeErr
	}
	if closeErr != nil {
		return closeErr
	}
	if err := os.Rename(path, filepath.Join(s.directory, name)); err != nil {
		return err
	}
	return s.sync()
}

func (s *store) sync() error {
	directory, err := os.Open(s.directory)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

func (s *store) remove(name string) error {
	// Do not unlink symlinks, hard links or foreign/wrong-mode files even inside
	// the private state directory. A missing file is already removed.
	file, err := os.OpenFile(filepath.Join(s.directory, name), os.O_RDONLY|syscall.O_NOFOLLOW, 0)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return err
	}
	err = privateFile(file)
	_ = file.Close()
	if err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(s.directory, name)); err != nil {
		return err
	}
	return s.sync()
}

func (s *store) save(status Status) error {
	data, err := json.Marshal(journal{Version: 1, Status: status})
	if err != nil {
		return err
	}
	return s.write(status.Target.ProfileID+".json", append(data, '\n'))
}

func (s *store) load(id string) (Status, error) {
	data, err := s.read(id+".json", 512<<10)
	if err != nil {
		return Status{}, err
	}
	var record journal
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&record); err != nil {
		return Status{}, failure("storage_failed")
	}
	var extra any
	if !errors.Is(decoder.Decode(&extra), io.EOF) {
		return Status{}, failure("storage_failed")
	}
	target := record.Status.Target
	if record.Version != 1 || record.Status.SchemaVersion != 1 || target.ProfileID != id || !profileID.MatchString(id) || !ownershipID.MatchString(target.OwnershipID) || target.InterfaceName != "ebwg"+target.OwnershipID[:10] {
		return Status{}, failure("storage_failed")
	}
	switch record.Status.Phase {
	case PhasePrepared, PhaseApplying, PhaseActive, PhaseRemoving, PhaseRollingBack, PhaseDisconnected, PhaseFailed, PhaseCancelled, PhaseRecoveryRequired:
	default:
		return Status{}, failure("storage_failed")
	}
	if fullTunnel(record.Status.Plan) != (record.Status.Policy != nil) {
		return Status{}, failure("storage_failed")
	}
	if record.Status.Policy != nil && record.Status.Policy.Validate(target.PolicyOwner(), record.Status.Plan) != nil {
		return Status{}, failure("storage_failed")
	}
	// Persisted observations are historical; only a fresh native readback may
	// assert that a kill switch is currently active.
	clearProtection(&record.Status)
	return record.Status, nil
}

func (s *store) ids() ([]string, error) {
	entries, err := os.ReadDir(s.directory)
	if err != nil {
		return nil, err
	}
	var ids []string
	for _, entry := range entries {
		if strings.HasSuffix(entry.Name(), ".json") {
			id := strings.TrimSuffix(entry.Name(), ".json")
			if !profileID.MatchString(id) {
				return nil, failure("storage_failed")
			}
			ids = append(ids, id)
		}
	}
	return ids, nil
}

func (s *store) cleanOrphans() error {
	entries, err := os.ReadDir(s.directory)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		name := entry.Name()
		if strings.HasPrefix(name, ".tmp-") {
			if err := s.remove(name); err != nil {
				return err
			}
		} else if strings.HasSuffix(name, ".conf") {
			id := strings.TrimSuffix(name, ".conf")
			if !profileID.MatchString(id) {
				return failure("storage_failed")
			}
			if _, err := os.Lstat(filepath.Join(s.directory, id+".json")); errors.Is(err, os.ErrNotExist) {
				if err := s.remove(name); err != nil {
					return err
				}
			}
		}
	}
	return nil
}
