/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL
 * (the "License"); You may not use this work except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://joinup.ec.europa.eu/page/eupl-text-11-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.eblocker.server.common.network.agent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** A bounded HTTP/1.1 exchange over the configured Unix socket, with no TCP fallback. */
final class UnixSocketHttpTransport implements NetworkAgentClient.Transport {
    static final int MAX_RESPONSE_BYTES = 512 * 1024;
    static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private final Path socket;
    private final Duration timeout;
    private final boolean control;

    UnixSocketHttpTransport(Path socket) {
        this(socket, TIMEOUT);
    }

    UnixSocketHttpTransport(Path socket, Duration timeout) {
        this(socket, timeout, false);
    }

    static UnixSocketHttpTransport control(Path socket) {
        return new UnixSocketHttpTransport(socket, Duration.ofSeconds(40), true);
    }

    private UnixSocketHttpTransport(Path socket, Duration timeout, boolean control) {
        if (!socket.isAbsolute() || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Invalid network agent transport configuration");
        }
        this.socket = socket;
        this.timeout = timeout;
        this.control = control;
    }

    @Override
    public NetworkAgentClient.AgentResponse exchange(String method, String path, byte[] body) throws IOException {
        byte[] request = control ? controlRequest(method, path, body) : request(method, path, body);
        long deadline = System.nanoTime() + timeout.toNanos();
        try (SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
             Selector selector = Selector.open()) {
            channel.configureBlocking(false);
            channel.register(selector, 0);
            if (!channel.connect(UnixDomainSocketAddress.of(socket))) {
                while (!channel.finishConnect()) {
                    await(channel, selector, SelectionKey.OP_CONNECT, deadline);
                }
            }
            ByteBuffer output = ByteBuffer.wrap(request);
            while (output.hasRemaining()) {
                checkDeadline(deadline);
                if (channel.write(output) == 0) {
                    await(channel, selector, SelectionKey.OP_WRITE, deadline);
                }
            }
            ByteArrayOutputStream input = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (true) {
                checkDeadline(deadline);
                int count = channel.read(buffer);
                if (count < 0) {
                    return parseResponse(input.toByteArray());
                }
                if (count == 0) {
                    await(channel, selector, SelectionKey.OP_READ, deadline);
                    continue;
                }
                if (input.size() + count > MAX_HEADER_BYTES + MAX_RESPONSE_BYTES) {
                    throw protocolError();
                }
                input.write(buffer.array(), 0, count);
                buffer.clear();
            }
        } finally {
            // The encoded request may contain private WireGuard material.
            Arrays.fill(request, (byte) 0);
        }
    }

    private static void await(SocketChannel channel, Selector selector, int operation, long deadline) throws IOException {
        checkDeadline(deadline);
        channel.keyFor(selector).interestOps(operation);
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new IOException("Network agent request timed out");
        }
        selector.select(Math.max(1, (remaining + 999_999) / 1_000_000));
        selector.selectedKeys().clear();
        checkDeadline(deadline);
    }

    static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() - deadline >= 0) {
            throw new IOException("Network agent request timed out");
        }
    }

    static byte[] request(String method, String path, byte[] body) throws IOException {
        if (!(method.equals("GET") && path.equals("/v1/status") && body.length == 0)
                && !(method.equals("POST") && path.equals("/v1/wireguard/validate")
                && body.length <= NetworkAgentClient.MAX_ENCODED_REQUEST_BYTES)) {
            throw protocolError();
        }
        return encodeRequest(method, path, body);
    }

    static byte[] controlRequest(String method, String path, byte[] body) throws IOException {
        boolean collection = path.equals("/v1/profiles");
        boolean profile = path.matches("/v1/profiles/[a-z][a-z0-9-]{0,31}");
        boolean action = path.matches("/v1/profiles/[a-z][a-z0-9-]{0,31}/(?:connect|disconnect|cancel)");
        if (!(method.equals("GET") && (collection || profile) && body.length == 0)
                && !(method.equals("DELETE") && profile && body.length == 0)
                && !(method.equals("POST") && action && Arrays.equals(body, "{\"schemaVersion\":1}".getBytes(StandardCharsets.US_ASCII)))
                && !(method.equals("PUT") && profile && body.length > 0
                && body.length <= WireGuardControlClient.MAX_REQUEST_BYTES)) {
            throw protocolError();
        }
        return encodeRequest(method, path, body);
    }

    private static byte[] encodeRequest(String method, String path, byte[] body) {
        String headers = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n"
                + "Accept: application/json\r\nContent-Type: application/json\r\n"
                + "Content-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        byte[] prefix = headers.getBytes(StandardCharsets.US_ASCII);
        byte[] result = Arrays.copyOf(prefix, prefix.length + body.length);
        System.arraycopy(body, 0, result, prefix.length, body.length);
        return result;
    }

    static NetworkAgentClient.AgentResponse parseResponse(byte[] raw) throws IOException {
        int separator = -1;
        for (int i = 0; i <= Math.min(raw.length - 4, MAX_HEADER_BYTES - 4); i++) {
            if (raw[i] == '\r' && raw[i + 1] == '\n' && raw[i + 2] == '\r' && raw[i + 3] == '\n') {
                separator = i;
                break;
            }
        }
        if (separator < 0 || raw.length > MAX_HEADER_BYTES + MAX_RESPONSE_BYTES) {
            throw protocolError();
        }
        for (int i = 0; i < separator; i++) {
            int value = Byte.toUnsignedInt(raw[i]);
            if (value > 126 || (value < 32 && value != '\r' && value != '\n')) {
                throw protocolError();
            }
        }
        String header = new String(raw, 0, separator, StandardCharsets.US_ASCII);
        String[] lines = header.split("\r\n", -1);
        if (!lines[0].matches("HTTP/1\\.[01] [1-5][0-9]{2} [\\x20-\\x7e]*")) {
            throw protocolError();
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (!lines[i].matches("[\\x20-\\x7e]+")) {
                throw protocolError();
            }
            int colon = lines[i].indexOf(':');
            if (colon <= 0) {
                throw protocolError();
            }
            String name = lines[i].substring(0, colon).toLowerCase(Locale.ROOT);
            String value = lines[i].substring(colon + 1).trim();
            if (!name.matches("[!#$%&'*+.^_`|~0-9a-z-]+")
                    || !value.matches("[\\x20-\\x7e]*") || headers.putIfAbsent(name, value) != null) {
                throw protocolError();
            }
        }
        String length = headers.get("content-length");
        String contentType = headers.getOrDefault("content-type", "");
        if (headers.containsKey("transfer-encoding") || headers.containsKey("content-encoding")
                || headers.containsKey("trailer") || length == null || !length.matches("0|[1-9][0-9]{0,6}")
                || !contentType.matches("(?i)application/json(?:;\\s*charset=utf-8)?")) {
            throw protocolError();
        }
        int bytes = Integer.parseInt(length);
        int bodyOffset = separator + 4;
        if (bytes > MAX_RESPONSE_BYTES || raw.length - bodyOffset != bytes) {
            throw protocolError();
        }
        return new NetworkAgentClient.AgentResponse(Integer.parseInt(lines[0].substring(9, 12)),
                Arrays.copyOfRange(raw, bodyOffset, raw.length));
    }

    private static IOException protocolError() {
        return new IOException("Invalid network agent HTTP response");
    }
}
