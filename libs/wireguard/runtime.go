package wireguard

import (
	"errors"
	"fmt"
	"io"
)

// RuntimeProfile is a short-lived secret view for a trusted native backend.
// It must not be retained outside WithRuntime. Key methods return copies that
// the backend must clear as soon as its kernel request has completed.
type RuntimeProfile struct{ state *profileState }

var ErrProfileDestroyed = errors.New("wireguard profile is unavailable or destroyed")

func (RuntimeProfile) String() string   { return "wireguard.RuntimeProfile[REDACTED]" }
func (RuntimeProfile) GoString() string { return "wireguard.RuntimeProfile[REDACTED]" }
func (RuntimeProfile) Format(w fmt.State, _ rune) {
	_, _ = io.WriteString(w, "wireguard.RuntimeProfile[REDACTED]")
}
func (RuntimeProfile) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }
func (r RuntimeProfile) Plan() Plan                 { return (Profile{state: r.state}).Plan() }

func (r RuntimeProfile) PrivateKeyBytes() [32]byte {
	if r.state == nil {
		return [32]byte{}
	}
	r.state.mu.Lock()
	defer r.state.mu.Unlock()
	return r.state.private
}

func (r RuntimeProfile) PresharedKeyBytes(index int) ([32]byte, bool) {
	if r.state == nil {
		return [32]byte{}, false
	}
	r.state.mu.Lock()
	defer r.state.mu.Unlock()
	if index < 0 || index >= len(r.state.preshared) {
		return [32]byte{}, false
	}
	key := r.state.preshared[index]
	return key, key != [32]byte{}
}

// WithRuntime lends an isolated secret snapshot to a trusted callback. The
// snapshot is zeroed on return, including error/panic unwinding. Calling Destroy
// on the original profile cannot invalidate an in-progress backend operation.
func (p Profile) WithRuntime(callback func(RuntimeProfile) error) error {
	if p.state == nil || callback == nil {
		return ErrProfileDestroyed
	}
	p.state.mu.Lock()
	if p.state.private == [32]byte{} {
		p.state.mu.Unlock()
		return ErrProfileDestroyed
	}
	copy := &Profile{state: &profileState{private: p.state.private, preshared: append([][32]byte(nil), p.state.preshared...), plan: p.state.plan}}
	p.state.mu.Unlock()
	defer copy.Destroy()
	return callback(RuntimeProfile{state: copy.state})
}
