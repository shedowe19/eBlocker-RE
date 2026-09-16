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

import org.eblocker.server.common.data.IpAddress;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.events.EventLogger;
import org.eblocker.server.common.data.events.EventType;
import org.eblocker.server.common.network.BaseURLs;
import org.eblocker.server.common.system.ScriptRunner;
import org.eblocker.server.common.update.AutomaticUpdater;
import org.eblocker.server.common.update.SystemUpdater;
import org.eblocker.server.common.update.SystemUpdater.State;
import org.eblocker.server.http.service.DeviceService;
import org.eblocker.server.http.service.EmbeddedRedisServiceTestBase;
import org.eblocker.server.http.service.ProductInfoService;
import org.eblocker.server.http.service.UserService;
import org.eblocker.server.http.controller.impl.AuthenticationControllerImpl;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.restexpress.exception.UnauthorizedException;
import org.restexpress.exception.BadRequestException;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.route.Route;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Base64;
import java.util.Map;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;

public class SecurityServiceTest extends EmbeddedRedisServiceTestBase {

    private static final IpAddress IP_ADDRESS = IpAddress.parse("1.2.3.4");

    @Mock
    private BaseURLs baseURLs;
    private final long passwordResetValiditySeconds = 3600;
    private final long passwordResetGracePeriodSeconds = 3600;
    @Mock
    private SystemUpdater systemUpdater;
    @Mock
    private AutomaticUpdater automaticUpdater;
    @Mock
    private ScriptRunner scriptRunner;
    @Mock
    private EventLogger eventLogger;
    @Mock
    private DeviceService deviceService;
    @Mock
    UserService userService;
    @Mock
    ProductInfoService productInfoService;

    Clock clock = Mockito.mock(Clock.class);

    @Rule
    public MockitoRule mockitoRule = MockitoJUnit.rule();

    @Override
    protected void doSetup() {
        super.doSetup();
        when(baseURLs.getHttpURL()).thenReturn("http://test.eblocker.com");
        when(baseURLs.getHttpsURL()).thenReturn("https://test.eblocker.com");
    }

    @Test
    public void test_generateAndVerifyJWT_ok() {
        generateAndVerifyJWT_ok(s -> s.generateToken(new Credentials(null, null), IP_ADDRESS, AppContext.defaultValue()));
    }

    private void generateAndVerifyJWT_ok(Function<SecurityService, JsonWebToken> generator) {
        SecurityService securityService = createSecurityService();

        JsonWebToken token = generator.apply(securityService);

        assertNotNull(token);

        String encodedToken = token.getToken();

        TokenInfo verified = securityService.verifyToken(encodedToken);

        assertNotNull(verified);
        assertEquals(token.getAppContext(), verified.getAppContext());
        assertEquals(token.getExpiresOn(), verified.getExpiresOn());
    }

    @Test
    public void test_generateAndVerifyJWT_expired() {
        SecurityService securityService = createSecurityService(-3600);

        JsonWebToken token = securityService.generateToken(new Credentials(null, null), IP_ADDRESS, AppContext.defaultValue());

        assertNotNull(token);

        String encodedToken = token.getToken();

        try {
            securityService.verifyToken(encodedToken);
            fail("Expected UnauthorizedException");

        } catch (UnauthorizedException e) {
            assertEquals("error.token.invalid", e.getMessage());
        }
    }

    @Test
    public void test_generateAndVerifyJWT_corrupt() {
        SecurityService securityService = createSecurityService();
        SecurityService otherSecurityService = createSecurityService(3600);

        // use other security service (with different secret) to generate token
        JsonWebToken token = otherSecurityService.generateToken(new Credentials(null, null), IP_ADDRESS, AppContext.defaultValue());

        assertNotNull(token);

        String encodedToken = token.getToken();

        try {
            securityService.verifyToken(encodedToken);
            fail("Expected UnauthorizedException");

        } catch (UnauthorizedException e) {
            assertEquals("error.token.invalid", e.getMessage());
        }
    }

