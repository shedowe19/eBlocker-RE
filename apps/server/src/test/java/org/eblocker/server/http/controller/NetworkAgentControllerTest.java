package org.eblocker.server.http.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import org.eblocker.server.common.network.agent.NetworkAgentClient;
import org.eblocker.server.http.security.AppContext;
import org.eblocker.server.http.security.JsonWebTokenHandler;
import org.eblocker.server.http.security.SecurityProcessor;
import org.eblocker.server.http.security.TokenInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.UnauthorizedException;
import org.restexpress.route.Route;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkAgentControllerTest {
    private NetworkAgentClient client;
    private NetworkAgentController controller;
    private Request request;
    private Response response;

    @BeforeEach
    void setup() {
        client = mock(NetworkAgentClient.class);
        controller = new NetworkAgentController(client, new ObjectMapper());
        request = mock(Request.class);
        response = mock(Response.class);
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.ADMINCONSOLE);
        when(request.getHeader("Content-Type")).thenReturn("application/json");
    }

    @ParameterizedTest
    @EnumSource(value = AppContext.class, mode = EnumSource.Mode.EXCLUDE, names = "ADMINCONSOLE")
    void rejectsOtherApplicationContextsBeforeReadingPrivateInput(AppContext context) {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(context);
        assertThrows(UnauthorizedException.class, () -> controller.getStatus(request, response));
        assertThrows(UnauthorizedException.class, () -> controller.validateWireGuard(request, response));
        verifyNoInteractions(client);
        verify(request, never()).getBody();
    }

    @Test
    void missingSecurityAttachmentIsUnauthorized() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(null);
        assertThrows(UnauthorizedException.class, () -> controller.getStatus(request, response));
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"adminconsole.network.agent.status.get", "adminconsole.wireguard.validate.post"})
    void securityProcessorRequiresAnAuthenticatedAdminToken(String routeName) {
        JsonWebTokenHandler tokenHandler = mock(JsonWebTokenHandler.class);
        SecurityProcessor processor = new SecurityProcessor(tokenHandler);
        Route route = mock(Route.class);
        when(route.getName()).thenReturn(routeName);
        when(request.getResolvedRoute()).thenReturn(route);
        assertThrows(UnauthorizedException.class, () -> processor.process(request));
        when(request.getHeader("authorization")).thenReturn("Bearer token");
        when(tokenHandler.verifyToken("token")).thenReturn(new TokenInfo(AppContext.ADMINCONSOLE, 100, 100, false));
        assertThrows(UnauthorizedException.class, () -> processor.process(request));
        when(tokenHandler.verifyToken("token")).thenReturn(new TokenInfo(AppContext.DASHBOARD, 100, 100, true));
        assertThrows(UnauthorizedException.class, () -> processor.process(request));
        when(tokenHandler.verifyToken("token")).thenReturn(new TokenInfo(AppContext.ADMINCONSOLE, 100, 100, true));
        processor.process(request);
        verify(request).putAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT, AppContext.ADMINCONSOLE);
    }

    @Test
    void unavailableAgentReturns503WithoutInventingStatus() throws Exception {
        when(client.status()).thenThrow(new NetworkAgentClient.Failure(503, "network_agent_unavailable", "Network agent is unavailable"));
        Object result = controller.getStatus(request, response);
        verify(response).setResponseCode(503);
        verify(response).addHeader("Cache-Control", "no-store");
        assertFalse(((Map<?, ?>) result).containsKey("data"));
        assertEquals("network_agent_unavailable", ((Map<?, ?>) ((Map<?, ?>) result).get("error")).get("code"));
    }

    @Test
    void validationReadsOnlyTheReadableSliceAndPreservesSharedBackingBytes() throws Exception {
        byte[] profile = "{\"config\":\"private material\"}".getBytes(StandardCharsets.UTF_8);
        byte[] backing = new byte[profile.length + 40];
        java.util.Arrays.fill(backing, (byte) 'x');
        System.arraycopy(profile, 0, backing, 12, profile.length);
        byte[] original = backing.clone();
        io.netty.buffer.ByteBuf slice = Unpooled.wrappedBuffer(backing).slice(8, profile.length + 12);
        slice.setIndex(4, profile.length + 4);
        when(request.getBody()).thenReturn(slice);

        controller.validateWireGuard(request, response);

        verify(client).validateWireGuard("private material");
        assertArrayEquals(original, backing);
        verify(request, never()).getBodyAsBytes();
        verify(response).addHeader("Cache-Control", "no-store");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"config\":7}", "{\"config\":null}", "{}", "[]",
            "{\"config\":\"secret\",\"apply\":true}", "{\"config\":\"secret\",\"config\":\"other\"}",
            "{\"config\":\"secret\"} trailing", "{\"config\":\"secret"})
    void invalidRequestsAreRedactedAndNeverReachTheAgent(String body) {
        body(body);
        Object result = controller.validateWireGuard(request, response);
        verify(response).setResponseCode(400);
        assertFalse(result.toString().contains("secret"));
        verifyNoInteractions(client);
    }

    @Test
    void rejectsOversizedJsonBeforeAllocatingOrParsingACopy() {
        when(request.getBody()).thenReturn(Unpooled.wrappedBuffer(new byte[NetworkAgentClient.MAX_ENCODED_REQUEST_BYTES + 1]));
        controller.validateWireGuard(request, response);
        verify(response).setResponseCode(413);
        verify(request, never()).getBodyAsBytes();
        verifyNoInteractions(client);
    }

    @Test
    void requiresJsonContentType() {
        when(request.getHeader("Content-Type")).thenReturn("text/plain");
        controller.validateWireGuard(request, response);
        verify(response).setResponseCode(415);
        verifyNoInteractions(client);
    }

    private byte[] body(String text) {
        byte[] copy = text.getBytes(StandardCharsets.UTF_8);
        when(request.getBody()).thenReturn(Unpooled.wrappedBuffer(copy.clone()));
        return copy;
    }
}
