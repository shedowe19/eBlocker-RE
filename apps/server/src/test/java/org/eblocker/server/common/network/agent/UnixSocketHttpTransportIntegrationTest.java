package org.eblocker.server.common.network.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt in only in a runtime that authorizes AF_UNIX; permission failures are never bypassed. */
@EnabledIfEnvironmentVariable(named = "EBLOCKER_AGENT_INTEGRATION", matches = "true")
class UnixSocketHttpTransportIntegrationTest {
    @TempDir Path directory;

    @Test
    void performsARealUnixSocketExchange() throws Exception {
        Path socket = directory.resolve("agent.sock");
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (SocketChannel connection = server.accept()) {
                    ByteBuffer input = ByteBuffer.allocate(1024);
                    StringBuilder request = new StringBuilder();
                    while (!request.toString().contains("\r\n\r\n")) {
                        int count = connection.read(input);
                        if (count < 0) throw new IOException("Request ended early");
                        request.append(new String(input.array(), 0, count, StandardCharsets.US_ASCII));
                        input.clear();
                    }
                    assertTrue(request.toString().startsWith("GET /v1/status HTTP/1.1\r\n"));
                    ByteBuffer response = StandardCharsets.US_ASCII.encode(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}");
                    while (response.hasRemaining()) connection.write(response);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
            assertEquals(200, new UnixSocketHttpTransport(socket).exchange("GET", "/v1/status", new byte[0]).status());
            peer.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void stalledPeerCannotKeepTheClientPastItsDeadline() throws Exception {
        Path socket = directory.resolve("stalled.sock");
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            CompletableFuture<Void> peer = CompletableFuture.runAsync(() -> {
                try (SocketChannel ignored = server.accept()) {
                    release.await(2, TimeUnit.SECONDS);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            });
            try {
                assertTimeout(Duration.ofSeconds(2), () -> assertThrows(IOException.class,
                        () -> new UnixSocketHttpTransport(socket, Duration.ofMillis(100))
                                .exchange("GET", "/v1/status", new byte[0])));
            } finally {
                release.countDown();
                peer.get(3, TimeUnit.SECONDS);
            }
        }
    }
}
