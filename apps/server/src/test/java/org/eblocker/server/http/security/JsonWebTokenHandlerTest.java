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

import org.eblocker.server.common.network.BaseURLs;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.restexpress.exception.UnauthorizedException;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

public class JsonWebTokenHandlerTest {

    private static final String HTTPS_URL = "my-https-url";

    private BaseURLs baseURLs;

    @Before
    public void init() {
        baseURLs = Mockito.mock(BaseURLs.class);
        when(baseURLs.getHttpsURL()).thenReturn(HTTPS_URL);
    }

    @Test
    public void test() {
        AppContext appContext = AppContext.CONSOLE;
        long validity = 3600;
        Date now = new Date();
        long expectedExpiry = now.getTime() / 1000L + validity;

        JsonWebTokenHandler jsonWebTokenHandler = new JsonWebTokenHandler(
                600,
                600,
                baseURLs
        );

        JsonWebToken jsonWebToken = jsonWebTokenHandler.generateToken(true, appContext, validity, true);

        assertNotNull(jsonWebToken);
        assertFalse(jsonWebToken.getToken().isEmpty());
        assertEquals(appContext, jsonWebToken.getAppContext());
        assertEquals(true, jsonWebToken.isPasswordRequired());
        // This test method should not take more than 5 seconds...
        assertTrue(Math.abs(expectedExpiry - jsonWebToken.getExpiresOn()) < 5);

        TokenInfo tokenInfo = jsonWebTokenHandler.verifyToken(jsonWebToken.getToken());

        assertNotNull(tokenInfo);
        assertEquals(appContext, tokenInfo.getAppContext());
        assertEquals(validity, tokenInfo.getTokenValiditySeconds());
        // This test method should not take more than 5 seconds...
        assertTrue(Math.abs(expectedExpiry - tokenInfo.getExpiresOn()) < 5);

    }

    @Test
    public void testSpecialTokens() {
        long systemTokenValidity = 1234L;
        long squidTokenValidity = 5678L;

        JsonWebTokenHandler jsonWebTokenHandler = new JsonWebTokenHandler(
                squidTokenValidity,
                systemTokenValidity,
                baseURLs
        );

        JsonWebToken jsonWebToken = jsonWebTokenHandler.generateSystemToken();
        TokenInfo tokenInfo = jsonWebTokenHandler.verifyToken(jsonWebToken.getToken());

        assertEquals(AppContext.SYSTEM, tokenInfo.getAppContext());
        assertEquals(systemTokenValidity, tokenInfo.getTokenValiditySeconds());

        jsonWebToken = jsonWebTokenHandler.generateSquidToken();
        tokenInfo = jsonWebTokenHandler.verifyToken(jsonWebToken.getToken());

        assertEquals(AppContext.SQUID_ERROR, tokenInfo.getAppContext());
        assertEquals(squidTokenValidity, tokenInfo.getTokenValiditySeconds());
    }

    @Test
    public void revokesOnlyContextsBoundToTheAdministratorPassword() {
        JsonWebTokenHandler handler = new JsonWebTokenHandler(600, 600, baseURLs);
        Map<AppContext, JsonWebToken> tokens = new HashMap<>();
        for (AppContext context : AppContext.values()) {
            tokens.put(context, handler.generateToken(context.isPasswordRequired(), context, 600, true));
        }
        handler.revokeAdministratorTokens();

        for (Map.Entry<AppContext, JsonWebToken> entry : tokens.entrySet()) {
            if (entry.getKey().isPasswordRequired()) {
                assertThrows(UnauthorizedException.class, () -> handler.verifyToken(entry.getValue().getToken()));
            } else {
                assertEquals(entry.getKey(), handler.verifyToken(entry.getValue().getToken()).getAppContext());
            }
            JsonWebToken fresh = handler.generateToken(entry.getKey().isPasswordRequired(), entry.getKey(), 600, true);
            assertEquals(entry.getKey(), handler.verifyToken(fresh.getToken()).getAppContext());
        }
    }

    @Test
    public void rejectsMissingOrMalformedAdministratorEpochAndExpiredTokenInfo() {
        JsonWebTokenHandler handler = new JsonWebTokenHandler(600, 600, baseURLs);
        long expires = System.currentTimeMillis() / 1000 + 600;
        assertThrows(UnauthorizedException.class,
                () -> handler.validateCurrentToken(new TokenInfo(AppContext.ADMINCONSOLE, expires, 600, true)));
        assertThrows(UnauthorizedException.class,
                () -> handler.validateCurrentToken(new TokenInfo(AppContext.CONTROLBAR, 0, 600, true)));

        Map<String, Object> claims = new HashMap<>();
        claims.put("acx", AppContext.ADMINCONSOLE);
        claims.put("exp", expires);
        claims.put("iat", expires - 600);
        claims.put("aut", true);
        for (Object invalid : new Object[] { -1, -1L, 0.0, "0", false, new HashMap<>() }) {
            claims.put(TokenInfo.ADMINISTRATOR_EPOCH_CLAIM, invalid);
            assertThrows(IllegalArgumentException.class, () -> new TokenInfo(claims));
        }
        claims.put(TokenInfo.ADMINISTRATOR_EPOCH_CLAIM, 0L);
        TokenInfo verifiedBeforeRevocation = new TokenInfo(claims);
        handler.validateCurrentToken(verifiedBeforeRevocation);
        handler.revokeAdministratorTokens();
        assertThrows(UnauthorizedException.class, () -> handler.validateCurrentToken(verifiedBeforeRevocation));
    }
}
