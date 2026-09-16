package controlapi

import "net/http"

type errorDefinition struct {
	Status  int
	Message string
}

// Only these fixed public messages are emitted; backend/parser/JSON/OS error
// strings, configuration fragments and request paths are never reflected.
var errorsByCode = map[string]errorDefinition{
	"forbidden":            {http.StatusForbidden, "The local peer is not authorized."},
	"invalid_id":           {http.StatusBadRequest, "Profile identifier is invalid."},
	"invalid_request":      {http.StatusBadRequest, "Request does not match the control API contract."},
	"invalid_json":         {http.StatusBadRequest, "Supply one JSON object using the documented request fields."},
	"invalid_schema":       {http.StatusBadRequest, "Only schemaVersion 1 is supported."},
	"request_too_large":    {http.StatusRequestEntityTooLarge, "Request body exceeds the size limit."},
	"profile_too_large":    {http.StatusRequestEntityTooLarge, "WireGuard profiles are limited to 64 KiB."},
	"invalid_content_type": {http.StatusUnsupportedMediaType, "Use uncompressed application/json."},
	"method_not_allowed":   {http.StatusMethodNotAllowed, "Method is not allowed for this operation."},
	"invalid_profile":      {http.StatusUnprocessableEntity, "WireGuard profile is invalid."},
	"unsupported_profile":  {http.StatusUnprocessableEntity, "The native backend cannot safely apply this profile."},
	"not_found":            {http.StatusNotFound, "Profile or operation was not found."},
	"conflict":             {http.StatusConflict, "Disconnect the existing profile before changing or deleting it."},
	"profile_limit":        {http.StatusConflict, "The private profile store has reached its capacity."},
	"busy":                 {http.StatusServiceUnavailable, "Another control operation is in progress."},
	"storage_failed":       {http.StatusServiceUnavailable, "Private lifecycle storage is unavailable."},
	"closed":               {http.StatusServiceUnavailable, "Lifecycle manager is closed."},
	"backend_unavailable":  {http.StatusServiceUnavailable, "The native backend is unavailable."},
	"ownership_mismatch":   {http.StatusServiceUnavailable, "A network resource is not owned by this profile."},
	"apply_failed":         {http.StatusServiceUnavailable, "Applying the profile failed; inspect the lifecycle state."},
	"remove_failed":        {http.StatusServiceUnavailable, "Disconnect is incomplete; retry or recover before reconnecting."},
	"rollback_failed":      {http.StatusServiceUnavailable, "Rollback is incomplete; recovery is required."},
	"recovery_failed":      {http.StatusServiceUnavailable, "Recovery is incomplete; inspect each profile before retrying."},
	"observation_failed":   {http.StatusServiceUnavailable, "The owned interface could not be observed."},
	"policy_not_verified":  {http.StatusServiceUnavailable, "Full-tunnel routing and firewall protection could not be verified."},
	"cancelled":            {http.StatusConflict, "The operation was cancelled."},
	"timeout":              {http.StatusGatewayTimeout, "The control operation timed out; read the profile state before retrying."},
	"response_unavailable": {http.StatusServiceUnavailable, "The control response is unavailable."},
}
