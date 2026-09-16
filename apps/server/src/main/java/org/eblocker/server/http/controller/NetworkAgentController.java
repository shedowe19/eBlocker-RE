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
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.common.network.agent.NetworkAgentClient;
import org.eblocker.server.http.security.AppContext;
import org.eblocker.server.http.security.SecurityProcessor;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.UnauthorizedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/** Authenticated admin bridge for observation and validation; no network mutation is exposed. */
@Singleton
public class NetworkAgentController {
    private final NetworkAgentClient client;
    private final ObjectMapper mapper;

    @Inject
    public NetworkAgentController(NetworkAgentClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public Object getStatus(Request request, Response response) {
        requireAdmin(request);
        response.addHeader("Cache-Control", "no-store");
        try {
            return client.status();
        } catch (NetworkAgentClient.Failure failure) {
            return error(response, failure.status(), failure.code(), failure.getMessage());
        }
    }

    public Object validateWireGuard(Request request, Response response) {
        requireAdmin(request);
        response.addHeader("Cache-Control", "no-store");
        String contentType = request.getHeader("Content-Type");
        if (contentType == null || !contentType.matches("(?i)application/json(?:;\\s*charset=utf-8)?")) {
            return error(response, 415, "invalid_content_type", "A JSON request is required");
        }
        if (request.getBody() == null || request.getBody().readableBytes() == 0) {
            return error(response, 400, "invalid_request", "A WireGuard configuration is required");
        }
        if (request.getBody().readableBytes() > NetworkAgentClient.MAX_ENCODED_REQUEST_BYTES) {
            return error(response, 413, "request_too_large", "WireGuard configuration exceeds the limit");
        }
        byte[] body = new byte[request.getBody().readableBytes()];
        request.getBody().getBytes(request.getBody().readerIndex(), body);
        try {
            JsonNode input = mapper.readTree(body);
            if (input == null || !input.isObject() || input.size() != 1 || !input.path("config").isTextual()) {
                return error(response, 400, "invalid_request", "A WireGuard configuration is required");
            }
            return client.validateWireGuard(input.get("config").textValue());
        } catch (NetworkAgentClient.Failure failure) {
            return error(response, failure.status(), failure.code(), failure.getMessage());
        } catch (IOException | RuntimeException e) {
            // Deserialization errors can quote private keys from the submitted profile.
            return error(response, 400, "invalid_request", "The request body is invalid");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static void requireAdmin(Request request) {
        if (request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT) != AppContext.ADMINCONSOLE) {
            throw new UnauthorizedException("error.token.invalidContext");
        }
    }

    private static Map<String, Object> error(Response response, int status, String code, String message) {
        response.setResponseCode(status);
        return Map.of("schemaVersion", 1, "error", Map.of("code", code, "message", message));
    }
}
