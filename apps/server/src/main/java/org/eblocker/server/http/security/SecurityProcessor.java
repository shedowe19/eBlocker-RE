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
package org.eblocker.server.http.security;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.netty.handler.codec.http.HttpHeaderNames;
import org.restexpress.Request;
import org.restexpress.exception.UnauthorizedException;
import org.restexpress.pipeline.Preprocessor;
import org.restexpress.route.Route;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class SecurityProcessor implements Preprocessor {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityProcessor.class);

    public static final String NO_AUTHENTICATION_REQUIRED = "NO_AUTHENTICATION_REQUIRED";
    public static final String APP_CONTEXT_ATTACHMENT = "appContext";
    public static final String TOKEN_INFO_ATTACHMENT = "jwt";

    private static final String AUTHN_PREFIX = "Bearer ";

    private final JsonWebTokenHandler tokenHandler;
    private final ConsoleSessionService consoleSessions;

    public SecurityProcessor(JsonWebTokenHandler tokenHandler) {
        this(tokenHandler, null);
    }

    @Inject
    public SecurityProcessor(JsonWebTokenHandler tokenHandler, ConsoleSessionService consoleSessions) {
        this.tokenHandler = tokenHandler;
        this.consoleSessions = consoleSessions;
    }

    @Override
    public void process(Request request) {
        Route route = request.getResolvedRoute();
        String routeName = route.getName();
        if (routeName == null) {
            LOG.warn("Request without route name: {}", request.getPath());
            routeName = "unknown";
        }

        if (AppContext.PUBLIC.isValidContextFor(routeName)) {
            LOG.debug("Request for PUBLIC resource {} - no authn needed", request.getPath());
            request.putAttachment(APP_CONTEXT_ATTACHMENT, AppContext.PUBLIC);
            return;
        }

        String authnHeader = request.getHeader(HttpHeaderNames.AUTHORIZATION.toString());
        LOG.debug("Authentication header present: {}", authnHeader != null);
        TokenInfo tokenInfo;
        if (authnHeader == null && consoleSessions != null && request.getHeader("Cookie") != null) {
            tokenInfo = consoleSessions.authenticate(request);
        } else if (authnHeader == null || !authnHeader.startsWith(AUTHN_PREFIX)) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Request for resource {} comes without a valid authentication header", routeName);
            }
            throw new UnauthorizedException("error.token.none");
        } else {
            // Explicit bearer requests retain their legacy contract independently of cookies.
            tokenInfo = tokenHandler.verifyToken(authnHeader.substring(AUTHN_PREFIX.length()));
        }

        if (!route.isFlagged(NO_AUTHENTICATION_REQUIRED) && !tokenInfo.isAuthenticationValid()) {
            LOG.warn("Need password to process request for resource {} in context {}", routeName, tokenInfo.getAppContext().name());
            throw new UnauthorizedException("error.credentials.invalid");
        }

        if (!tokenInfo.getAppContext().isValidContextFor(routeName)) {
            LOG.warn("Request for resource {} not allowed in context {}", routeName, tokenInfo.getAppContext().name());
            throw new UnauthorizedException("error.token.invalidContext");
        }

        // Store app context for further processors
        request.putAttachment(APP_CONTEXT_ATTACHMENT, tokenInfo.getAppContext());
        request.putAttachment(TOKEN_INFO_ATTACHMENT, tokenInfo);

        LOG.debug("Valid authn token found for {}", request.getPath());
    }

}
