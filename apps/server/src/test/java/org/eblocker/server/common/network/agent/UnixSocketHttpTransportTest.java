package org.eblocker.server.common.network.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class UnixSocketHttpTransportTest {
    @Test
    void parsesAnExactLengthJsonResponse() throws Exception {
        NetworkAgentClient.AgentResponse response = UnixSocketHttpTransport.parseResponse(bytes(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: 2\r\n\r\n{}"));
        assertEquals(200, response.status());
        assertArrayEquals(bytes("{}"), response.body());
    }

    @ParameterizedTest
    @MethodSource("invalidResponses")
    void rejectsAmbiguousOrIncompleteFraming(String response) {
        IOException failure = assertThrows(IOException.class,
                () -> UnixSocketHttpTransport.parseResponse(bytes(response)));
        assertEquals("Invalid network agent HTTP response", failure.getMessage());
    }

    static Stream<String> invalidResponses() {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n";
        return Stream.of(
                head + "\r\n{}",
                head + "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
                head + "Content-Length: 2\r\ncontent-length: 2\r\n\r\n{}",
                head + "Content-Length: 2\r\nTransfer-Encoding: chunked\r\n\r\n{}",
                head + "Content-Length: 2\r\nContent-Encoding: gzip\r\n\r\n{}",
                head + "Content-Length: 2\r\nTrailer: secret\r\n\r\n{}",
                head + "Content-Length: 3\r\n\r\n{}",
                head + "Content-Length: 1\r\n\r\n{}",
                head + "Content-Length: 2\r\n\r\n{}trailing",
                head + "Content-Length: -2\r\n\r\n{}",
                head + "Content-Length: 02\r\n\r\n{}",
                head + "Content-Length: 524289\r\n\r\n",
                head + "Content-Length: 99999999999999999\r\n\r\n",
                head + "Content-Length: 2\r\n X-Header: folded\r\n\r\n{}",
                head + "Content-Length: 2\r\nX-Header: invalid\n\r\n\r\n{}",
                head + "Content-Length: 2\r\nX-Header: bad\u0000\r\n\r\n{}",
                "HTTP/2 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 2\r\n\r\n{}",
                "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n{}",
                "HTTP/1.1 200 OK\nContent-Type: application/json\nContent-Length: 2\n\n{}",
                head + "X-Header: " + "x".repeat(UnixSocketHttpTransport.MAX_HEADER_BYTES) + "\r\nContent-Length: 2\r\n\r\n{}");
    }

    @Test
    void onlyFixedOperationsCanBeEncoded() throws Exception {
        byte[] body = bytes("{\"config\":\"profile\"}");
        String request = new String(UnixSocketHttpTransport.request("POST", "/v1/wireguard/validate", body),
                StandardCharsets.UTF_8);
        assertTrue(request.startsWith("POST /v1/wireguard/validate HTTP/1.1\r\n"));
        assertTrue(request.contains("Content-Length: " + body.length + "\r\n"));
        assertTrue(request.contains("Connection: close\r\n"));
        assertTrue(request.endsWith("\r\n\r\n{\"config\":\"profile\"}"));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.request("GET", "http://remote/v1/status", new byte[0]));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.request("GET", "/v1/status", body));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.request("DELETE", "/v1/status", new byte[0]));
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.request("POST", "/v1/wireguard/validate",
                new byte[NetworkAgentClient.MAX_ENCODED_REQUEST_BYTES + 1]));
    }

    @Test
    void deadlineAndInterruptionAreEnforcedWithoutOpeningASocket() {
        assertThrows(IOException.class, () -> UnixSocketHttpTransport.checkDeadline(System.nanoTime() - 1));
        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class,
                    () -> UnixSocketHttpTransport.checkDeadline(System.nanoTime() + Duration.ofSeconds(3).toNanos()));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void socketMustBeAnAbsoluteFilesystemPath() {
        assertThrows(IllegalArgumentException.class, () -> new UnixSocketHttpTransport(Path.of("agent.sock")));
        assertThrows(IllegalArgumentException.class, () -> new UnixSocketHttpTransport(Path.of("/agent.sock"), Duration.ZERO));
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