    @Test
    public void test_generateAndVerifyJWT_randomSecret_ok() {
        SecurityService securityService = createSecurityService(3600);

        JsonWebToken token = securityService.generateToken(new Credentials(null, null), IP_ADDRESS, AppContext.defaultValue());

        assertNotNull(token);

        String encodedToken = token.getToken();

        TokenInfo verified = securityService.verifyToken(encodedToken);

        assertNotNull(verified);
        assertEquals(token.getAppContext(), verified.getAppContext());
        assertEquals(token.getExpiresOn(), verified.getExpiresOn());
    }

    @Test
    public void test_renewJWT_ok() throws InterruptedException {
        SecurityService securityService = createSecurityService(3600); // within grace period

        JsonWebToken token = securityService.generateToken(new Credentials(null, null), IP_ADDRESS, AppContext.defaultValue());

        Thread.sleep(1000L); // to get a measurable time difference to renewed token

        JsonWebToken renewed = securityService.renewToken(new TokenInfo(token.getAppContext(), token.getExpiresOn(), 3600, true));

        assertNotNull(renewed);

        // renewed is valid at least one second longer than original token
        assertTrue(1 <= renewed.getExpiresOn() - token.getExpiresOn());
    }

    @Test
    public void test_setVerifyRemovePassword_ok() {
        SecurityService securityService = createSecurityService();
        Instant first = Instant.ofEpochSecond(1000L);// Trying correct
        Instant second = first.plusSeconds(1000L);// Testing wrong password, 1
        Instant third = second.plusSeconds(1000L);// Testing wrong password, 1
        Instant fourth = third.plusSeconds(1000L);// Changing password
        Instant fifth = fourth.plusSeconds(1000L);// Trying correct
        Instant sixth = fifth.plusSeconds(1000L); // Testing wrong password, 1
        Instant seventh = sixth.plusSeconds(1000L);// Testing wrong password, 1
        Instant eighth = seventh.plusSeconds(1000L);// Failing to remove
        Instant ninth = eighth.plusSeconds(1000L);// Failing to remove
        Instant tenth = ninth.plusSeconds(1000L);// Successfully removed
        when(clock.instant()).thenReturn(first, second, third, fourth, fifth, sixth, seventh, eighth, ninth, tenth);

        IpAddress[] ipAddresses = { IpAddress.parse("1.2.3.4"), IpAddress.parse("2.3.4.5"), IpAddress.parse("3.4.5.6"), IpAddress.parse("4.5.6.7") };

        // No password set, all credentials are valid. Even <null>.
        securityService.verifyPassword(new Credentials(null, null), ipAddresses[0]);
        securityService.verifyPassword(new Credentials("", null), ipAddresses[0]);
        securityService.verifyPassword(new Credentials("mY-s3creT-p@ssw0rD", null), ipAddresses[0]);

        // Set new password
        securityService.setPassword(new Credentials(null, "mY-s3creT-p@ssw0rD"), ipAddresses[0]);
        // verify change is logged!
        Mockito.verify(eventLogger).log(Mockito.any());
        Mockito.reset(eventLogger);

        // Now, only the correct password is valid
        securityService.verifyPassword(new Credentials("mY-s3creT-p@ssw0rD", null), ipAddresses[0]);

        // All other passwords are not accepted
        assertInvalidCredentials(securityService, "wrong-password", ipAddresses[0]);
        assertInvalidCredentials(securityService, "", ipAddresses[0]);
        assertInvalidCredentials(securityService, null, ipAddresses[0]);

        // Cannot set new password, w/o providing the old one
        try {
            securityService.setPassword(new Credentials(null, "new-password"), ipAddresses[0]);
            fail("Expected UnauthorizedException");
        } catch (UnauthorizedException e) {
            assertEquals("error.credentials.invalid", e.getMessage());
        }

        // But can set new password, if old one is correct
        securityService.setPassword(new Credentials("mY-s3creT-p@ssw0rD", "new-password"), ipAddresses[1]);
        // verify change is logged!
        Mockito.verify(eventLogger).log(Mockito.any());
        Mockito.reset(eventLogger);

        // Now, only the new password is valid
        securityService.verifyPassword(new Credentials("new-password", null), ipAddresses[1]);

        // All other passwords are not accepted
        assertInvalidCredentials(securityService, "wrong-password", ipAddresses[1]);
        assertInvalidCredentials(securityService, "", ipAddresses[1]);
        assertInvalidCredentials(securityService, null, ipAddresses[1]);

        // Cannot remove password, w/o providing the current one
        try {
            securityService.removePassword(new Credentials("xyz-abc", null), ipAddresses[2]);
            fail("Expected UnauthorizedException");
        } catch (UnauthorizedException e) {
            assertEquals("error.credentials.invalid", e.getMessage());
        }

        // But can remove password, if old one is correct
        securityService.removePassword(new Credentials("new-password", null), ipAddresses[3]);
        // verify change is logged!
        Mockito.verify(eventLogger).log(Mockito.any());

        // Now, all passwords are accepted again
        securityService.verifyPassword(new Credentials(null, null), ipAddresses[3]);
        securityService.verifyPassword(new Credentials("", null), ipAddresses[3]);
        securityService.verifyPassword(new Credentials("mY-s3creT-p@ssw0rD", null), ipAddresses[3]);
        securityService.verifyPassword(new Credentials("wrong-password", null), ipAddresses[3]);

    }

