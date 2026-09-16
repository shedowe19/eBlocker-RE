// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.common.network.agent.WireGuardControlClient;
import org.eblocker.server.http.security.AppContext;
import org.eblocker.server.http.security.SecurityProcessor;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.UnauthorizedException;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;

/** Administrator-only native control. Cookie requests also pass the shared Origin/CSRF processor. */
@Singleton
public class WireGuardControlController {
    private final WireGuardControlClient client;
    private final ObjectMapper mapper;

    @Inject
    public WireGuardControlController(WireGuardControlClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }
    public Object listProfiles(Request request, Response response) { return execute(request, response, client::listProfiles); }
    public Object getProfile(Request request, Response response) { return execute(request, response, () -> client.profile(id(request))); }
    public Object deleteProfile(Request request, Response response) {
        return execute(request, response, () -> {
            if (request.getBody() != null && request.getBody().readableBytes() != 0) throw WireGuardControlClient.failure(400, "invalid_request");
            return client.delete(id(request));
        });
    }
    public Object importProfile(Request request, Response response) {
        return execute(request, response, () -> {
            JsonNode input = input(request, true);
            return client.importProfile(id(request), input.get("configuration").textValue());
        });
    }
    public Object connect(Request request, Response response) {
        return execute(request, response, () -> { input(request, false); return client.connect(id(request)); });
    }
    public Object disconnect(Request request, Response response) {
        return execute(request, response, () -> { input(request, false); return client.disconnect(id(request)); });
    }
    public Object cancel(Request request, Response response) {
        return execute(request, response, () -> { input(request, false); return client.cancel(id(request)); });
    }
    private String id(Request request) { return request.getHeader("profileId"); }
    private JsonNode input(Request request, boolean configuration) throws WireGuardControlClient.Failure {
        String contentType = request.getHeader("Content-Type");
        if (request.getHeader("Content-Encoding") != null || contentType == null || !contentType.matches("(?i)application/json(?:;\\s*charset=utf-8)?"))
            throw WireGuardControlClient.failure(415, "invalid_content_type");
        if (request.getBody() == null || request.getBody().readableBytes() == 0) throw WireGuardControlClient.failure(400, "invalid_request");
        if (request.getBody().readableBytes() > WireGuardControlClient.MAX_REQUEST_BYTES) throw WireGuardControlClient.failure(413, "request_too_large");
        byte[] body = new byte[request.getBody().readableBytes()];
        request.getBody().getBytes(request.getBody().readerIndex(), body);
        try {
            JsonNode input = mapper.readTree(body);
            if (input == null || !input.isObject() || input.size() != (configuration ? 2 : 1)
                    || configuration && !input.path("configuration").isTextual()) throw WireGuardControlClient.failure(400, "invalid_request");
            if (!input.path("schemaVersion").isInt() || input.path("schemaVersion").intValue() != 1)
                throw WireGuardControlClient.failure(400, "invalid_schema");
            return input;
        } catch (IOException | RuntimeException e) { throw WireGuardControlClient.failure(400, "invalid_json"); }
        finally { Arrays.fill(body, (byte) 0); }
    }
    private Object execute(Request request, Response response, Operation operation) {
        if (request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT) != AppContext.ADMINCONSOLE)
            throw new UnauthorizedException("error.token.invalidContext");
        response.addHeader("Cache-Control", "no-store");
        try { return operation.run(); }
        catch (WireGuardControlClient.Failure failure) {
            response.setResponseCode(failure.status());
            return Map.of("schemaVersion", 1, "error", Map.of("code", failure.code(), "message", failure.getMessage()));
        }
    }
    @FunctionalInterface
    private interface Operation { Object run() throws WireGuardControlClient.Failure; }
}
