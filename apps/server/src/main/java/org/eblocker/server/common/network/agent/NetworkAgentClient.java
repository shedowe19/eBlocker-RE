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

import static org.eblocker.server.common.network.agent.NetworkAgentModels.*;

/** Only the two read-only protocol operations are exposed to the control plane. */
@Singleton
public class NetworkAgentClient {
    public static final int MAX_CONFIGURATION_BYTES = 64 * 1024;
    public static final int MAX_ENCODED_REQUEST_BYTES = 6 * MAX_CONFIGURATION_BYTES + 32;
    private final Transport transport;
    private final ObjectMapper mapper;

    @Inject
    public NetworkAgentClient(@Named("network.agent.socket") String socket, ObjectMapper mapper) {
        this(new UnixSocketHttpTransport(Path.of(socket)), mapper);
    }

    NetworkAgentClient(Transport transport, ObjectMapper mapper) {
        this.transport = transport;
        this.mapper = mapper.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public Envelope<Status> status() throws Failure {
        Status status = exchange("GET", "/v1/status", new byte[0], Status.class);
        if (!status.readOnly() || !status.capabilities().readOnly() || status.capabilities().wireguard().management()) {
            throw unavailable();
        }
        return new Envelope<>(1, status);
    }

    public Envelope<WireGuardPlan> validateWireGuard(String configuration) throws Failure {
        if (configuration == null || configuration.isBlank()) {
            throw new Failure(400, "invalid_request", "A WireGuard configuration is required");
        }
        byte[] input = configuration.getBytes(StandardCharsets.UTF_8);
        try {
            if (input.length > MAX_CONFIGURATION_BYTES) {
                throw new Failure(413, "request_too_large", "WireGuard configuration exceeds the limit");
            }
        } finally {
            Arrays.fill(input, (byte) 0);
        }
        byte[] body;
        try {
            body = mapper.writeValueAsBytes(Map.of("config", configuration));
        } catch (IOException e) {
            throw unavailable();
        }
        try {
            WireGuardPlan plan = exchange("POST", "/v1/wireguard/validate", body, WireGuardPlan.class);
            if (plan.applied() || plan.killSwitchActive()) {
                throw unavailable();
            }
            return new Envelope<>(1, plan);
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private <T> T exchange(String method, String path, byte[] request, Class<T> type) throws Failure {
        try {
            AgentResponse response = transport.exchange(method, path, request);
            if (response.body().length > UnixSocketHttpTransport.MAX_RESPONSE_BYTES) {
                throw unavailable();
            }
            JsonNode envelope = mapper.readTree(response.body());
            if (envelope == null || !envelope.isObject() || !envelope.path("schemaVersion").isInt()
                    || envelope.path("schemaVersion").intValue() != 1 || envelope.size() != 2) {
                throw unavailable();
            }
            if (response.status() == 422 && path.equals("/v1/wireguard/validate")
                    && envelope.path("error").path("code").asText().equals("invalid_wireguard_profile")) {
                throw new Failure(422, "invalid_wireguard_profile", "WireGuard configuration is invalid");
            }
            if (response.status() != 200 || !envelope.path("data").isObject()) {
                throw unavailable();
            }
            return mapper.treeToValue(envelope.get("data"), type);
        } catch (IOException | RuntimeException e) {
            // Transport/parser exception text can include source snippets. Never attach or log it.
            throw unavailable();
        }
    }

    private static Failure unavailable() {
        return new Failure(503, "network_agent_unavailable", "Network agent is unavailable");
    }

    @FunctionalInterface
    interface Transport {
        AgentResponse exchange(String method, String path, byte[] body) throws IOException;
    }

    record AgentResponse(int status, byte[] body) { }

    public static final class Failure extends Exception {
        private final int status;
        private final String code;

        public Failure(int status, String code, String message) {
            super(message);
            this.status = status;
            this.code = code;
        }

        public int status() { return status; }
        public String code() { return code; }
    }
}
