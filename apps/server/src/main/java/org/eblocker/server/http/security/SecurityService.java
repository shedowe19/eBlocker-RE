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
import com.google.inject.name.Named;
import org.eblocker.registration.ProductFeature;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.IpAddress;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.events.EventLogger;
import org.eblocker.server.common.data.events.Events;
import org.eblocker.server.common.system.ScriptRunner;
import org.eblocker.server.common.update.AutomaticUpdater;
import org.eblocker.server.common.update.SystemUpdater;
import org.eblocker.server.http.service.DeviceService;
import org.eblocker.server.http.service.ProductInfoService;
import org.eblocker.server.http.service.UserService;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ServiceException;
import org.restexpress.exception.UnauthorizedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class SecurityService {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityService.class);
    private static final String EXCEPTION_STRING_ERROR_CREDENTIALS_INVALID = "error.credentials.invalid";

    private final JsonWebTokenHandler tokenHandler;
    private final long tokenUserValiditySeconds;
    private final long passwordResetValiditySeconds;
    private final long passwordResetGracePeriodSeconds;
    private final long tokenDashboardValiditySeconds;
    private final DataSource dataSource;
    private final SystemUpdater systemUpdater;
    private final AutomaticUpdater automaticUpdater;
    private ScriptRunner scriptRunner;
    private String prepareShutdownScript;
    private EventLogger eventLogger;
    private final DeviceService deviceService;
    private final UserService userService;
    private final ProductInfoService productInfoService;
    private Map<IpAddress, Instant> nextPasswordEntryPermittedAt = new ConcurrentHashMap<>();
    private Map<IpAddress, Integer> failedPasswordAttempts = new ConcurrentHashMap<>();
    private final Object credentialLock = new Object();
    private final long passwordFailedMaxPenaltySeconds;
    private final long passwordFailedPenaltyIncrementSeconds;
    private Clock clock;

    @Inject
    public SecurityService(
            JsonWebTokenHandler tokenHandler,
            @Named("authentication.token.user.validity.seconds") long tokenUserValiditySeconds,
            @Named("authentication.token.dashboard.validity.seconds") long tokenDashboardValiditySeconds,
            @Named("authentication.passwordReset.validity.seconds") long passwordResetValiditySeconds,
            @Named("authentication.passwordReset.gracePeriod.seconds") long passwordResetGracePeriodSeconds,
            DataSource dataSource,
            SystemUpdater systemUpdater,
            AutomaticUpdater automaticUpdater,
            ScriptRunner scriptRunner,
            @Named("prepare.shutdown.command") String prepareShutdownScript,
            EventLogger eventLogger,
            DeviceService deviceService,
            UserService userService,
            ProductInfoService productInfoService,
            @Named("authentication.passwordFailed.maxPenalty.seconds") long passwordFailedMaxPenaltySeconds,
            @Named("authentication.passwordFailed.penaltyIncrement.seconds") long passwordFailedPenaltyIncrementSeconds,
            Clock clock

    ) {
        this.tokenHandler = tokenHandler;
        this.tokenUserValiditySeconds = tokenUserValiditySeconds;
        this.tokenDashboardValiditySeconds = tokenDashboardValiditySeconds;
        this.passwordResetValiditySeconds = passwordResetValiditySeconds;
        this.passwordResetGracePeriodSeconds = passwordResetGracePeriodSeconds;
        this.dataSource = dataSource;
        this.systemUpdater = systemUpdater;
        this.automaticUpdater = automaticUpdater;
        this.scriptRunner = scriptRunner;
        this.prepareShutdownScript = prepareShutdownScript;
        this.eventLogger = eventLogger;
        this.deviceService = deviceService;
        this.userService = userService;
        this.productInfoService = productInfoService;
        this.passwordFailedMaxPenaltySeconds = passwordFailedMaxPenaltySeconds;
        this.passwordFailedPenaltyIncrementSeconds = passwordFailedPenaltyIncrementSeconds;
        this.clock = clock;

        LOG.info("Using random value as base for JWT secret");
    }

    public JsonWebToken generateToken(Credentials credentials, IpAddress ip, AppContext appContext) {
        synchronized (credentialLock) {
            verifyPasswordLocked(credentials, ip);
            return generateToken(isPasswordRequired(), appContext, tokenUserValiditySeconds, true);
        }
    }

    public JsonWebToken generateConsoleToken(AppContext appContext) {
        synchronized (credentialLock) {
            boolean passwordRequired = isPasswordRequired();
            return generateToken(passwordRequired, appContext, tokenUserValiditySeconds, !passwordRequired);
        }
    }

    public JsonWebToken generateToken(AppContext appContext) {
        if (appContext.isPasswordRequired()) {
            LOG.info("Need password to access context {}", appContext);
            throw new UnauthorizedException("error.token.noPassword");
        }
        return generateToken(false, appContext, tokenDashboardValiditySeconds, true);
    }

    private JsonWebToken generateToken(boolean passwordRequired, AppContext context, long tokenValiditySeconds, boolean isAuthenticationValid) {
        if (context == AppContext.SYSTEM) {
            LOG.error("System app context can only be accessed internally");
            throw new UnauthorizedException("error.token.invalidContext");
        }
        return tokenHandler.generateToken(passwordRequired, context, tokenValiditySeconds, isAuthenticationValid);
    }

    public JsonWebToken renewToken(TokenInfo tokenInfo) {
        synchronized (credentialLock) {
            tokenHandler.validateCurrentToken(tokenInfo);
            return generateToken(isPasswordRequired(), tokenInfo.getAppContext(), tokenInfo.getTokenValiditySeconds(), tokenInfo.isAuthenticationValid());
        }
    }

    public JsonWebToken renewToken(TokenInfo tokenInfo, AppContext appContext) {
        synchronized (credentialLock) {
            // Preprocessing may have happened before a concurrent password change.
            // Neither an old epoch nor a different URL context may grant fresh access.
            tokenHandler.validateCurrentToken(tokenInfo);
            if (tokenInfo.getAppContext() != appContext) {
                throw new UnauthorizedException("error.token.invalidContext");
            }
            return generateToken(isPasswordRequired(), appContext, tokenUserValiditySeconds, tokenInfo.isAuthenticationValid());
        }
    }

    public void verifyPassword(Credentials credentials, IpAddress ip) {
        synchronized (credentialLock) {
            verifyPasswordLocked(credentials, ip);
        }
    }

    private void verifyPasswordLocked(Credentials credentials, IpAddress ip) {
        byte[] passwordHash = dataSource.getPasswordHash();
        if (passwordHash == null) return;
        String password = credentials == null ? null : credentials.getCurrentPassword();
        if (password == null) {
            throw new UnauthorizedException(EXCEPTION_STRING_ERROR_CREDENTIALS_INVALID);
        }
        // Check throttling before the expensive KDF, including empty guesses.
        if (passwordEntryInSeconds(ip) > 0) {
            throw new UnauthorizedException(password.isEmpty()
                    ? EXCEPTION_STRING_ERROR_CREDENTIALS_INVALID : "error.credentials.too.soon");
        }
        if (!PasswordUtil.verifyPassword(password, passwordHash)) {
            int attempts = failedPasswordAttempts.getOrDefault(ip, 0);
            long wait = Math.min((attempts + 1L) * passwordFailedPenaltyIncrementSeconds,
                    passwordFailedMaxPenaltySeconds);
            nextPasswordEntryPermittedAt.put(ip, clock.instant().plusSeconds(wait));
            failedPasswordAttempts.put(ip, attempts == Integer.MAX_VALUE ? attempts : attempts + 1);
            throw new UnauthorizedException(EXCEPTION_STRING_ERROR_CREDENTIALS_INVALID);
        }
        nextPasswordEntryPermittedAt.remove(ip);
        failedPasswordAttempts.remove(ip);
        if (PasswordUtil.needsRehash(passwordHash)) {
            byte[] upgraded = PasswordUtil.hashPassword(password);
            // All password operations in this service share credentialLock. Also avoid
            // restoring a hash if a restore/import changed the stored value meanwhile.
            if (MessageDigest.isEqual(passwordHash, dataSource.getPasswordHash())) {
                try {
                    dataSource.setPasswordHash(upgraded);
                } catch (RuntimeException e) {
                    // Rehashing is opportunistic. A read-only store must not reject a
                    // password that was just verified successfully. Never log credentials,
                    // hashes, or storage exception messages (which can contain credentials).
                    LOG.warn("Could not upgrade the verified administrator password hash; retaining the existing credential");
                }
            }
        }
    }

    public long passwordEntryInSeconds(IpAddress ip) {
        Instant now = clock.instant();
        return Duration.between(now, this.nextPasswordEntryPermittedAt.getOrDefault(ip, now)).getSeconds();
    }

    public boolean isPasswordRequired() {
        return dataSource.getPasswordHash() != null;
    }

    private Map<String, String> preparePasswordChangeDetails(IpAddress ipAddress) {
        Map<String, String> details = new HashMap<>();
        details.put("ipAddress", ipAddress.toString());
        Device device = deviceService.getDeviceByIp(ipAddress);
        if (device != null) {
            details.put("deviceName", device.getName());
            int userId = device.getOperatingUser();
            // Only if FAM-feature is available, otherwise there are no users
            if (productInfoService.hasFeature(ProductFeature.FAM)) {
                UserModule user = userService.getUserById(userId);
                // Only if user is not system user
                if (!user.isSystem()) {
                    details.put("userName", user.getName());
                }
            }
        }
        return details;
    }

    public void setPassword(Credentials credentials, IpAddress ipAddress) {
        String newPassword = credentials == null ? null : credentials.getNewPassword();
        if (newPassword == null || newPassword.isEmpty() || newPassword.length() > 50) {
            throw new BadRequestException("error.credentials.invalidNewPassword");
        }
        synchronized (credentialLock) {
            verifyPasswordLocked(credentials, ipAddress);
            byte[] hash = PasswordUtil.hashPassword(newPassword);
            dataSource.setPasswordHash(hash);
            tokenHandler.revokeAdministratorTokens();
            eventLogger.log(Events.adminPasswordChanged(preparePasswordChangeDetails(ipAddress)));
        }
    }

    public void removePassword(Credentials credentials, IpAddress ipAddress) {
        synchronized (credentialLock) {
            verifyPasswordLocked(credentials, ipAddress);
            dataSource.deletePasswordHash();
            tokenHandler.revokeAdministratorTokens();
            eventLogger.log(Events.adminPasswordRemoved(preparePasswordChangeDetails(ipAddress)));
        }
    }

    public PasswordResetToken initiateReset() {
        //
        // Remember the current auto update status and switch auto-updates off
        //
        boolean autoUpdateActivated = automaticUpdater.isActivated();
        automaticUpdater.setActivated(false);

        //
        // Is update currently running?
        // If yes, or if error occurs, restore auto-update state
        //
        try {
            if (systemUpdater.getUpdateStatus() == SystemUpdater.State.UPDATING) {
                restoreAutoUpdateState(autoUpdateActivated);
                throw new BadRequestException("error.password.reset.update.running");
            }
        } catch (IOException e) {
            restoreAutoUpdateState(autoUpdateActivated);
            throw new ServiceException("error.password.reset.update.unknown", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            restoreAutoUpdateState(autoUpdateActivated);
            throw new ServiceException("error.password.reset.update.unknown", e);
        }

        Date validTill = new Date(System.currentTimeMillis() + 1000L * passwordResetValiditySeconds);
        PasswordResetToken passwordResetToken = new PasswordResetToken(
                UUID.randomUUID().toString(),
                validTill,
                passwordResetGracePeriodSeconds,
                autoUpdateActivated
        );

        dataSource.save(passwordResetToken);

        dataSource.saveSynchronously();

        try {
            scriptRunner.runScript(prepareShutdownScript);
        } catch (IOException e) {
            throw new ServiceException("error.password.reset.cannot.prepare.shutdown", e);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceException("error.password.reset.cannot.prepare.shutdown", e);
        }

        return passwordResetToken;
    }

    private void restoreAutoUpdateState(boolean autoUpdateActivated) {
        if (autoUpdateActivated) {
            automaticUpdater.setActivated(true);
        }
    }

    public void executeReset(PasswordResetToken passwordResetToken, IpAddress ipAddress) {
        completeReset(passwordResetToken, true, ipAddress);
    }

    public void cancelReset(PasswordResetToken passwordResetToken) {
        completeReset(passwordResetToken, false, null);
    }

    private void completeReset(PasswordResetToken passwordResetToken, boolean executeReset, IpAddress ipAddress) {
        synchronized (credentialLock) {
            completeResetLocked(passwordResetToken, executeReset, ipAddress);
        }
    }

    private void completeResetLocked(PasswordResetToken passwordResetToken, boolean executeReset, IpAddress ipAddress) {
        PasswordResetToken savedToken = dataSource.get(PasswordResetToken.class);
        if (savedToken == null) {
            throw new UnauthorizedException("error.password.reset.not.initiated");
        }
        if (savedToken.getResetToken() == null || !savedToken.getResetToken().equals(passwordResetToken.getResetToken())) {
            throw new UnauthorizedException("error.password.reset.invalid.token");
        }
        if (savedToken.getValidTill() == null || savedToken.getValidTill().before(new Date())) {
            throw new UnauthorizedException("error.password.reset.token.expired");
        }

        //
        // restore update functionality
        //
        if (savedToken.isAutoUpdateActive()) {
            automaticUpdater.setActivated(true);
        }

        //
        // Delete password hash
        //
        if (executeReset) {
            dataSource.deletePasswordHash();
            tokenHandler.revokeAdministratorTokens();
            eventLogger.log(Events.adminPasswordReset(preparePasswordChangeDetails(ipAddress)));
        }
        dataSource.delete(PasswordResetToken.class);
    }

    public TokenInfo verifyToken(String encodedToken) {
        return tokenHandler.verifyToken(encodedToken);
    }
}
