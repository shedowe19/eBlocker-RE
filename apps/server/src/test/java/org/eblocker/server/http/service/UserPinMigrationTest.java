// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.service;

import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserRole;
import org.eblocker.server.http.security.PasswordUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;

import java.util.Base64;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserPinMigrationTest {
    private static final String PIN = "legacy-admin-password";
    private static final byte[] LEGACY = Base64.getDecoder().decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8k1GiJ4AOIjUd4kLv6UTmzFtDsEhelDkYpfJhOiy8hRg==");
    private DataSource dataSource;
    private UserService users;
    private AtomicReference<UserModule> stored;

    @BeforeEach
    void setup() {
        dataSource = mock(DataSource.class);
        users = new UserService(dataSource, mock(DeviceService.class), mock(DashboardCardService.class), "standard");
        stored = new AtomicReference<>(user(LEGACY));
        when(dataSource.get(UserModule.class, 100)).thenAnswer(call -> copy(stored.get()));
        when(dataSource.getAll(UserModule.class)).thenAnswer(call -> stored.get() == null ? java.util.List.of() : java.util.List.of(copy(stored.get())));
        when(dataSource.compareAndSetUserPin(eq(100), any(), nullable(byte[].class))).thenAnswer(call -> {
            UserModule next = copy(stored.get());
            if (!java.util.Arrays.equals(next.getPin(), call.getArgument(1))) return false;
            next.setPin(call.getArgument(2));
            stored.set(next);
            return true;
        });
    }

    private UserModule user(byte[] pin) {
        return new UserModule(100, 300, "Alice", null, null, UserRole.CHILD, false, pin, new HashMap<>(), null, null, null);
    }

    private UserModule copy(UserModule source) {
        if (source == null) return null;
        return new UserModule(source.getId(), source.getAssociatedProfileId(), source.getName(), source.getNameKey(), null,
                source.getUserRole(), source.isSystem(), source.getPin(), new HashMap<>(source.getWhiteListConfigByDomains()), null, null, null);
    }

    @Test
    void successfulLegacyVerificationUpgradesOnlyPinAndInvalidatesCache() {
        assertArrayEquals(LEGACY, users.getUserById(100).getPin());
        assertTrue(users.verifyPin(100, PIN));
        assertFalse(PasswordUtil.needsRehash(stored.get().getPin()));
        assertTrue(PasswordUtil.verifyPassword(PIN, stored.get().getPin()));
        assertEquals(UserRole.CHILD, stored.get().getUserRole());
        assertEquals(300, stored.get().getAssociatedProfileId());
        assertArrayEquals(stored.get().getPin(), users.getUserById(100).getPin());
        verify(dataSource, never()).save(any(UserModule.class), anyInt());
    }

    @Test
    void wrongMissingAndModernPinsDoNotTriggerMigration() {
        assertFalse(users.verifyPin(100, "wrong"));
        assertFalse(users.verifyPin(100, null));
        stored.set(user(null));
        assertFalse(users.verifyPin(100, PIN));
        stored.set(null);
        assertFalse(users.verifyPin(100, PIN));
        stored.set(user(PasswordUtil.hashPassword("1234")));
        assertTrue(users.verifyPin(100, "1234"));
        verify(dataSource, never()).compareAndSetUserPin(anyInt(), any(), any());
    }

    @Test
    void failedOptionalPersistenceDoesNotDenyCorrectPinOrMutateCachedUser() {
        UserModule cached = users.getUserById(100);
        when(dataSource.compareAndSetUserPin(anyInt(), any(), any())).thenThrow(new IllegalStateException("store unavailable"));
        assertTrue(users.verifyPin(100, PIN));
        assertArrayEquals(LEGACY, stored.get().getPin());
        assertArrayEquals(LEGACY, cached.getPin());
    }

    @Test
    void concurrentPinReplacementRejectsOldPinAndNeverOverwritesReplacement() {
        byte[] replacement = PasswordUtil.hashPassword("9876");
        when(dataSource.compareAndSetUserPin(anyInt(), any(), any())).thenAnswer(call -> {
            stored.set(user(replacement));
            return false;
        });
        assertFalse(users.verifyPin(100, PIN));
        assertArrayEquals(replacement, stored.get().getPin());
    }

    @Test
    void concurrentProfileChangeRetainsProfileAndStillVerifiesCurrentPin() {
        when(dataSource.compareAndSetUserPin(anyInt(), any(), any())).thenAnswer(call -> {
            UserModule changed = new UserModule(100, 999, "Renamed", null, null, UserRole.PARENT, false,
                    LEGACY, new HashMap<>(), null, null, null);
            stored.set(changed);
            return false;
        });
        assertTrue(users.verifyPin(100, PIN));
        assertEquals(999, stored.get().getAssociatedProfileId());
        assertEquals("Renamed", stored.get().getName());
        assertEquals(UserRole.PARENT, stored.get().getUserRole());
    }

    @Test
    void concurrentlyDeletedUserCannotAuthenticate() {
        when(dataSource.compareAndSetUserPin(anyInt(), any(), any())).thenAnswer(call -> { stored.set(null); return false; });
        assertFalse(users.verifyPin(100, PIN));
    }

    @Test
    void explicitPinChangeReadsFreshCredentialAndUsesFieldCas() {
        users.getUserById(100); // stale cached old PIN
        stored.set(user(PasswordUtil.hashPassword("changed")));
        assertThrows(BadRequestException.class, () -> users.changePin(100, "new", PIN));
        users.changePin(100, "new", "changed");
        assertTrue(PasswordUtil.verifyPassword("new", stored.get().getPin()));
        verify(dataSource, never()).save(any(UserModule.class), anyInt());
        users.setPin(100, "");
        assertNull(stored.get().getPin());
    }

    @Test
    void explicitPinChangeConflictsWithoutMutatingSnapshot() {
        UserModule cached = users.getUserById(100);
        when(dataSource.compareAndSetUserPin(anyInt(), any(), any())).thenReturn(false);
        assertThrows(ConflictException.class, () -> users.changePin(100, "new", PIN));
        assertArrayEquals(LEGACY, cached.getPin());
        assertArrayEquals(LEGACY, stored.get().getPin());
    }
}
