package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net/http"
	"strconv"
	"time"

	"github.com/eblocker/eblocker/apps/network-agent/internal/network"
	"github.com/eblocker/eblocker/libs/wireguard"
)

const maxRequestBody = 6*(64<<10) + 32 // Includes worst-case JSON escaping of a 64 KiB profile.

type Handler struct {
	provider network.Provider
	slots    chan struct{}
}

func New(provider network.Provider) http.Handler {
	if provider == nil {
		panic("network provider must not be nil")
	}
	return &Handler{provider: provider, slots: make(chan struct{}, 32)}
}

type envelope struct {
	SchemaVersion int       `json:"schemaVersion"`
	Data          any       `json:"data,omitempty"`
	Error         *apiError `json:"error,omitempty"`
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

type capabilities struct {
	ReadOnly    bool                        `json:"readOnly"`
	Operations  []string                    `json:"operations"`
	WireGuard   network.WireGuardCapability `json:"wireguard"`
	Limitations []string                    `json:"limitations"`
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	select {
	case h.slots <- struct{}{}:
		defer func() { <-h.slots }()
	default:
		failure(w, http.StatusServiceUnavailable, "busy", "Agent is handling too many requests")
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
	defer cancel()
	path := r.URL.Path
	wantMethod := http.MethodGet
	switch path {
	case "/v1/health", "/v1/capabilities", "/v1/interfaces", "/v1/routes", "/v1/status":
	case "/v1/wireguard/validate":
		wantMethod = http.MethodPost
	default:
		failure(w, http.StatusNotFound, "not_found", "Unknown operation")
		return
	}
	if r.Method != wantMethod {
		w.Header().Set("Allow", wantMethod)
		failure(w, http.StatusMethodNotAllowed, "method_not_allowed", "Method is not allowed for this operation")
		return
	}
	if r.URL.RawQuery != "" {
		failure(w, http.StatusBadRequest, "invalid_request", "Query parameters are not supported")
		return
	}
	if wantMethod == http.MethodGet {
		// Reject bodies including chunked bodies without trusting Content-Length.
		var first [1]byte
		n, err := io.ReadFull(r.Body, first[:])
		if n != 0 || (err != nil && !errors.Is(err, io.EOF)) {
			failure(w, http.StatusBadRequest, "invalid_request", "This operation does not accept a request body")
			return
		}
	}
	var data any
	var err error
	switch path {
	case "/v1/health":
		data = struct {
			Status  string `json:"status"`
			Version string `json:"version"`
		}{"ready", "v1"}
	case "/v1/interfaces":
		data, err = h.provider.Interfaces(ctx)
	case "/v1/routes":
		data, err = h.provider.Routes(ctx)
	case "/v1/capabilities":
		data, err = h.capabilities(ctx)
	case "/v1/status":
		var status struct {
			ReadOnly     bool                `json:"readOnly"`
			Capabilities capabilities        `json:"capabilities"`
			Interfaces   []network.Interface `json:"interfaces"`
			Routes       []network.Route     `json:"routes"`
		}
		status.ReadOnly = true
		status.Capabilities, err = h.capabilities(ctx)
		if err == nil {
			status.Interfaces, err = h.provider.Interfaces(ctx)
		}
		if err == nil {
			status.Routes, err = h.provider.Routes(ctx)
		}
		data = status
	case "/v1/wireguard/validate":
		h.validateWireGuard(w, r)
		return
	}
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(ctx.Err(), context.DeadlineExceeded) {
			failure(w, http.StatusGatewayTimeout, "observation_timeout", "Kernel observation timed out")
		} else {
			failure(w, http.StatusServiceUnavailable, "observation_unavailable", "Kernel observation is unavailable")
		}
		return
	}
	respond(w, http.StatusOK, envelope{SchemaVersion: 1, Data: data})
}

func (h *Handler) validateWireGuard(w http.ResponseWriter, r *http.Request) {
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		failure(w, http.StatusUnsupportedMediaType, "invalid_content_type", "Use application/json")
		return
	}
	if r.Header.Get("Content-Encoding") != "" && r.Header.Get("Content-Encoding") != "identity" {
		failure(w, http.StatusUnsupportedMediaType, "invalid_content_encoding", "Compressed request bodies are not supported")
		return
	}
	r.Body = http.MaxBytesReader(w, r.Body, maxRequestBody)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	// Read tokens explicitly to reject duplicate keys and case variants too.
	opening, err := decoder.Token()
	if err != nil || opening != json.Delim('{') {
		decodeFailure(w, err)
		return
	}
	key, err := decoder.Token()
	if err != nil || key != "config" {
		decodeFailure(w, err)
		return
	}
	var text *string
	if err := decoder.Decode(&text); err != nil {
		decodeFailure(w, err)
		return
	}
	closing, err := decoder.Token()
	if err != nil || closing != json.Delim('}') || text == nil {
		decodeFailure(w, err)
		return
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		decodeFailure(w, err)
		return
	}
	if len(*text) > 64<<10 {
		failure(w, http.StatusRequestEntityTooLarge, "profile_too_large", "WireGuard profiles are limited to 64 KiB")
		return
	}
	config := []byte(*text)
	*text = ""
	defer clear(config)
	profile, err := wireguard.Parse(config)
	if err != nil {
		// Parser diagnostics are deliberately not reflected. Even future parser
		// errors must not disclose a private key or untrusted configuration text.
		failure(w, http.StatusUnprocessableEntity, "invalid_wireguard_profile", "WireGuard profile is invalid or contains unsupported options")
		return
	}
	defer profile.Destroy()
	respond(w, http.StatusOK, envelope{SchemaVersion: 1, Data: profile.Plan()})
}

func (h *Handler) capabilities(ctx context.Context) (capabilities, error) {
	wg, err := h.provider.WireGuard(ctx)
	return capabilities{ReadOnly: true, Operations: []string{"health", "status.read", "interfaces.read", "routes.read", "wireguard.validate"}, WireGuard: wg, Limitations: []string{"Process network namespace only", "Route output is observed state, not policy-routing simulation", "No firewall, route, interface, DNS or tunnel changes", "WireGuard validation does not imply connectivity or kill-switch protection"}}, err
}

func decodeFailure(w http.ResponseWriter, err error) {
	var oversized *http.MaxBytesError
	if errors.As(err, &oversized) {
		failure(w, http.StatusRequestEntityTooLarge, "request_too_large", "Request body exceeds the size limit")
		return
	}
	failure(w, http.StatusBadRequest, "invalid_json", "Supply one JSON object using the documented request fields")
}

func failure(w http.ResponseWriter, status int, code, message string) {
	respond(w, status, envelope{SchemaVersion: 1, Error: &apiError{Code: code, Message: message}})
}
func respond(w http.ResponseWriter, status int, value envelope) {
	body, err := json.Marshal(value)
	if err != nil {
		status = http.StatusInternalServerError
		body = []byte(`{"schemaVersion":1,"error":{"code":"encoding_error","message":"Response could not be encoded"}}`)
	}
	body = append(body, '\n')
	w.Header().Set("Content-Length", strconv.Itoa(len(body)))
	w.WriteHeader(status)
	_, _ = w.Write(body)
}
