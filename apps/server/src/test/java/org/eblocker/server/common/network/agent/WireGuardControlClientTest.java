// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.common.network.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WireGuardControlClientTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PLAN = """
            {"interfaceAddresses":["10.0.0.2/32"],"dns":[],"peers":[{"publicKey":"public-material",
            "allowedIPs":["10.0.0.0/8"],"hasPresharedKey":true}],"defaultRouteIPv4":false,"defaultRouteIPv6":false,
            "endpointExclusions":[],"requiredCapabilities":["CAP_NET_ADMIN"],"leakRisks":[],"warnings":[],"applied":false,"killSwitchActive":false}
            """;
    private static final String SUMMARY = "{\"profileId\":\"office\",\"phase\":\"imported\",\"plan\":" + PLAN + "}";
    private static final String DETAIL = "{\"profileId\":\"office\",\"phase\":\"imported\",\"plan\":" + PLAN + ",\"runtime\":null}";
    private static String envelope(String data) { return "{\"schemaVersion\":1,\"data\":" + data + "}"; }
    private static byte[] bytes(String data) { return data.getBytes(StandardCharsets.UTF_8); }
    private static WireGuardControlClient reply(int status, String json) {
        return new WireGuardControlClient((method, path, body) -> new NetworkAgentClient.AgentResponse(status, bytes(json)), MAPPER);
    }

    @Test
    void importedAndDetailedProfilesPreserveExactEnvelopeAndNullDistinction() throws Exception {
        assertEquals(MAPPER.readTree(envelope(DETAIL)), reply(200, envelope(DETAIL)).profile("office"));
        String list = envelope("{\"profiles\":[" + SUMMARY + "]}");
        assertEquals(MAPPER.readTree(list), reply(200, list).listProfiles());
        assertEquals(MAPPER.readTree(envelope(SUMMARY)), reply(200, envelope(SUMMARY)).importProfile("office", "config"));
    }

    @Test
    void importEncodesOnlyVersionAndConfigurationThenClearsTransferredSecrets() throws Exception {
        AtomicReference<byte[]> transferred = new AtomicReference<>();
        WireGuardControlClient client = new WireGuardControlClient((method, path, body) -> {
            assertEquals("PUT", method); assertEquals("/v1/profiles/office", path);
            JsonNode input = MAPPER.readTree(body);
            assertEquals(2, input.size());
            assertEquals(1, input.path("schemaVersion").intValue());
            assertEquals("PrivateKey=secret", input.path("configuration").textValue());
            transferred.set(body);
            return new NetworkAgentClient.AgentResponse(200, bytes(envelope(SUMMARY)));
        }, MAPPER);
        assertFalse(client.importProfile("office", "PrivateKey=secret").toString().contains("secret"));
        assertArrayEquals(new byte[transferred.get().length], transferred.get());
    }

    @Test
    void eachOperationUsesItsOwnFixedVerbPathAndVersionedBody() throws Exception {
        AtomicReference<String> call = new AtomicReference<>();
        WireGuardControlClient client = new WireGuardControlClient((method, path, body) -> {
            call.set(method + " " + path);
            String data = DETAIL;
            if (method.equals("POST")) assertEquals("{\"schemaVersion\":1}", new String(body, StandardCharsets.US_ASCII));
            else assertEquals(0, body.length);
            if (path.endsWith("/cancel")) data = "{\"profileId\":\"office\",\"cancellationRequested\":true}";
            if (method.equals("DELETE")) data = "{\"profileId\":\"office\",\"deleted\":true}";
            return new NetworkAgentClient.AgentResponse(200, bytes(envelope(data)));
        }, MAPPER);
        client.connect("office"); assertEquals("POST /v1/profiles/office/connect", call.get());
        client.disconnect("office"); assertEquals("POST /v1/profiles/office/disconnect", call.get());
        client.cancel("office"); assertEquals("POST /v1/profiles/office/cancel", call.get());
        client.delete("office"); assertEquals("DELETE /v1/profiles/office", call.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../secret", "office/connect", "OFFICE", "a?x=y", "a\r\nHost: remote", "", "0123", "abcdefghijklmnopqrstuvwxyz1234567890"})
    void invalidIdsNeverReachTransport(String id) {
        WireGuardControlClient client = new WireGuardControlClient((method, path, body) -> { fail("No transport for invalid ID"); return null; }, MAPPER);
        var error = assertThrows(WireGuardControlClient.Failure.class, () -> client.profile(id));
        assertEquals(400, error.status()); assertEquals("invalid_id", error.code());
    }

    @Test
    void utf8AndEncodedRequestLimitsAreCheckedBeforeTransport() {
        WireGuardControlClient client = new WireGuardControlClient((method, path, body) -> { fail("No oversized transport"); return null; }, MAPPER);
        var error = assertThrows(WireGuardControlClient.Failure.class, () -> client.importProfile("office", "ü".repeat(32769)));
        assertEquals(413, error.status()); assertEquals("profile_too_large", error.code());
        assertEquals("request_too_large", assertThrows(WireGuardControlClient.Failure.class,
                () -> client.importProfile("office", "\u0000".repeat(65536))).code());
    }

    @Test
    void secretFieldsWrongIdentityAndInconsistentRuntimeFailClosed() {
        for (String data : new String[]{DETAIL.replace("\"runtime\":null", "\"runtime\":null,\"privateKey\":\"secret\""),
                DETAIL.replace("\"office\"", "\"other\""), DETAIL.replace("\"imported\"", "\"active\""),
                DETAIL.replace("\"hasPresharedKey\":true", "\"hasPresharedKey\":true,\"presharedKey\":\"secret\""),
                DETAIL.replace("\"applied\":false", "\"applied\":true"),
                DETAIL.replace("\"defaultRouteIPv4\":false,", ""), DETAIL.replace("\"defaultRouteIPv4\":false", "\"defaultRouteIPv4\":\"false\"")}) {
            unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(200, envelope(data)).profile("office")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "{\"schemaVersion\":2,\"data\":{}}", "{\"schemaVersion\":1,\"schemaVersion\":1,\"data\":{}}",
            "{\"schemaVersion\":1,\"data\":null}", "{\"schemaVersion\":1,\"data\":{}} trailing", "secret"})
    void malformedEnvelopesAreUnavailableWithoutSourceLeak(String body) {
        unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(200, body).profile("office")));
    }

    @Test
    void knownFailuresUseFixedMessagesAndUnknownOrMismatchedFailuresAreUnavailable() {
        var failure = assertThrows(WireGuardControlClient.Failure.class, () -> reply(409,
                "{\"schemaVersion\":1,\"error\":{\"code\":\"conflict\",\"message\":\"secret\"}}").connect("office"));
        assertEquals(409, failure.status());
        assertEquals("Disconnect the existing profile before changing or deleting it.", failure.getMessage());
        unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(500,
                "{\"schemaVersion\":1,\"error\":{\"code\":\"conflict\",\"message\":\"secret\"}}").connect("office")));
        unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(503,
                "{\"schemaVersion\":1,\"error\":{\"code\":\"privateKey\",\"message\":\"secret\"}}").connect("office")));
    }

    @Test
    void transportFailureHasNoFallbackRetryOrSecretCause() {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        WireGuardControlClient client = new WireGuardControlClient((method, path, body) -> {
            attempts.incrementAndGet(); throw new IOException("/private/socket secret");
        }, MAPPER);
        unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> client.connect("office")));
        assertEquals(1, attempts.get());
    }

    @Test
    void controlFramingIsSeparateFromReadOnlyAllowlist() throws Exception {
        byte[] action = bytes("{\"schemaVersion\":1}");
        String request = new String(UnixSocketHttpTransport.controlRequest("POST", "/v1/profiles/office/connect", action), StandardCharsets.US_ASCII);
        assertTrue(request.startsWith("POST /v1/profiles/office/connect HTTP/1.1\r\n"));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.request("POST", "/v1/profiles/office/connect", action));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.controlRequest("GET", "/v1/status", new byte[0]));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.controlRequest("POST", "/v1/profiles/office/connect", new byte[0]));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.controlRequest("POST", "/v1/profiles/office/shell", action));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.controlRequest("GET", "http://remote/v1/profiles", new byte[0]));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.controlRequest("DELETE", "/v1/profiles/office", action));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.controlRequest("PUT", "/v1/profiles/office",
                new byte[WireGuardControlClient.MAX_REQUEST_BYTES + 1]));
    }

    @Test
    void consumesExactGoProducedSharedSuccessFixtures() throws Exception {
        for (String name : new String[]{"profile-list", "empty-list"}) {
            String fixture = fixture(name);
            assertEquals(MAPPER.readTree(fixture), reply(200, fixture).listProfiles());
        }
        String imported = fixture("import-result");
        assertEquals(MAPPER.readTree(imported), reply(200, imported).importProfile("home", "test input"));
        String deleted = fixture("delete-result");
        assertEquals(MAPPER.readTree(deleted), reply(200, deleted).delete("home"));
        String status = fixture("profile-status");
        JsonNode result = reply(200, status).profile("home");
        assertEquals(MAPPER.readTree(status), result);
        assertTrue(result.path("data").path("runtime").path("killSwitchActive").booleanValue());
        assertEquals(3946075768L, result.path("data").path("runtime").path("policy").path("firewallMark").longValue());
    }

    @Test
    void everySharedGoErrorHasIdenticalFixedJavaStatusAndMessage() throws Exception {
        JsonNode errors = MAPPER.readTree(fixture("errors"));
        for (var fields = errors.fields(); fields.hasNext();) {
            var field = fields.next();
            int status = field.getValue().path("status").intValue();
            assertEquals(status, WireGuardControlClient.errorStatus(field.getKey()), field.getKey());
            String input = MAPPER.writeValueAsString(java.util.Map.of("schemaVersion", 1,
                    "error", java.util.Map.of("code", field.getKey(), "message", "secret text never reflected")));
            var failure = assertThrows(WireGuardControlClient.Failure.class, () -> reply(status, input).connect("home"));
            assertEquals(field.getKey(), failure.code());
            assertEquals(field.getValue().path("message").textValue(), failure.getMessage());
            assertEquals(status, failure.status());
        }
    }

    @Test
    void runtimeRejectsSecretFieldsMissingAttestationAndFalseProtectionClaims() throws Exception {
        JsonNode fixture = MAPPER.readTree(fixture("profile-status"));
        for (String field : new String[]{"firewallVerified", "routingVerified", "markVerified", "endpointsVerified", "ipv4PoliciesVerified", "ipv6PoliciesVerified", "killSwitchActive"}) {
            JsonNode invalid = fixture.deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) invalid.path("data").path("runtime").path("observation").path("policy")).put(field, false);
            unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(200, invalid.toString()).profile("home")));
        }
        JsonNode secret = fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) secret.path("data").path("runtime").path("policy")).put("configuration", "secret");
        unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(200, secret.toString()).profile("home")));
        JsonNode unsigned = fixture.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned.path("data").path("runtime").path("observation").path("peers").get(0))
                .put("receiveBytes", new java.math.BigInteger("18446744073709551615"));
        assertEquals(unsigned, reply(200, unsigned.toString()).profile("home"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) unsigned.path("data").path("runtime").path("observation").path("peers").get(0))
                .put("receiveBytes", new java.math.BigInteger("18446744073709551616"));
        unavailable(assertThrows(WireGuardControlClient.Failure.class, () -> reply(200, unsigned.toString()).profile("home")));
    }

    private static String fixture(String name) throws IOException {
        java.nio.file.Path directory = java.nio.file.Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        while (directory != null) {
            java.nio.file.Path fixture = directory.resolve("contracts/wireguard-control/v1/" + name + ".json");
            if (java.nio.file.Files.isRegularFile(fixture)) return java.nio.file.Files.readString(fixture, StandardCharsets.UTF_8);
            directory = directory.getParent();
        }
        throw new IOException("Shared WireGuard control fixture is missing");
    }

    private static void unavailable(WireGuardControlClient.Failure failure) {
        assertEquals(503, failure.status()); assertEquals("wireguard_control_unavailable", failure.code());
        assertFalse(failure.getMessage().contains("secret")); assertNull(failure.getCause());
    }
}
