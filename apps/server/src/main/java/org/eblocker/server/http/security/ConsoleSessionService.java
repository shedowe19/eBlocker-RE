// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.security;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.common.data.IpAddress;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.ForbiddenException;
import org.eblocker.server.http.exceptions.restexpress.ServiceNotAvailableServiceException;
import org.restexpress.exception.UnauthorizedException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Browser-only opaque sessions. The bearer JWT and session identifier never enter response JSON. */
@Singleton
public class ConsoleSessionService {
    public static final String COOKIE_NAME = "__Host-eblocker-console";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String CONSOLE_HEADER = "X-Eblocker-Console";
    private static final int MAX_SESSIONS = 1024;
    private static final Set<String> SECURITY_HEADERS = Set.of("host", "origin", "referer", "cookie",
            "authorization", "x-csrf-token", "x-eblocker-console", "sec-fetch-site");
    private final SecurityService securityService;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Family> sessions = new HashMap<>();
    private final Object sessionsLock = new Object();

    @Inject
    public ConsoleSessionService(SecurityService securityService) {
        this.securityService = securityService;
    }

    public Status bootstrap(Request request, Response response) {
        String origin = requireSameOrigin(request);
        response.addHeader("Cache-Control", "no-store");
        Snapshot previous = find(request, origin, false, response);
        if (previous != null) {
            try {
                TokenInfo info = securityService.verifyToken(previous.token.getToken());
                return status(previous, info);
            } catch (UnauthorizedException ignored) {
                // An expired/revoked credential needs a new anonymous session state.
                // Explicit logout is checked separately by find/commit and cannot be undone here.
            }
        }
        JsonWebToken token = securityService.generateConsoleToken(AppContext.ADMINCONSOLE);
        Snapshot current = commit(previous, token, origin, true);
        setCookie(response, current);
        return status(current, securityService.verifyToken(token.getToken()));
    }

    public Status login(Request request, Response response, Credentials credentials, IpAddress ip) {
        String origin = requireSameOrigin(request);
        Snapshot previous = require(request, origin);
        checkCsrf(request, previous.csrf);
        securityService.verifyToken(previous.token.getToken());
        // Password hashing must not hold sessionsLock. The commit below rejects logout
        // or another login that completed while verification was in progress.
        JsonWebToken token = securityService.generateToken(credentials, ip, AppContext.ADMINCONSOLE);
        Snapshot current = commit(previous, token, origin, true);
        setCookie(response, current);
        return status(current, securityService.verifyToken(token.getToken()));
    }

    public Status renew(Request request, Response response) {
        String origin = requireSameOrigin(request);
        Snapshot previous = require(request, origin);
        checkCsrf(request, previous.csrf);
        TokenInfo verified = securityService.verifyToken(previous.token.getToken());
        JsonWebToken token = securityService.renewToken(verified, AppContext.ADMINCONSOLE);
        Snapshot current = commit(previous, token, origin, false);
        setCookie(response, current);
        return status(current, securityService.verifyToken(token.getToken()));
    }

    public void logout(Request request, Response response) {
        String origin = requireSameOrigin(request);
        String[] cookie = cookie(request);
        synchronized (sessionsLock) {
            Family family = cookie == null ? null : sessions.get(cookie[0]);
            if (family != null) {
                if (!family.origin.equals(origin)) throw forbidden();
                checkCsrf(request, family.csrf);
                // A preceding session ID may still be in an in-flight logout request
                // when login rotates it. Revoking the family also cancels that new login.
                family.revoked = true;
                family.revision++;
            }
        }
        clearCookie(response);
        response.addHeader("Cache-Control", "no-store");
        response.setResponseCode(204);
    }

    public TokenInfo authenticate(Request request) {
        String origin = requireSameOrigin(request);
        Snapshot session = require(request, origin);
        checkCsrf(request, session.csrf);
        TokenInfo info = securityService.verifyToken(session.token.getToken());
        synchronized (sessionsLock) {
            if (!isCurrent(session)) throw invalid();
        }
        return info;
    }

    private Snapshot require(Request request, String origin) {
        Snapshot session = find(request, origin, true, null);
        if (session == null) throw invalid();
        return session;
    }

    private Snapshot find(Request request, String origin, boolean required, Response bootstrapResponse) {
        String[] cookie = cookie(request);
        if (cookie == null) return null;
        synchronized (sessionsLock) {
            Family family = sessions.get(cookie[0]);
            if (family == null) {
                if (required) throw invalid();
                return null;
            }
            if (!family.origin.equals(origin)) throw forbidden();
            if (family.revoked) {
                // A lost logout response can leave an HttpOnly cookie in the browser.
                // Clear it on bootstrap without ever reviving its revoked family.
                if (bootstrapResponse != null) clearCookie(bootstrapResponse);
                throw invalid();
            }
            if (!constantTimeEquals(family.sessionId, cookie[1])) throw invalid();
            return family.snapshot();
        }
    }

    private Snapshot commit(Snapshot previous, JsonWebToken token, String origin, boolean rotate) {
        // Validate the epoch again after password verification and before publishing.
        TokenInfo tokenInfo = securityService.verifyToken(token.getToken());
        synchronized (sessionsLock) {
            Family family;
            if (previous == null) {
                long now = System.currentTimeMillis() / 1000;
                sessions.values().removeIf(session -> session.retainUntil <= now);
                if (sessions.size() >= MAX_SESSIONS) {
                    // Do not evict authenticated sessions to make room for anonymous probes.
                    String disposable = sessions.entrySet().stream()
                            .filter(entry -> entry.getValue().revoked || entry.getValue().token.isPasswordRequired()
                                    && !entry.getValue().authenticated)
                            .map(Map.Entry::getKey).findFirst().orElse(null);
                    if (disposable != null) sessions.remove(disposable);
                    else throw new ServiceNotAvailableServiceException("error.session.capacity");
                }
                family = new Family(secret(), secret(), origin);
                sessions.put(family.id, family);
            } else {
                if (!isCurrent(previous)) throw invalid();
                family = sessions.get(previous.id);
            }
            if (rotate || family.sessionId == null) family.sessionId = secret();
            family.token = token;
            family.authenticated = tokenInfo.isAuthenticationValid();
            family.retainUntil = token.getExpiresOn() + 300;
            family.revision++;
            return family.snapshot();
        }
    }

