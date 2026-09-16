// Package controlapi exposes an explicitly enabled, local-only WireGuard
// management service. Authentication comes exclusively from Unix SO_PEERCRED.
package controlapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/eblocker/eblocker/libs/wireguard"
	"github.com/eblocker/eblocker/libs/wireguard/manager"
)

const MaxRequestBytes = 384 << 10
const MaxResponseBytes = 512 << 10
const OperationTimeout = 30 * time.Second

type Lifecycle interface {
	ImportProfile(context.Context, string, []byte) (manager.StoredProfile, error)
	Profiles(context.Context) ([]manager.StoredProfile, error)
	Profile(context.Context, string) (manager.StoredProfile, error)
	ConnectProfile(context.Context, string) (manager.Status, error)
	Status(context.Context, string) (manager.Status, error)
	Disconnect(context.Context, string) (manager.Status, error)
	DeleteProfile(context.Context, string) error
	Cancel(string) bool
}

type ProfileStatus struct {
	ProfileID string          `json:"profileId"`
	Phase     string          `json:"phase"`
	Plan      wireguard.Plan  `json:"plan"`
	Runtime   *manager.Status `json:"runtime"`
}

type Handler struct {
	lifecycle Lifecycle
	identity  Identity
	gate      chan struct{}
	slots     chan struct{}
	timeout   time.Duration
}

func New(lifecycle Lifecycle, identity Identity) *Handler {
	if lifecycle == nil {
		panic("control lifecycle is required")
	}
	return &Handler{lifecycle: lifecycle, identity: identity, gate: make(chan struct{}, 1), slots: make(chan struct{}, 16), timeout: OperationTimeout}
}

var profilePattern = regexp.MustCompile(`^[a-z][a-z0-9-]{0,31}$`)

type envelope struct {
	SchemaVersion int          `json:"schemaVersion"`
	Data          any          `json:"data,omitempty"`
	Error         *publicError `json:"error,omitempty"`
}
type publicError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	peer, ok := r.Context().Value(peerKey{}).(Identity)
	if !ok || peer != h.identity {
		fail(w, "forbidden")
		return
	}
	select {
	case h.slots <- struct{}{}:
		defer func() { <-h.slots }()
	default:
		fail(w, "busy")
		return
	}
	if r.URL.RawQuery != "" || r.URL.RawPath != "" || r.URL.Fragment != "" || r.Header.Get("Content-Encoding") != "" && r.Header.Get("Content-Encoding") != "identity" {
		fail(w, "invalid_request")
		return
	}
	parts := strings.Split(strings.TrimPrefix(r.URL.Path, "/v1/profiles"), "/")
	if !strings.HasPrefix(r.URL.Path, "/v1/profiles") || len(parts) > 3 || parts[0] != "" {
		fail(w, "not_found")
		return
	}
	id, action := "", ""
	if len(parts) > 1 {
		id = parts[1]
		if !profilePattern.MatchString(id) {
			fail(w, "invalid_id")
			return
		}
	}
	if len(parts) > 2 {
		action = parts[2]
		if action != "connect" && action != "disconnect" && action != "cancel" {
			fail(w, "not_found")
			return
		}
	}
	wanted := http.MethodGet
	if action != "" {
		wanted = http.MethodPost
	} else if id != "" {
		wanted = "GET, PUT, DELETE"
	}
	if !strings.Contains(","+strings.ReplaceAll(wanted, " ", "")+",", ","+r.Method+",") {
		w.Header().Set("Allow", wanted)
		fail(w, "method_not_allowed")
		return
	}
	var configuration []byte
	if r.Method == http.MethodPut || r.Method == http.MethodPost {
		var code string
		configuration, code = decodeRequest(w, r, r.Method == http.MethodPut)
		if code != "" {
			fail(w, code)
			return
		}
		defer clear(configuration)
	} else {
		var b [1]byte
		n, err := io.ReadFull(r.Body, b[:])
		if n != 0 || err != nil && !errors.Is(err, io.EOF) {
			fail(w, "invalid_request")
			return
		}
	}
	if action == "cancel" {
		respond(w, struct {
			ProfileID string `json:"profileId"`
			Requested bool   `json:"cancellationRequested"`
		}{id, h.lifecycle.Cancel(id)})
		return
	}
	select {
	case h.gate <- struct{}{}:
		defer func() { <-h.gate }()
	default:
		fail(w, "busy")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), h.timeout)
	defer cancel()
	var data any
	var err error
	switch {
	case id == "":
		var profiles []manager.StoredProfile
		profiles, err = h.lifecycle.Profiles(ctx)
		data = struct {
			Profiles []manager.StoredProfile `json:"profiles"`
		}{profiles}
	case r.Method == http.MethodPut:
		data, err = h.lifecycle.ImportProfile(ctx, id, configuration)
	case r.Method == http.MethodDelete:
		err = h.lifecycle.DeleteProfile(ctx, id)
		data = struct {
			ProfileID string `json:"profileId"`
			Deleted   bool   `json:"deleted"`
		}{id, true}
	default:
		var profile manager.StoredProfile
		profile, err = h.lifecycle.Profile(ctx, id)
		if err == nil {
			result := ProfileStatus{ProfileID: id, Phase: profile.Phase, Plan: profile.Plan}
			if action == "connect" || profile.Phase != "imported" {
				var runtime manager.Status
				switch action {
				case "connect":
					runtime, err = h.lifecycle.ConnectProfile(ctx, id)
				case "disconnect":
					runtime, err = h.lifecycle.Disconnect(ctx, id)
				default:
					runtime, err = h.lifecycle.Status(ctx, id)
				}
				result.Runtime = &runtime
				result.Phase = runtime.Phase
			}
			data = result
		}
	}
	if err != nil {
		code := "response_unavailable"
		var detail *manager.Error
		if errors.As(err, &detail) {
			if _, ok := errorsByCode[detail.Code]; ok {
				code = detail.Code
			}
		}
		if errors.Is(ctx.Err(), context.DeadlineExceeded) {
			code = "timeout"
		}
		fail(w, code)
		return
	}
	respond(w, data)
}

