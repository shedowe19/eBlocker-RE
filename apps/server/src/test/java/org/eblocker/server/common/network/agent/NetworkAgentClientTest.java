package org.eblocker.server.common.network.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class NetworkAgentClientTest {
    static final String STATUS = """
            {"schemaVersion":1,"data":{"readOnly":true,"capabilities":{"readOnly":true,
            "operations":["status","wireguard.validate"],"wireguard":{"kernelFamilyRegistered":false,
            "state":"not_registered","management":false,"reason":"Unavailable"},"limitations":[]},
            "interfaces":[],"routes":[]}}
            """;
    static final String PLAN = """
            {"schemaVersion":1,"data":{"interfaceAddresses":["10.0.0.2/32"],"dns":[],"peers":[
            {"publicKey":"public-material","allowedIPs":["0.0.0.0/0"],"hasPresharedKey":true}],
            "defaultRouteIPv4":true,"defaultRouteIPv6":false,"endpointExclusions":[],
            "requiredCapabilities":["CAP_NET_ADMIN"],"leakRisks":[],"warnings":[],
            "applied":false,"killSwitchActive":false}}
            """;

    @Test
    void exposesOnlyTheTypedReadOnlyStatus() throws Exception {
        NetworkAgentClient client = new NetworkAgentClient((method, path, body) -> {
            assertEquals("GET", method);
            assertEquals("/v1/status", path);
            assertEquals(0, body.length);
            return response(200, STATUS);
        }, new ObjectMapper());
        NetworkAgentModels.Envelope<NetworkAgentModels.Status> result = client.status();
        assertEquals(1, result.schemaVersion());
        assertTrue(result.data().readOnly());
        assertFalse(result.data().capabilities().wireguard().management());
    }

    @Test
    void acceptsTheStatusFixtureProducedByTheRealGoHandler() throws Exception {
        NetworkAgentModels.Status status = replying(200, sharedFixture("status")).status().data();
        assertTrue(status.readOnly());
        assertTrue(status.capabilities().wireguard().kernelFamilyRegistered());
        assertFalse(status.capabilities().wireguard().management());
        assertEquals("192.0.2.2/24", status.interfaces().get(0).addresses().get(0).prefix());
        assertEquals("fe80::2/64", status.interfaces().get(0).addresses().get(1).prefix());
        assertTrue(status.interfaces().get(1).addresses().isEmpty());
        NetworkAgentModels.Route blackhole = status.routes().get(0);
        assertEquals(6, blackhole.type());
        assertNull(blackhole.interfaceIndex());
        assertNull(blackhole.gateway());
        assertTrue(blackhole.nextHops().isEmpty());
        NetworkAgentModels.Route multipath = status.routes().get(1);
        assertEquals("::/0", multipath.destination());
        assertEquals(4294967295L, multipath.priority());
        assertEquals(2, multipath.nextHops().size());
        assertEquals("fe80::1", multipath.nextHops().get(0).gateway());
        assertEquals(256, multipath.nextHops().get(1).weight());
    }

    @Test
    void acceptsTheWireGuardFixtureProducedByTheRealGoHandlerAndParser() throws Exception {
        NetworkAgentModels.WireGuardPlan plan = replying(200, sharedFixture("wireguard-plan"))
                .validateWireGuard("test transport input").data();
        assertFalse(plan.applied());
        assertFalse(plan.killSwitchActive());
        assertTrue(plan.defaultRouteIPv4());
        assertTrue(plan.defaultRouteIPv6());
        assertEquals(51820, plan.listenPort());
        assertEquals(1420, plan.mtu());
        assertEquals(2, plan.peers().size());
        assertTrue(plan.peers().get(0).hasPresharedKey());
        assertEquals(25, plan.peers().get(0).persistentKeepalive());
        assertEquals("198.51.100.1:51820", plan.peers().get(0).endpoint());
        assertFalse(plan.peers().get(1).hasPresharedKey());
        assertNull(plan.peers().get(1).endpoint());
        assertNull(plan.peers().get(1).persistentKeepalive());
        assertEquals(java.util.List.of("198.51.100.1"), plan.endpointExclusions());
        assertEquals(java.util.List.of("no_kill_switch"), plan.leakRisks());
    }

    @Test
    void validationReturnsAPublicPlanAndClearsTheTransportBuffer() throws Exception {
        AtomicReference<byte[]> transferred = new AtomicReference<>();
        NetworkAgentClient client = new NetworkAgentClient((method, path, body) -> {
            assertEquals("POST", method);
            assertEquals("/v1/wireguard/validate", path);
            assertEquals("[Interface]\nPrivateKey=secret", new ObjectMapper().readTree(body).path("config").asText());
            transferred.set(body);
            return response(200, PLAN);
        }, new ObjectMapper());
        NetworkAgentModels.Envelope<NetworkAgentModels.WireGuardPlan> result = client.validateWireGuard("[Interface]\nPrivateKey=secret");
        assertFalse(result.data().applied());
        assertFalse(result.data().killSwitchActive());
        assertTrue(result.data().peers().get(0).hasPresharedKey());
        assertArrayEquals(new byte[transferred.get().length], transferred.get());
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("secret"));
    }

    @Test
    void invalidProfileErrorsNeverForwardAgentMessages() {
        NetworkAgentClient client = replying(422,
                "{\"schemaVersion\":1,\"error\":{\"code\":\"invalid_wireguard_profile\",\"message\":\"PrivateKey=secret\"}}");
        NetworkAgentClient.Failure failure = assertThrows(NetworkAgentClient.Failure.class,
                () -> client.validateWireGuard("private material"));
        assertEquals(422, failure.status());
        assertEquals("invalid_wireguard_profile", failure.code());
        assertFalse(failure.getMessage().contains("secret"));
        assertNull(failure.getCause());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":2,\"data\":{}}",
            "{\"schemaVersion\":1,\"schemaVersion\":1,\"data\":{}}",
            "{\"schemaVersion\":1,\"data\":{}} trailing",
            "{\"schemaVersion\":1,\"data\":null}",
            "{\"schemaVersion\":1,\"data\":{\"privateKey\":\"secret\"}}",
            "not JSON secret"
    })
    void malformedProtocolResponsesFailClosed(String input) {
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class, () -> replying(200, input).status()));
    }

    @Test
    void rejectsUnknownFieldsAndMutationClaims() {
        String unexpected = STATUS.replace("\"routes\":[]", "\"routes\":[],\"privateKey\":\"secret\"");
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class, () -> replying(200, unexpected).status()));
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class,
                () -> replying(200, STATUS.replace("\"management\":false", "\"management\":true")).status()));
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class,
                () -> replying(200, PLAN.replace("\"applied\":false", "\"applied\":true")).validateWireGuard("input")));
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class,
                () -> replying(200, PLAN.replace("\"publicKey\":", "\"privateKey\":")).validateWireGuard("input")));
    }

    @Test
    void unavailableAgentAndOversizedResponsesNeverFabricateCapabilities() {
        NetworkAgentClient client = new NetworkAgentClient((method, path, body) -> {
            throw new IOException("/private/path secret");
        }, new ObjectMapper());
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class, client::status));
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class, () -> replying(500, "secret").status()));
        assertUnavailable(assertThrows(NetworkAgentClient.Failure.class,
                () -> replying(200, "x".repeat(UnixSocketHttpTransport.MAX_RESPONSE_BYTES + 1)).status()));
    }

    @Test
    void decodedConfigurationLimitCountsUtf8BytesBeforeCallingAgent() {
        NetworkAgentClient client = new NetworkAgentClient((method, path, body) -> {
            fail("Invalid inputs must not reach the agent");
            return null;
        }, new ObjectMapper());
        NetworkAgentClient.Failure oversized = assertThrows(NetworkAgentClient.Failure.class,
                () -> client.validateWireGuard("ü".repeat(NetworkAgentClient.MAX_CONFIGURATION_BYTES / 2 + 1)));
        assertEquals(413, oversized.status());
        assertEquals(400, assertThrows(NetworkAgentClient.Failure.class, () -> client.validateWireGuard("  ")).status());
        assertEquals(400, assertThrows(NetworkAgentClient.Failure.class, () -> client.validateWireGuard(null)).status());
    }

    private static NetworkAgentClient replying(int code, String body) {
        return new NetworkAgentClient((method, path, request) -> response(code, body), new ObjectMapper());
    }

    private static String sharedFixture(String name) throws IOException {
        Path directory = Path.of(System.getProperty("basedir", ".")).toAbsolutePath().normalize();
        while (directory != null) {
            Path fixture = directory.resolve("contracts/network-agent/v1/" + name + ".json");
            if (Files.isRegularFile(fixture)) {
                return Files.readString(fixture, StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IOException("Shared network-agent contract fixture is missing: " + name);
    }

    private static NetworkAgentClient.AgentResponse response(int code, String body) {
        return new NetworkAgentClient.AgentResponse(code, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertUnavailable(NetworkAgentClient.Failure failure) {
        assertEquals(503, failure.status());
        assertEquals("network_agent_unavailable", failure.code());
        assertEquals("Network agent is unavailable", failure.getMessage());
        assertNull(failure.getCause());
    }
}
