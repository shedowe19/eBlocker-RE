// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.common.network.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/** Separate capability for the explicitly installed native write service, never the read-only agent socket. */
@Singleton
public class WireGuardControlClient {
    public static final int MAX_CONFIGURATION_BYTES = 64 * 1024;
    public static final int MAX_REQUEST_BYTES = 384 * 1024;
    private static final byte[] ACTION = "{\"schemaVersion\":1}".getBytes(StandardCharsets.US_ASCII);
    private final NetworkAgentClient.Transport transport;
    private final ObjectMapper mapper;

    @Inject
    public WireGuardControlClient(@Named("network.control.socket") String socket, ObjectMapper mapper) {
        this(UnixSocketHttpTransport.control(Path.of(socket)), mapper);
    }

    WireGuardControlClient(NetworkAgentClient.Transport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public JsonNode listProfiles() throws Failure {
        return exchange("GET", "/v1/profiles", new byte[0], WireGuardControlModels.Profiles.class, null);
    }
    public JsonNode profile(String id) throws Failure {
        return exchange("GET", path(id), new byte[0], WireGuardControlModels.Detail.class, id);
    }
    public JsonNode importProfile(String id, String configuration) throws Failure {
        String path = path(id);
        if (configuration == null || configuration.isBlank()) throw failure(400, "invalid_request");
        byte[] raw = configuration.getBytes(StandardCharsets.UTF_8);
        try { if (raw.length > MAX_CONFIGURATION_BYTES) throw failure(413, "profile_too_large"); }
        finally { Arrays.fill(raw, (byte) 0); }
        try {
            byte[] request = mapper.writeValueAsBytes(Map.of("schemaVersion", 1, "configuration", configuration));
            return exchange("PUT", path, request, WireGuardControlModels.Summary.class, id);
        } catch (IOException e) { throw unavailable(); }
    }
    public JsonNode connect(String id) throws Failure { return action(id, "connect", WireGuardControlModels.Detail.class); }
    public JsonNode disconnect(String id) throws Failure { return action(id, "disconnect", WireGuardControlModels.Detail.class); }
    public JsonNode cancel(String id) throws Failure { return action(id, "cancel", WireGuardControlModels.Cancelled.class); }
    public JsonNode delete(String id) throws Failure {
        return exchange("DELETE", path(id), new byte[0], WireGuardControlModels.Deleted.class, id);
    }
    private JsonNode action(String id, String action, Class<?> type) throws Failure {
        return exchange("POST", path(id) + "/" + action, ACTION.clone(), type, id);
    }
    private String path(String id) throws Failure {
        try { WireGuardControlModels.id(id); }
        catch (IllegalArgumentException e) { throw failure(400, "invalid_id"); }
        return "/v1/profiles/" + id;
    }

    private JsonNode exchange(String method, String path, byte[] body, Class<?> type, String id) throws Failure {
        byte[] responseBody = null;
        try {
            if (body.length > MAX_REQUEST_BYTES) throw failure(413, "request_too_large");
            NetworkAgentClient.AgentResponse response = transport.exchange(method, path, body);
            responseBody = response.body();
            if (responseBody.length > UnixSocketHttpTransport.MAX_RESPONSE_BYTES) throw unavailable();
            JsonNode envelope = mapper.readTree(responseBody);
            if (envelope == null || !envelope.isObject() || envelope.size() != 2
                    || !envelope.path("schemaVersion").isInt() || envelope.path("schemaVersion").intValue() != 1) throw unavailable();
            if (response.status() != 200) {
                JsonNode error = envelope.get("error");
                if (error == null || !error.isObject() || error.size() != 2
                        || !error.path("code").isTextual() || !error.path("message").isTextual()) throw unavailable();
                String code = error.get("code").textValue();
                if (response.status() != errorStatus(code)) throw unavailable();
                // Never reflect arbitrary messages from a privileged peer or a parser.
                throw failure(response.status(), code);
            }
            JsonNode data = envelope.get("data");
            if (data == null || !data.isObject()) throw unavailable();
            mapper.treeToValue(data, type);
            if (id != null && !id.equals(data.path("profileId").textValue())) throw unavailable();
            // This exact tree has passed the complete typed allowlist, retaining
            // absent/null distinctions used by the shared Go/Java/React fixtures.
            return envelope;
        } catch (IOException | RuntimeException e) {
            throw unavailable();
        } finally {
            Arrays.fill(body, (byte) 0);
            if (responseBody != null) Arrays.fill(responseBody, (byte) 0);
        }
    }

    private record ErrorDefinition(int status, String message) { }
    // Public protocol v1 definitions, checked against the shared Go-generated error fixture.
    private static final Map<String, ErrorDefinition> ERRORS = Map.ofEntries(
            Map.entry("forbidden", new ErrorDefinition(403, "The local peer is not authorized.")),
            Map.entry("invalid_id", new ErrorDefinition(400, "Profile identifier is invalid.")),
            Map.entry("invalid_request", new ErrorDefinition(400, "Request does not match the control API contract.")),
            Map.entry("invalid_json", new ErrorDefinition(400, "Supply one JSON object using the documented request fields.")),
            Map.entry("invalid_schema", new ErrorDefinition(400, "Only schemaVersion 1 is supported.")),
            Map.entry("request_too_large", new ErrorDefinition(413, "Request body exceeds the size limit.")),
            Map.entry("profile_too_large", new ErrorDefinition(413, "WireGuard profiles are limited to 64 KiB.")),
            Map.entry("invalid_content_type", new ErrorDefinition(415, "Use uncompressed application/json.")),
            Map.entry("method_not_allowed", new ErrorDefinition(405, "Method is not allowed for this operation.")),
            Map.entry("invalid_profile", new ErrorDefinition(422, "WireGuard profile is invalid.")),
            Map.entry("unsupported_profile", new ErrorDefinition(422, "The native backend cannot safely apply this profile.")),
            Map.entry("not_found", new ErrorDefinition(404, "Profile or operation was not found.")),
            Map.entry("conflict", new ErrorDefinition(409, "Disconnect the existing profile before changing or deleting it.")),
            Map.entry("profile_limit", new ErrorDefinition(409, "The private profile store has reached its capacity.")),
            Map.entry("busy", new ErrorDefinition(503, "Another control operation is in progress.")),
            Map.entry("storage_failed", new ErrorDefinition(503, "Private lifecycle storage is unavailable.")),
            Map.entry("closed", new ErrorDefinition(503, "Lifecycle manager is closed.")),
            Map.entry("backend_unavailable", new ErrorDefinition(503, "The native backend is unavailable.")),
            Map.entry("ownership_mismatch", new ErrorDefinition(503, "A network resource is not owned by this profile.")),
            Map.entry("apply_failed", new ErrorDefinition(503, "Applying the profile failed; inspect the lifecycle state.")),
            Map.entry("remove_failed", new ErrorDefinition(503, "Disconnect is incomplete; retry or recover before reconnecting.")),
            Map.entry("rollback_failed", new ErrorDefinition(503, "Rollback is incomplete; recovery is required.")),
            Map.entry("recovery_failed", new ErrorDefinition(503, "Recovery is incomplete; inspect each profile before retrying.")),
            Map.entry("observation_failed", new ErrorDefinition(503, "The owned interface could not be observed.")),
            Map.entry("policy_not_verified", new ErrorDefinition(503, "Full-tunnel routing and firewall protection could not be verified.")),
            Map.entry("cancelled", new ErrorDefinition(409, "The operation was cancelled.")),
            Map.entry("timeout", new ErrorDefinition(504, "The control operation timed out; read the profile state before retrying.")),
            Map.entry("response_unavailable", new ErrorDefinition(503, "The control response is unavailable.")));
    static int errorStatus(String code) {
        ErrorDefinition definition = ERRORS.get(code);
        return definition == null ? -1 : definition.status;
    }
    private static Failure unavailable() { return new Failure(503, "wireguard_control_unavailable", "WireGuard control service is unavailable"); }
    public static Failure failure(int status, String code) {
        ErrorDefinition definition = ERRORS.get(code);
        if (definition == null || definition.status != status) return unavailable();
        return new Failure(status, code, definition.message);
    }
    public static final class Failure extends Exception {
        private final int status;
        private final String code;
        public Failure(int status, String code, String message) { super(message); this.status = status; this.code = code; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