    @Test(expected = UnauthorizedException.class)
    public void testAttemptTooSoonRejected() {
        SecurityService securityService = createSecurityService();
        Instant first = Instant.ofEpochSecond(1000L);// Trying wrong password
        Instant second = first.plusSeconds(0L);// Setting wait period for wrong password
        Instant third = second.plusSeconds(1L);// Testing how long to wait
        Instant fourth = third.plusSeconds(1L);// Correct password rejected, entered too soon
        Instant fifth = fourth.plusSeconds(0L);// Correct password rejected, entered too soon
        when(clock.instant()).thenReturn(first, second, third, fourth, fifth);

        // Set new password
        securityService.setPassword(new Credentials(null, "mY-s3creT-p@ssw0rD"), IP_ADDRESS);
        // verify change is logged!
        Mockito.verify(eventLogger).log(Mockito.any());
        Mockito.reset(eventLogger);

        // First attempt at verification, everything allright (i.e. rejected due to wrong password)
        try {
            securityService.verifyPassword(new Credentials("n0t-mY-s3creT-p@ssw0rD", null), IP_ADDRESS);
            fail("Expected UnauthorizedException");
        } catch (Exception e) {
            // Expected
        }

        // We need to wait
        assertTrue(securityService.passwordEntryInSeconds(IP_ADDRESS) >= 1);

        // Second attempt at verification without waiting - even correct password is rejected
        securityService.verifyPassword(new Credentials("mY-s3creT-p@ssw0rD", null), IP_ADDRESS);
    }

    @Test(expected = UnauthorizedException.class)
    public void testSystemAppContextRejected() {
        SecurityService securityService = createSecurityService();
        securityService.generateToken(AppContext.SYSTEM); // for internal use only
    }

    @Test
    public void testAttemptFromDifferentIpAccepted() {
        SecurityService securityService = createSecurityService();
        Instant first = Instant.ofEpochSecond(1000L);// Trying wrong password
        Instant second = first.plusSeconds(0L);// Setting wait period for wrong password
        Instant third = second.plusSeconds(1L);// Testing how long to wait
        Instant fourth = third.plusSeconds(1000L);// Successful login
        when(clock.instant()).thenReturn(first, second, third, fourth);

        // Set new password
        securityService.setPassword(new Credentials(null, "mY-s3creT-p@ssw0rD"), IP_ADDRESS);
        // verify change is logged!
        Mockito.verify(eventLogger).log(Mockito.any());
        Mockito.reset(eventLogger);

        // First attempt at verification, everything allright (i.e. rejected due to wrong password)
        try {
            securityService.verifyPassword(new Credentials("n0t-mY-s3creT-p@ssw0rD", null), IpAddress.parse("123.123.123.123"));
            fail("Expected UnauthorizedException");
        } catch (Exception e) {
            // Expected
        }

        // We do not need to wait
        assertEquals(0, securityService.passwordEntryInSeconds(IP_ADDRESS));

        // Second attempt at verification without waiting - but from different IP and therefore accepted
        try {
            securityService.verifyPassword(new Credentials("mY-s3creT-p@ssw0rD", null), IP_ADDRESS);
        } catch (Exception e) {
            // Any exception is not accepted
            fail("Expected no UnauthorizedException");
        }
    }