func decodeRequest(w http.ResponseWriter, r *http.Request, importing bool) ([]byte, string) {
	media, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || media != "application/json" {
		return nil, "invalid_content_type"
	}
	r.Body = http.MaxBytesReader(w, r.Body, MaxRequestBytes)
	d := json.NewDecoder(r.Body)
	start, err := d.Token()
	if err != nil || start != json.Delim('{') {
		return nil, decodeCode(err)
	}
	seen := map[string]bool{}
	version := 0
	var configuration string
	for d.More() {
		token, err := d.Token()
		if err != nil {
			return nil, decodeCode(err)
		}
		key, ok := token.(string)
		if !ok || seen[key] {
			return nil, "invalid_json"
		}
		seen[key] = true
		switch key {
		case "schemaVersion":
			err = d.Decode(&version)
		case "configuration":
			if !importing {
				return nil, "invalid_json"
			}
			var text *string
			err = d.Decode(&text)
			if err == nil {
				if text == nil {
					return nil, "invalid_json"
				}
				configuration = *text
				*text = ""
			}
		default:
			return nil, "invalid_json"
		}
		if err != nil {
			return nil, decodeCode(err)
		}
	}
	closing, err := d.Token()
	if err != nil || closing != json.Delim('}') {
		return nil, decodeCode(err)
	}
	var extra any
	if err := d.Decode(&extra); !errors.Is(err, io.EOF) {
		return nil, decodeCode(err)
	}
	if !seen["schemaVersion"] || version != 1 {
		return nil, "invalid_schema"
	}
	if importing && !seen["configuration"] {
		return nil, "invalid_json"
	}
	if len(configuration) > wireguard.MaxConfigBytes {
		return nil, "profile_too_large"
	}
	result := []byte(configuration)
	configuration = ""
	return result, ""
}
func decodeCode(err error) string {
	var tooLarge *http.MaxBytesError
	if errors.As(err, &tooLarge) {
		return "request_too_large"
	}
	return "invalid_json"
}
func fail(w http.ResponseWriter, code string) {
	definition, ok := errorsByCode[code]
	if !ok {
		code = "response_unavailable"
		definition = errorsByCode[code]
	}
	write(w, definition.Status, envelope{SchemaVersion: 1, Error: &publicError{Code: code, Message: definition.Message}})
}
func respond(w http.ResponseWriter, data any) {
	write(w, http.StatusOK, envelope{SchemaVersion: 1, Data: data})
}
func write(w http.ResponseWriter, status int, value envelope) {
	data, err := json.Marshal(value)
	if err != nil || len(data)+1 > MaxResponseBytes {
		definition := errorsByCode["response_unavailable"]
		status = definition.Status
		data, _ = json.Marshal(envelope{SchemaVersion: 1, Error: &publicError{Code: "response_unavailable", Message: definition.Message}})
	}
	data = append(data, '\n')
	w.Header().Set("Content-Length", strconv.Itoa(len(data)))
	w.WriteHeader(status)
	_, _ = w.Write(data)
}
