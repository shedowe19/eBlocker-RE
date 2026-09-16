// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import org.eblocker.server.common.network.agent.WireGuardControlClient;
import org.eblocker.server.http.security.AppContext;
import org.eblocker.server.http.security.SecurityProcessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.UnauthorizedException;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WireGuardControlControllerTest {
    private WireGuardControlClient client;
    private WireGuardControlController controller;
    private Request request;
    private Response response;

    @BeforeEach
    void setup() {
        client = mock(WireGuardControlClient.class);
        controller = new WireGuardControlController(client, new ObjectMapper());
        request = mock(Request.class);
        response = new Response();
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.ADMINCONSOLE);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        when(request.getHeader("profileId")).thenReturn("office");
    }
    private void body(String body) { when(request.getBody()).thenReturn(Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8))); }
    private String code(Object result) { return (String) ((Map<?, ?>) ((Map<?, ?>) result).get("error")).get("code"); }

    @ParameterizedTest
    @EnumSource(value = AppContext.class, mode = EnumSource.Mode.EXCLUDE, names = "ADMINCONSOLE")
    void rejectsEveryOtherContextBeforeReadingSensitiveBody(AppContext context) {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(context);
        assertThrows(UnauthorizedException.class, () -> controller.listProfiles(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.getProfile(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.importProfile(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.connect(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.disconnect(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.cancel(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.deleteProfile(request, response));
        verifyNoInteractions(client);
        verify(request, never()).getBody();
    }

    @Test
    void fixedOperationsDelegateWithNoCachingAndPreserveCheckedEnvelope() throws Exception {
        var envelope = new ObjectMapper().readTree("{\"schemaVersion\":1,\"data\":{\"profiles\":[]}}");
        when(client.listProfiles()).thenReturn(envelope);
        assertSame(envelope, controller.listProfiles(request, response));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        body("{\"schemaVersion\":1,\"configuration\":\"private material\"}");
        controller.importProfile(request, response); verify(client).importProfile("office", "private material");
        body("{\"schemaVersion\":1}");
        controller.connect(request, response); verify(client).connect("office");
        controller.disconnect(request, response); verify(client).disconnect("office");
        controller.cancel(request, response); verify(client).cancel("office");
        body("");
        controller.deleteProfile(request, response); verify(client).delete("office");
        controller.getProfile(request, response); verify(client).profile("office");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "{\"schemaVersion\":1,\"configuration\":null}", "{\"schemaVersion\":1,\"configuration\":3}",
            "{\"schemaVersion\":1,\"configuration\":\"secret\",\"apply\":true}",
            "{\"schemaVersion\":1,\"configuration\":\"secret\",\"configuration\":\"other\"}",
            "{\"schemaVersion\":1,\"configuration\":\"secret\"} trailing", "{\"configuration\":\"secret"})
    void malformedImportsNeverReachTransportOrReflectInput(String input) {
        body(input);
        Object result = controller.importProfile(request, response);
        assertEquals(400, response.getResponseStatus().code());
        assertFalse(result.toString().contains("secret"));
        verifyNoInteractions(client);
    }

    @Test
    void actionsRequireExactVersionedBodyAndDeleteRequiresEmptyBody() {
        body("{\"schemaVersion\":2}");
        assertEquals("invalid_schema", code(controller.connect(request, response)));
        body("{\"schemaVersion\":1,\"force\":true}");
        assertEquals("invalid_request", code(controller.disconnect(request, response)));
        body("{\"schemaVersion\":1}");
        assertEquals("invalid_request", code(controller.deleteProfile(request, response)));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsCompressedOrNonJsonInputAndLargeEncodedBodies() {
        body("{\"schemaVersion\":1}");
        when(request.getHeader("Content-Encoding")).thenReturn("gzip");
        assertEquals("invalid_content_type", code(controller.connect(request, response)));
        when(request.getHeader("Content-Encoding")).thenReturn(null);
        when(request.getHeader("Content-Type")).thenReturn("text/plain");
        assertEquals("invalid_content_type", code(controller.connect(request, response)));
        when(request.getHeader("Content-Type")).thenReturn("application/json");
        body("x".repeat(WireGuardControlClient.MAX_REQUEST_BYTES + 1));
        assertEquals("request_too_large", code(controller.importProfile(request, response)));
        assertEquals(413, response.getResponseStatus().code());
        verifyNoInteractions(client);
    }

    @Test
    void missingControlServiceReturns503VersionedFailure() throws Exception {
        when(client.listProfiles()).thenThrow(new WireGuardControlClient.Failure(503, "wireguard_control_unavailable", "WireGuard control service is unavailable"));
        Object result = controller.listProfiles(request, response);
        assertEquals(503, response.getResponseStatus().code());
        assertEquals("wireguard_control_unavailable", code(result));
        assertEquals(1, ((Map<?, ?>) result).get("schemaVersion"));
        verify(client, times(1)).listProfiles();
    }
}
