// Package wireguard validates a deliberately restricted wg-quick configuration
// and produces a public, read-only plan. It never applies network configuration.
package wireguard

import (
	"fmt"
	"io"
	"slices"
	"sync"
)

const (
	MaxConfigBytes = 64 * 1024
	MaxPeers       = 128
	MaxPrefixes    = 1024
	MaxAddresses   = 64
	MaxDNS         = 16
)

// ValidationError contains only fixed messages and recognized field names.
// In particular, unknown field names and user-supplied values are never echoed.
type ValidationError struct {
	Code    string `json:"code"`
	Line    int    `json:"line,omitempty"`
	Field   string `json:"field,omitempty"`
	Message string `json:"message"`
}

func (e *ValidationError) Error() string {
	return fmt.Sprintf("wireguard: %s (line %d, field %s): %s", e.Code, e.Line, e.Field, e.Message)
}

func invalid(code string, line int, field, message string) error {
	return &ValidationError{Code: code, Line: line, Field: field, Message: message}
}

// Plan is safe to serialize: private and preshared keys are never included.
// RequiredCapabilities describes a future privileged apply operation; validation
// itself requires none. Applied and KillSwitchActive are always false.
type Plan struct {
	InterfaceAddresses   []string   `json:"interfaceAddresses"`
	DNS                  []string   `json:"dns"`
	ListenPort           *uint16    `json:"listenPort,omitempty"`
	MTU                  *uint16    `json:"mtu,omitempty"`
	Peers                []PeerPlan `json:"peers"`
	DefaultRouteIPv4     bool       `json:"defaultRouteIPv4"`
	DefaultRouteIPv6     bool       `json:"defaultRouteIPv6"`
	EndpointExclusions   []string   `json:"endpointExclusions"`
	RequiredCapabilities []string   `json:"requiredCapabilities"`
	LeakRisks            []string   `json:"leakRisks"`
	Warnings             []string   `json:"warnings"`
	Applied              bool       `json:"applied"`
	KillSwitchActive     bool       `json:"killSwitchActive"`
}

type PeerPlan struct {
	PublicKey           string   `json:"publicKey"`
	AllowedIPs          []string `json:"allowedIPs"`
	Endpoint            string   `json:"endpoint,omitempty"`
	PersistentKeepalive *uint16  `json:"persistentKeepalive,omitempty"`
	HasPresharedKey     bool     `json:"hasPresharedKey"`
}

// Profile owns parsed secrets. Treat the original input as sensitive too: the
// caller must clear its input buffer. Destroy clears retained key bytes, but Go
// does not guarantee elimination of temporary copies or protected process memory.
// Copies of Profile share the same secret lifetime; Plan returns an independent
// deep copy and can be used concurrently with Destroy.
type Profile struct {
	state *profileState
}

type profileState struct {
	mu        sync.Mutex
	private   [32]byte
	preshared [][32]byte
	plan      Plan
}

func (Profile) String() string               { return "wireguard.Profile[REDACTED]" }
func (Profile) GoString() string             { return "wireguard.Profile[REDACTED]" }
func (Profile) Format(w fmt.State, _ rune)   { _, _ = io.WriteString(w, "wireguard.Profile[REDACTED]") }
func (Profile) MarshalJSON() ([]byte, error) { return []byte(`{"redacted":true}`), nil }

// Plan returns public metadata only. A zero Profile returns a zero Plan.
func (p Profile) Plan() Plan {
	if p.state == nil {
		return Plan{}
	}
	// plan is immutable once Parse returns; Destroy touches only secret fields.
	result := p.state.plan
	result.InterfaceAddresses = slices.Clone(result.InterfaceAddresses)
	result.DNS = slices.Clone(result.DNS)
	result.EndpointExclusions = slices.Clone(result.EndpointExclusions)
	result.RequiredCapabilities = slices.Clone(result.RequiredCapabilities)
	result.LeakRisks = slices.Clone(result.LeakRisks)
	result.Warnings = slices.Clone(result.Warnings)
	result.ListenPort = cloneNumber(result.ListenPort)
	result.MTU = cloneNumber(result.MTU)
	result.Peers = slices.Clone(result.Peers)
	for i := range result.Peers {
		result.Peers[i].AllowedIPs = slices.Clone(result.Peers[i].AllowedIPs)
		result.Peers[i].PersistentKeepalive = cloneNumber(result.Peers[i].PersistentKeepalive)
	}
	return result
}

func cloneNumber(number *uint16) *uint16 {
	if number == nil {
		return nil
	}
	result := *number
	return &result
}

// Destroy is safe to call repeatedly and concurrently. It does not erase the
// caller's configuration, temporary runtime copies, or the public plan.
func (p Profile) Destroy() {
	if p.state == nil {
		return
	}
	p.state.mu.Lock()
	defer p.state.mu.Unlock()
	clear(p.state.private[:])
	for i := range p.state.preshared {
		clear(p.state.preshared[i][:])
	}
}
