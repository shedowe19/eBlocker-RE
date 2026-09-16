package wireguard

import (
	"encoding/json"
	"errors"
	"fmt"
	"testing"
)

func TestRuntimeSecretLeaseIsRedactedAndExpires(t *testing.T) {
	profile := mustParse(t, config("10.0.0.0/8", "198.51.100.1:51820"))
	var retained RuntimeProfile
	if err := profile.WithRuntime(func(runtime RuntimeProfile) error {
		retained = runtime
		private := runtime.PrivateKeyBytes()
		if private == [32]byte{} {
			t.Fatal("missing private key")
		}
		clear(private[:])
		psk, ok := runtime.PresharedKeyBytes(0)
		if !ok || psk == [32]byte{} {
			t.Fatal("missing PSK")
		}
		clear(psk[:])
		data, _ := json.Marshal(runtime)
		if string(data) != `{"redacted":true}` || fmt.Sprintf("%#v", runtime) != "wireguard.RuntimeProfile[REDACTED]" {
			t.Fatal("runtime secrets are not redacted")
		}
		profile.Destroy() // Leased snapshot must remain usable during this callback.
		if runtime.PrivateKeyBytes() == [32]byte{} {
			t.Fatal("original destruction invalidated snapshot")
		}
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	if retained.PrivateKeyBytes() != [32]byte{} {
		t.Fatal("secret lease survived callback")
	}
	if _, ok := retained.PresharedKeyBytes(0); ok {
		t.Fatal("PSK lease survived callback")
	}
	if !errors.Is(profile.WithRuntime(func(RuntimeProfile) error { return nil }), ErrProfileDestroyed) {
		t.Fatal("destroyed profile leased secrets")
	}
}

func TestRuntimeLeaseIsClearedWhenCallbackPanics(t *testing.T) {
	profile := mustParse(t, config("10.0.0.0/8", "198.51.100.1:51820"))
	var retained RuntimeProfile
	func() {
		defer func() { _ = recover() }()
		_ = profile.WithRuntime(func(runtime RuntimeProfile) error { retained = runtime; panic("test interruption") })
	}()
	if retained.PrivateKeyBytes() != [32]byte{} {
		t.Fatal("panic retained runtime secret")
	}
}