    private boolean isCurrent(Snapshot snapshot) {
        Family family = sessions.get(snapshot.id);
        return family != null && !family.revoked && family.revision == snapshot.revision;
    }

    private Status status(Snapshot snapshot, TokenInfo info) {
        return new Status(AppContext.ADMINCONSOLE, info.isAuthenticationValid(), snapshot.token.isPasswordRequired(),
                snapshot.token.getExpiresOn(), snapshot.csrf);
    }

    private void clearCookie(Response response) {
        response.addHeader("Set-Cookie", COOKIE_NAME + "=; Path=/; Max-Age=0; Secure; HttpOnly; SameSite=Strict");
    }

    private void setCookie(Response response, Snapshot snapshot) {
        long remaining = Math.max(0, snapshot.token.getExpiresOn() - System.currentTimeMillis() / 1000);
        response.addHeader("Set-Cookie", COOKIE_NAME + "=" + snapshot.id + "." + snapshot.sessionId
                + "; Path=/; Max-Age=" + remaining + "; Secure; HttpOnly; SameSite=Strict");
        response.addHeader("Cache-Control", "no-store");
    }

    private String requireSameOrigin(Request request) {
        // RestExpress's legacy URL parser adds query parameters to headers. Never
        // accept security metadata originating from those parameters.
        Map<String, String> query = request.getQueryStringMap();
        if (query != null && query.keySet().stream().anyMatch(key -> SECURITY_HEADERS.contains(key.toLowerCase(Locale.ROOT)))) {
            throw forbidden();
        }
        if (!Boolean.TRUE.equals(request.getAttachment("transport.secure"))) {
            throw new ForbiddenException("error.session.httpsRequired");
        }
        if (!"1".equals(singleHeader(request, CONSOLE_HEADER))) throw forbidden();
        String fetchSite = singleHeader(request, "Sec-Fetch-Site");
        if (fetchSite != null && !"same-origin".equals(fetchSite)) throw forbidden();
        try {
            String host = singleHeader(request, "Host");
            if (host == null || host.length() > 512) throw forbidden();
            URI target = new URI("https://" + host);
            if (target.getHost() == null || target.getRawUserInfo() != null || target.getRawQuery() != null
                    || target.getRawFragment() != null || !target.getRawPath().isEmpty()) throw forbidden();
            String origin = singleHeader(request, "Origin");
            String referer = singleHeader(request, "Referer");
            if (origin == null && referer == null) throw forbidden();
            URI source = new URI(origin == null ? referer : origin);
            if (!"https".equals(source.getScheme()) || source.getHost() == null || source.getRawUserInfo() != null
                    || !source.getHost().equalsIgnoreCase(target.getHost()) || port(source) != port(target)
                    || origin != null && (source.getRawQuery() != null || source.getRawFragment() != null
                    || !source.getRawPath().isEmpty())) throw forbidden();
            return "https://" + target.getHost().toLowerCase(Locale.ROOT) + ":" + port(target);
        } catch (java.net.URISyntaxException e) {
            throw forbidden();
        }
    }

    private int port(URI uri) {
        int port = uri.getPort();
        if (port < -1 || port == 0 || port > 65535) throw forbidden();
        return port == -1 ? 443 : port;
    }

    private String singleHeader(Request request, String name) {
        List<String> values = request.getHeaders(name);
        if (values != null && values.size() > 1) throw forbidden();
        return request.getHeader(name);
    }

    private String[] cookie(Request request) {
        String header = singleHeader(request, "Cookie");
        if (header == null) return null;
        if (header.length() > 8192) throw invalid();
        String value = null;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(COOKIE_NAME + "=")) {
                if (value != null) throw invalid();
                value = trimmed.substring(COOKIE_NAME.length() + 1);
            }
        }
        if (value == null) return null;
        if (!value.matches("[A-Za-z0-9_-]{43}\\.[A-Za-z0-9_-]{43}")) throw invalid();
        return value.split("\\.");
    }

    private void checkCsrf(Request request, String expected) {
        if (!constantTimeEquals(expected, singleHeader(request, CSRF_HEADER))) throw forbidden();
    }

    private boolean constantTimeEquals(String expected, String actual) {
        return actual != null && actual.length() == 43
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }

    private String secret() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private UnauthorizedException invalid() { return new UnauthorizedException("error.token.invalid"); }
    private ForbiddenException forbidden() { return new ForbiddenException("error.session.csrf"); }

    public record Status(AppContext appContext, boolean authenticated, boolean passwordRequired, long expiresOn, String csrfToken) { }

    private record Snapshot(String id, String sessionId, String csrf, String origin, JsonWebToken token, long revision) { }

    private static final class Family {
        private final String id;
        private final String csrf;
        private final String origin;
        private String sessionId;
        private JsonWebToken token;
        private long revision;
        private long retainUntil;
        private boolean revoked;
        private boolean authenticated;

        private Family(String id, String csrf, String origin) {
            this.id = id;
            this.csrf = csrf;
            this.origin = origin;
        }

        private Snapshot snapshot() { return new Snapshot(id, sessionId, csrf, origin, token, revision); }
    }
}