    @Test
    public void testPasswordResetEventLogged() throws IOException, InterruptedException {
        when(automaticUpdater.isActivated()).thenReturn(false);
        when(systemUpdater.getUpdateStatus()).thenReturn(State.IDLING);

        SecurityService securityService = createSecurityService();

        PasswordResetToken passwordResetTokenReceived = securityService.initiateReset();
        Mockito.verify(automaticUpdater).setActivated(false);
        Mockito.verify(systemUpdater).getUpdateStatus();
        Mockito.verify(scriptRunner).runScript("shutdownScript");

        securityService.executeReset(passwordResetTokenReceived, IP_ADDRESS);

        Map<String, String> expectedResetDetails = new HashMap<>();
        expectedResetDetails.put("ipAddress", IP_ADDRESS.toString());

        Mockito.verify(eventLogger).log(Mockito.argThat((event ->
                event.getType() == EventType.ADMIN_PASSWORD_RESET &&
                        event.getEventDetails().equals(expectedResetDetails)
        )));
    }

    private void assertInvalidCredentials(SecurityService securityService, String password, IpAddress ip) {
        try {
            securityService.verifyPassword(new Credentials(password, null), ip);
            fail("Expected UnauthorizedException");
        } catch (UnauthorizedException e) {
            assertEquals("error.credentials.invalid", e.getMessage());
        }
    }

    @Test
    public void successfulLegacyLoginMigratesTheStoredHashOnceAndAcceptsLongPasswords() {
        byte[] legacy = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh/TJXiAu1F7ZE0CvSeHp9tcjzROAvnbRFm9Anty+1vzNw==");
        String password = "legacy-administrator-password-longer-than-fifty-characters-accepted";
        dataSource.setPasswordHash(legacy);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        SecurityService service = createSecurityService();
        JsonWebToken existingToken = service.generateConsoleToken(AppContext.ADMINCONSOLE);
        service.verifyPassword(new Credentials(password, null), IP_ADDRESS);
        byte[] migrated = dataSource.getPasswordHash();
        assertFalse(PasswordUtil.needsRehash(migrated));
        assertTrue(PasswordUtil.verifyPassword(password, migrated));
        service.verifyPassword(new Credentials(password, null), IP_ADDRESS);
        assertArrayEquals(migrated, dataSource.getPasswordHash());
        assertEquals(AppContext.ADMINCONSOLE, service.verifyToken(existingToken.getToken()).getAppContext());
        Mockito.verifyNoInteractions(eventLogger);
    }

    @Test
    public void readOnlyStorageDoesNotRejectAnOtherwiseValidLegacyLogin() {
        byte[] legacy = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8k1GiJ4AOIjUd4kLv6UTmzFtDsEhelDkYpfJhOiy8hRg==");
        dataSource.setPasswordHash(legacy);
        DataSource readOnly = Mockito.spy(dataSource);
        Mockito.doThrow(new IllegalStateException("read-only test store"))
                .when(readOnly).setPasswordHash(Mockito.any(byte[].class));
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        SecurityService service = createSecurityService(3600, readOnly);
        service.verifyPassword(new Credentials("legacy-admin-password", null), IP_ADDRESS);
        assertArrayEquals(legacy, dataSource.getPasswordHash());
        Mockito.verify(readOnly).setPasswordHash(Mockito.any(byte[].class));
        Mockito.verifyNoInteractions(eventLogger);
    }

