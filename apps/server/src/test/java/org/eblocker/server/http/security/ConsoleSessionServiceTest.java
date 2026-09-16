// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.eblocker.server.common.data.IpAddress;
import org.eblocker.server.common.network.BaseURLs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.ForbiddenException;
import org.restexpress.exception.UnauthorizedException;
import org.restexpress.route.Route;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConsoleSessionServiceTest {
    private SecurityService security;
    private JsonWebTokenHandler tokens;
    private ConsoleSessionService sessions;
    private static final IpAddress IP = IpAddress.parse("192.168.1.2");
    private static final Credentials CREDENTIALS = new Credentials("password", null);

    @BeforeEach
    void setup() {
        BaseURLs urls = mock(BaseURLs.class);
        when(urls.getHttpsURL()).thenReturn("https://device.example:3443");
        tokens = new JsonWebTokenHandler(300, 300, urls);
        security = mock(SecurityService.class);
        when(security.generateConsoleToken(AppContext.ADMINCONSOLE)).thenAnswer(call -> token(false));
        when(security.generateToken(any(Credentials.class), any(IpAddress.class), eq(AppContext.ADMINCONSOLE)))
                .thenAnswer(call -> token(true));
        when(security.verifyToken(anyString())).thenAnswer(call -> tokens.verifyToken(call.getArgument(0)));
        when(security.renewToken(any(TokenInfo.class), eq(AppContext.ADMINCONSOLE))).thenAnswer(call -> {
            TokenInfo info = call.getArgument(0);
            tokens.validateCurrentToken(info);
            return token(info.isAuthenticationValid());
        });
        sessions = new ConsoleSessionService(security);
    }

    private JsonWebToken token(boolean authenticated) {
        return tokens.generateToken(true, AppContext.ADMINCONSOLE, 600, authenticated);
    }

    private Request request(String cookie, String csrf) {
        return request("/api/adminconsole/authentication/session", cookie, csrf, Map.of());
    }

    private Request request(String path, String cookie, String csrf, Map<String, String> overrides) {
        DefaultFullHttpRequest http = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, path);
        http.headers().set("Host", "device.example:3443");
        http.headers().set("Origin", "https://device.example:3443");
        http.headers().set(ConsoleSessionService.CONSOLE_HEADER, "1");
        if (cookie != null) http.headers().set("Cookie", cookie);
        if (csrf != null) http.headers().set(ConsoleSessionService.CSRF_HEADER, csrf);
        overrides.forEach((name, value) -> { if (value.isEmpty()) http.headers().remove(name); else http.headers().set(name, value); });
        Request request = new Request(http, null);
        request.putAttachment("transport.secure", true);
        return request;
    }

    private State bootstrap() {
        Response response = new Response();
        ConsoleSessionService.Status status = sessions.bootstrap(request(null, null), response);
        return new State(cookie(response), status.csrfToken());
    }

    private String cookie(Response response) { return response.getHeader("Set-Cookie").split(";", 2)[0]; }
    private record State(String cookie, String csrf) { }

    @Test
    void bootstrapReturnsOnlyMetadataAndSetsSecureOpaqueCookie() throws Exception {
        Response response = new Response();
        ConsoleSessionService.Status status = sessions.bootstrap(request(null, null), response);
        assertFalse(status.authenticated());
        assertTrue(status.passwordRequired());
        assertEquals(AppContext.ADMINCONSOLE, status.appContext());
        String header = response.getHeader("Set-Cookie");
        assertTrue(header.startsWith("__Host-eblocker-console="));
        assertTrue(header.contains("; Path=/; Max-Age="));
        assertTrue(header.endsWith("; Secure; HttpOnly; SameSite=Strict"));
        assertFalse(header.contains("Domain="));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        String json = new ObjectMapper().writeValueAsString(status);
        assertFalse(json.contains("\"token\""));
        assertFalse(json.contains(cookie(response).split("=", 2)[1]));
        assertEquals(5, new ObjectMapper().readTree(json).size());
    }

    @Test
    void existingBootstrapPreservesSessionAndIsNotCacheable() {
        State state = bootstrap();
        Response response = new Response();
        assertEquals(state.csrf, sessions.bootstrap(request(state.cookie, null), response).csrfToken());
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertNull(response.getHeader("Set-Cookie"));
        verify(security, times(1)).generateConsoleToken(AppContext.ADMINCONSOLE);
    }

    @Test
    void tlsCannotBeSpoofedByHostForwardedOrQueryParameters() {
        Request insecure = request(null, null);
        insecure.putAttachment("transport.secure", false);
        insecure.addHeader("X-Forwarded-Proto", "https");
        assertThrows(ForbiddenException.class, () -> sessions.bootstrap(insecure, new Response()));
        for (String name : new String[]{"Host", "Origin", "Referer", "Cookie", "Authorization", "X-CSRF-Token", "X-Eblocker-Console", "Sec-Fetch-Site"}) {
            Request spoofed = request("/api/adminconsole/authentication/session?" + name + "=spoofed", null, null, Map.of());
            assertThrows(ForbiddenException.class, () -> sessions.bootstrap(spoofed, new Response()), name);
        }
    }

    @Test
    void rejectsForeignOrMalformedSourceAndDuplicateSecurityHeaders() {
        for (String origin : new String[]{"https://device.example.attacker:3443", "https://device.example", "http://device.example:3443", "null", "https://user@device.example:3443", "https://device.example:3443/path", "https://device.example:3443?x=y"}) {
            Request foreign = request("/", null, null, Map.of("Origin", origin));
            assertThrows(ForbiddenException.class, () -> sessions.bootstrap(foreign, new Response()), origin);
        }
        for (Map<String, String> override : java.util.List.of(Map.of("Origin", ""), Map.of("Host", ""), Map.of("X-Eblocker-Console", ""), Map.of("Sec-Fetch-Site", "cross-site"))) {
            assertThrows(ForbiddenException.class, () -> sessions.bootstrap(request("/", null, null, override), new Response()));
        }
        Request duplicate = request(null, null);
        duplicate.addHeader("Origin", "https://device.example:3443");
        assertThrows(ForbiddenException.class, () -> sessions.bootstrap(duplicate, new Response()));
    }

    @Test
    void sameOriginRefererSupportsGetRequestsWithoutOriginHeader() {
        Request request = request("/", null, null, Map.of("Origin", "", "Referer", "https://device.example:3443/next/"));
        assertNotNull(sessions.bootstrap(request, new Response()));
    }

    @Test
    void csrfIsRequiredForAuthenticationLoginRenewAndLogout() {
        State state = bootstrap();
        for (String csrf : new String[]{null, "x".repeat(43)}) {
            assertThrows(ForbiddenException.class, () -> sessions.authenticate(request(state.cookie, csrf)));
            assertThrows(ForbiddenException.class, () -> sessions.login(request(state.cookie, csrf), new Response(), CREDENTIALS, IP));
            assertThrows(ForbiddenException.class, () -> sessions.renew(request(state.cookie, csrf), new Response()));
            assertThrows(ForbiddenException.class, () -> sessions.logout(request(state.cookie, csrf), new Response()));
        }
    }

    @Test
    void loginRotatesCookieButRetainsCsrfAndRenewKeepsCookie() {
        State state = bootstrap();
        Response login = new Response();
        var status = sessions.login(request(state.cookie, state.csrf), login, CREDENTIALS, IP);
        assertTrue(status.authenticated());
        assertEquals(state.csrf, status.csrfToken());
        assertNotEquals(state.cookie, cookie(login));
        assertThrows(UnauthorizedException.class, () -> sessions.authenticate(request(state.cookie, state.csrf)));
        assertTrue(sessions.authenticate(request(cookie(login), state.csrf)).isAuthenticationValid());
        Response renew = new Response();
        assertTrue(sessions.renew(request(cookie(login), state.csrf), renew).authenticated());
        assertEquals(cookie(login), cookie(renew));
    }

    @Test
    void oldCookieLogoutRevokesRotatedLoginAndCannotBeBootstrappedBack() {
        State state = bootstrap();
        Response login = new Response();
        sessions.login(request(state.cookie, state.csrf), login, CREDENTIALS, IP);
        Response logout = new Response();
        sessions.logout(request(state.cookie, state.csrf), logout);
        assertEquals(204, logout.getResponseStatus().code());
        assertTrue(logout.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertThrows(UnauthorizedException.class, () -> sessions.authenticate(request(cookie(login), state.csrf)));
        assertThrows(UnauthorizedException.class, () -> sessions.bootstrap(request(cookie(login), null), new Response()));
        assertNotNull(sessions.bootstrap(request(null, null), new Response()));
    }

    @Test
    void bootstrapClearsCookieLeftBehindByLostLogoutResponseWithoutRevivingIt() {
        State state = bootstrap();
        sessions.logout(request(state.cookie, state.csrf), new Response()); // browser loses this response
        Response rejected = new Response();
        assertThrows(UnauthorizedException.class, () -> sessions.bootstrap(request(state.cookie, null), rejected));
        assertEquals("__Host-eblocker-console=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict", rejected.getHeader("Set-Cookie"));
        assertEquals("no-store", rejected.getHeader("Cache-Control"));
        assertThrows(UnauthorizedException.class, () -> sessions.authenticate(request(state.cookie, state.csrf)));
        Response fresh = new Response();
        assertFalse(sessions.bootstrap(request(null, null), fresh).authenticated());
        assertNotEquals(state.cookie, cookie(fresh));
    }

    @Test
    void logoutDuringPasswordVerificationPreventsLateLoginCommit() throws Exception {
        State state = bootstrap();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(security.generateToken(any(), any(), eq(AppContext.ADMINCONSOLE))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return token(true);
        });
        var executor = Executors.newSingleThreadExecutor();
        Response login = new Response();
        try {
            var result = executor.submit(() -> sessions.login(request(state.cookie, state.csrf), login, CREDENTIALS, IP));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            sessions.logout(request(state.cookie, state.csrf), new Response());
            resume.countDown();
            assertInstanceOf(UnauthorizedException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS)).getCause());
            assertNull(login.getHeader("Set-Cookie"));
        } finally { resume.countDown(); executor.shutdownNow(); }
    }

    @Test
    void logoutDuringRenewalPreventsLateRenewalCommit() throws Exception {
        State state = bootstrap();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        when(security.renewToken(any(), eq(AppContext.ADMINCONSOLE))).thenAnswer(call -> {
            entered.countDown();
            assertTrue(resume.await(5, TimeUnit.SECONDS));
            return token(false);
        });
        var executor = Executors.newSingleThreadExecutor();
        Response renew = new Response();
        try {
            var result = executor.submit(() -> sessions.renew(request(state.cookie, state.csrf), renew));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            sessions.logout(request(state.cookie, state.csrf), new Response());
            resume.countDown();
            assertInstanceOf(UnauthorizedException.class, assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS)).getCause());
            assertNull(renew.getHeader("Set-Cookie"));
        } finally { resume.countDown(); executor.shutdownNow(); }
    }

    @Test
    void passwordRevocationInvalidatesCookieButAllowsFreshPreauthBootstrap() {
        State state = bootstrap();
        Response login = new Response();
        sessions.login(request(state.cookie, state.csrf), login, CREDENTIALS, IP);
        tokens.revokeAdministratorTokens();
        assertThrows(UnauthorizedException.class, () -> sessions.authenticate(request(cookie(login), state.csrf)));
        Response recovery = new Response();
        assertFalse(sessions.bootstrap(request(cookie(login), null), recovery).authenticated());
        assertNotEquals(cookie(login), cookie(recovery));
    }

    @Test
    void unauthenticatedCookieCanQueryWaitButCannotAccessProtectedRoutes() {
        State state = bootstrap();
        Request request = request(state.cookie, state.csrf);
        Route route = mock(Route.class);
        when(route.getName()).thenReturn("adminconsole.authentication.wait.route");
        when(route.isFlagged(SecurityProcessor.NO_AUTHENTICATION_REQUIRED)).thenReturn(true);
        request.setResolvedRoute(route);
        SecurityProcessor processor = new SecurityProcessor(tokens, sessions);
        processor.process(request);
        assertEquals(AppContext.ADMINCONSOLE, request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT));
        when(route.isFlagged(SecurityProcessor.NO_AUTHENTICATION_REQUIRED)).thenReturn(false);
        assertThrows(UnauthorizedException.class, () -> processor.process(request));
    }

    @Test
    void explicitBearerRemainsCompatibleWithoutCookieCsrfOrTls() {
        Request request = request("invalid cookie", null);
        request.putAttachment("transport.secure", false);
        request.addHeader("Authorization", "Bearer " + token(true).getToken());
        Route route = mock(Route.class);
        when(route.getName()).thenReturn("adminconsole.devices.get");
        request.setResolvedRoute(route);
        new SecurityProcessor(tokens, sessions).process(request);
        assertEquals(AppContext.ADMINCONSOLE, request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT));
    }

    @Test
    void expiredAndMalformedCookiesCannotAuthenticate() {
        State state = bootstrap();
        doThrow(new UnauthorizedException("error.token.invalid")).when(security).verifyToken(anyString());
        assertThrows(UnauthorizedException.class, () -> sessions.authenticate(request(state.cookie, state.csrf)));
        for (String cookie : new String[]{"__Host-eblocker-console=x", state.cookie + "; " + state.cookie}) {
            assertThrows(UnauthorizedException.class, () -> sessions.authenticate(request(cookie, state.csrf)));
        }
    }
}