    @Test
    public void wrongLegacyPasswordNeverReplacesTheStoredHash() {
        byte[] legacy = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8k1GiJ4AOIjUd4kLv6UTmzFtDsEhelDkYpfJhOiy8hRg==");
        dataSource.setPasswordHash(legacy);
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        assertThrows(UnauthorizedException.class,
                () -> createSecurityService().verifyPassword(new Credentials("wrong", null), IP_ADDRESS));
        assertArrayEquals(legacy, dataSource.getPasswordHash());
    }

    @Test
    public void validatesOnlyNewPasswordLengthWithoutChangingExistingCredentials() {
        byte[] existing = PasswordUtil.hashPassword("existing");
        dataSource.setPasswordHash(existing);
        SecurityService service = createSecurityService();
        for (String invalid : new String[] {null, "", "x".repeat(51)}) {
            BadRequestException error = assertThrows(BadRequestException.class,
                    () -> service.setPassword(new Credentials("existing", invalid), IP_ADDRESS));
            assertEquals("error.credentials.invalidNewPassword", error.getMessage());
            assertArrayEquals(existing, dataSource.getPasswordHash());
        }
        Mockito.verifyNoInteractions(eventLogger);
    }

    @Test
    public void existingEmptyPasswordRemainsVerifiableDuringMigration() {
        dataSource.setPasswordHash(Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh9kLlucXn3L7DOk3lA60QJwRXMiY9xoUCcbtQuxk7fhzQ=="));
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        SecurityService service = createSecurityService();
        service.verifyPassword(new Credentials("", null), IP_ADDRESS);
        assertFalse(PasswordUtil.needsRehash(dataSource.getPasswordHash()));
        service.verifyPassword(new Credentials("", null), IP_ADDRESS);
    }

    @Test
    public void passwordChangesRevokeAdministrativeTokensWithoutRevokingDashboardOrControlbar() {
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        SecurityService service = createSecurityService();
        Map<AppContext, JsonWebToken> tokens = new HashMap<>();
        for (AppContext context : new AppContext[] { AppContext.ADMINCONSOLE, AppContext.CONSOLE,
                AppContext.ADMINDASHBOARD, AppContext.DASHBOARD, AppContext.CONTROLBAR }) {
            tokens.put(context, service.generateConsoleToken(context));
        }

        service.setPassword(new Credentials(null, "first password"), IP_ADDRESS);
        assertAdministrativeTokensRevoked(service, tokens);
        assertFalse(service.verifyToken(service.generateConsoleToken(AppContext.ADMINCONSOLE).getToken())
                .isAuthenticationValid());

        for (AppContext context : new AppContext[] { AppContext.ADMINCONSOLE, AppContext.CONSOLE, AppContext.ADMINDASHBOARD }) {
            tokens.put(context, service.generateToken(new Credentials("first password", null), IP_ADDRESS, context));
        }
        service.setPassword(new Credentials("first password", "second password"), IP_ADDRESS);
        assertAdministrativeTokensRevoked(service, tokens);

        for (AppContext context : new AppContext[] { AppContext.ADMINCONSOLE, AppContext.CONSOLE, AppContext.ADMINDASHBOARD }) {
            tokens.put(context, service.generateToken(new Credentials("second password", null), IP_ADDRESS, context));
        }
        service.removePassword(new Credentials("second password", null), IP_ADDRESS);
        assertAdministrativeTokensRevoked(service, tokens);
        assertTrue(service.verifyToken(service.generateConsoleToken(AppContext.ADMINCONSOLE).getToken())
                .isAuthenticationValid());
    }

    @Test
    public void renewRejectsTokenVerifiedBeforePasswordChange() {
        SecurityService service = createSecurityService();
        TokenInfo previouslyVerified = service.verifyToken(service.generateConsoleToken(AppContext.ADMINCONSOLE).getToken());
        service.setPassword(new Credentials(null, "new password"), IP_ADDRESS);

        UnauthorizedException error = assertThrows(UnauthorizedException.class, () -> service.renewToken(previouslyVerified));
        assertEquals("error.token.invalid", error.getMessage());
        assertThrows(UnauthorizedException.class, () -> service.renewToken(previouslyVerified, AppContext.ADMINCONSOLE));
    }

    @Test
    public void renewPreservesTheVerifiedContextAndAuthenticationState() {
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        SecurityService service = createSecurityService();
        TokenInfo dashboard = service.verifyToken(service.generateConsoleToken(AppContext.ADMINDASHBOARD).getToken());
        UnauthorizedException error = assertThrows(UnauthorizedException.class,
                () -> service.renewToken(dashboard, AppContext.ADMINCONSOLE));
        assertEquals("error.token.invalidContext", error.getMessage());
        assertEquals(AppContext.ADMINDASHBOARD,
                service.renewToken(dashboard, AppContext.ADMINDASHBOARD).getAppContext());

        service.setPassword(new Credentials(null, "password"), IP_ADDRESS);
        TokenInfo unauthenticated = service.verifyToken(service.generateConsoleToken(AppContext.ADMINCONSOLE).getToken());
        JsonWebToken renewed = service.renewToken(unauthenticated, AppContext.ADMINCONSOLE);
        assertFalse(service.verifyToken(renewed.getToken()).isAuthenticationValid());
        assertThrows(UnauthorizedException.class, () -> service.renewToken(null, AppContext.ADMINCONSOLE));
    }

    @Test
    public void failedPasswordChangesKeepExistingAdministrativeTokensValid() {
        when(clock.instant()).thenReturn(Instant.ofEpochSecond(1000));
        dataSource.setPasswordHash(PasswordUtil.hashPassword("existing"));
        DataSource failingStore = Mockito.spy(dataSource);
        SecurityService service = createSecurityService(3600, failingStore);
        JsonWebToken token = service.generateToken(new Credentials("existing", null), IP_ADDRESS, AppContext.ADMINCONSOLE);

        assertThrows(BadRequestException.class, () -> service.setPassword(new Credentials("existing", ""), IP_ADDRESS));
        assertThrows(UnauthorizedException.class,
                () -> service.setPassword(new Credentials("wrong", "new"), IpAddress.parse("1.2.3.5")));
        Mockito.doThrow(new IllegalStateException("read-only store"))
                .when(failingStore).setPasswordHash(Mockito.any(byte[].class));
        assertThrows(IllegalStateException.class, () -> service.setPassword(new Credentials("existing", "new"), IP_ADDRESS));
        Mockito.doThrow(new IllegalStateException("read-only store")).when(failingStore).deletePasswordHash();
        assertThrows(IllegalStateException.class, () -> service.removePassword(new Credentials("existing", null), IP_ADDRESS));

        assertTrue(service.verifyToken(token.getToken()).isAuthenticationValid());
        assertNotNull(service.renewToken(service.verifyToken(token.getToken()), AppContext.ADMINCONSOLE));
        Mockito.verifyNoInteractions(eventLogger);
    }

    @Test
    public void resetRevokesOnlyAfterSuccessfulPasswordRemovalAndConsumesTheResetToken() {
        SecurityService service = createSecurityService();
        JsonWebToken token = service.generateConsoleToken(AppContext.ADMINCONSOLE);
        PasswordResetToken reset = new PasswordResetToken("reset", new Date(System.currentTimeMillis() + 60_000), 0L, false);
        dataSource.save(reset);
        PasswordResetToken invalid = new PasswordResetToken("invalid", reset.getValidTill(), 0L, false);
        assertThrows(UnauthorizedException.class, () -> service.executeReset(invalid, IP_ADDRESS));
        assertNotNull(service.verifyToken(token.getToken()));

        service.executeReset(reset, IP_ADDRESS);
        assertThrows(UnauthorizedException.class, () -> service.verifyToken(token.getToken()));
        JsonWebToken fresh = service.generateConsoleToken(AppContext.ADMINCONSOLE);
        assertTrue(service.verifyToken(fresh.getToken()).isAuthenticationValid());
        assertThrows(UnauthorizedException.class, () -> service.executeReset(reset, IP_ADDRESS));
        assertNotNull(service.verifyToken(fresh.getToken()));
    }

    @Test
    public void canceledOrFailedResetDoesNotRevokeAdministrativeTokens() {
        DataSource failingStore = Mockito.spy(dataSource);
        SecurityService service = createSecurityService(3600, failingStore);
        JsonWebToken token = service.generateConsoleToken(AppContext.ADMINCONSOLE);
        PasswordResetToken reset = new PasswordResetToken("reset", new Date(System.currentTimeMillis() + 60_000), 0L, false);
        dataSource.save(reset);
        Mockito.doThrow(new IllegalStateException("read-only store")).when(failingStore).deletePasswordHash();
        assertThrows(IllegalStateException.class, () -> service.executeReset(reset, IP_ADDRESS));
        assertNotNull(service.verifyToken(token.getToken()));
        service.cancelReset(reset);
        assertNotNull(service.verifyToken(token.getToken()));
        Mockito.verifyNoInteractions(eventLogger);
    }

    @Test
    public void concurrentPasswordActivationCannotUpgradeAnEarlierPasswordlessLogin() throws Exception {
        assertConcurrentIssueIsRevoked(service -> service.generateToken(new Credentials(null, null), IP_ADDRESS, AppContext.ADMINCONSOLE));
    }

    @Test
    public void renewalControllerRechecksTokenValidatedByTheSecurityProcessor() {
        JsonWebTokenHandler handler = new JsonWebTokenHandler(3600, 3600, baseURLs);
        SecurityService service = createSecurityService(3600, dataSource, handler);
        JsonWebToken token = service.generateConsoleToken(AppContext.ADMINCONSOLE);
        Request request = Mockito.mock(Request.class);
        Response response = Mockito.mock(Response.class);
        Route route = Mockito.mock(Route.class);
        when(request.getResolvedRoute()).thenReturn(route);
        when(route.getName()).thenReturn("adminconsole.authentication.renew.route");
        when(request.getHeader("authorization")).thenReturn("Bearer " + token.getToken());
        when(request.getHeader("appContext")).thenReturn("ADMINCONSOLE");
        Map<String, Object> attachments = new HashMap<>();
        Mockito.doAnswer(invocation -> attachments.put(invocation.getArgument(0), invocation.getArgument(1)))
                .when(request).putAttachment(Mockito.anyString(), Mockito.any());
        when(request.getAttachment(Mockito.anyString())).thenAnswer(invocation -> attachments.get(invocation.getArgument(0)));
        new SecurityProcessor(handler).process(request);
        AuthenticationControllerImpl controller = new AuthenticationControllerImpl(service, null, null);
        assertNotNull(controller.renewToken(request, response));

        service.setPassword(new Credentials(null, "new password"), IP_ADDRESS);
        assertThrows(UnauthorizedException.class, () -> controller.renewToken(request, response));
    }

    @Test
    public void concurrentPasswordActivationCannotUpgradeAnEarlierConsoleBootstrap() throws Exception {
        assertConcurrentIssueIsRevoked(service -> service.generateConsoleToken(AppContext.ADMINCONSOLE));
    }

    @Test
    public void concurrentPasswordActivationCannotUpgradeAnEarlierRenewal() throws Exception {
        assertConcurrentIssueIsRevoked(service -> {
            TokenInfo token = service.verifyToken(service.generateConsoleToken(AppContext.ADMINCONSOLE).getToken());
            return service.renewToken(token, AppContext.ADMINCONSOLE);
        }, 2);
    }

    private void assertConcurrentIssueIsRevoked(Function<SecurityService, JsonWebToken> issue) throws Exception {
        assertConcurrentIssueIsRevoked(issue, 1);
    }

    private void assertConcurrentIssueIsRevoked(Function<SecurityService, JsonWebToken> issue, int blockInvocation) throws Exception {
        JsonWebTokenHandler handler = Mockito.spy(new JsonWebTokenHandler(3600, 3600, baseURLs));
        SecurityService service = createSecurityService(3600, dataSource, handler);
        CountDownLatch signing = new CountDownLatch(1);
        CountDownLatch releaseSigning = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger invocations = new java.util.concurrent.atomic.AtomicInteger();
        Mockito.doAnswer(invocation -> {
            if (invocations.incrementAndGet() == blockInvocation) {
                signing.countDown();
                assertTrue("Timed out releasing token issuance", releaseSigning.await(5, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(handler).generateToken(Mockito.anyBoolean(), Mockito.eq(AppContext.ADMINCONSOLE), Mockito.anyLong(), Mockito.anyBoolean());
        FutureTask<JsonWebToken> issued = new FutureTask<>(() -> issue.apply(service));
        Thread issuer = new Thread(issued, "admin-token-issuer");
        FutureTask<Void> changed = new FutureTask<>(() -> {
            service.setPassword(new Credentials(null, "new password"), IP_ADDRESS);
            return null;
        });
        Thread changer = new Thread(changed, "admin-password-changer");
        try {
            issuer.start();
            assertTrue("Token issuance did not reach signing", signing.await(5, TimeUnit.SECONDS));
            changer.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean attempted = false;
            while (System.nanoTime() < deadline) {
                ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(changer.getId());
                // With the fix, the password change waits for this issuer's credential
                // lock. Without it, it can complete before the old login signs a token.
                if (changed.isDone() || (info != null && info.getLockOwnerId() == issuer.getId())) {
                    attempted = true;
                    break;
                }
                Thread.sleep(1);
            }
            assertTrue("Password change did not reach the credential operation", attempted);
            releaseSigning.countDown();
            JsonWebToken token = issued.get(5, TimeUnit.SECONDS);
            changed.get(5, TimeUnit.SECONDS);
            assertThrows(UnauthorizedException.class, () -> service.verifyToken(token.getToken()));
        } finally {
            releaseSigning.countDown();
            issuer.interrupt();
            changer.interrupt();
            issuer.join(5000);
            changer.join(5000);
        }
    }

    private void assertAdministrativeTokensRevoked(SecurityService service, Map<AppContext, JsonWebToken> tokens) {
        for (AppContext context : new AppContext[] { AppContext.ADMINCONSOLE, AppContext.CONSOLE, AppContext.ADMINDASHBOARD }) {
            UnauthorizedException error = assertThrows(UnauthorizedException.class,
                    () -> service.verifyToken(tokens.get(context).getToken()));
            assertEquals("error.token.invalid", error.getMessage());
        }
        for (AppContext context : new AppContext[] { AppContext.DASHBOARD, AppContext.CONTROLBAR }) {
            assertEquals(context, service.verifyToken(tokens.get(context).getToken()).getAppContext());
        }
    }

    private SecurityService createSecurityService() {
        return createSecurityService(3600);
    }

    private SecurityService createSecurityService(long validity) {
        return createSecurityService(validity, dataSource);
    }

    private SecurityService createSecurityService(long validity, DataSource credentialsDataSource) {
        return createSecurityService(validity, credentialsDataSource, new JsonWebTokenHandler(validity, validity, baseURLs));
    }

    private SecurityService createSecurityService(long validity, DataSource credentialsDataSource, JsonWebTokenHandler handler) {
        return new SecurityService(
                handler,
                validity,
                validity,
                passwordResetValiditySeconds,
                passwordResetGracePeriodSeconds,
                credentialsDataSource,
                systemUpdater,
                automaticUpdater,
                scriptRunner,
                "shutdownScript",
                eventLogger, // Eventlogger
                deviceService, // Device Service
                userService, // User Service
                productInfoService, // ProductInfoService
                100, // Max time to wait after entering wrong password
                10, // Increment time to wait after entering wrong password by this number of seconds
                clock
        );
    }

}
