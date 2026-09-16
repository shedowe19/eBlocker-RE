// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eblocker.server.common.data.*;
import org.eblocker.server.common.data.dashboard.DashboardColumnsView;
import org.eblocker.server.common.data.dashboard.UiCardColumnPosition;
import org.eblocker.server.http.controller.FamilySettingsPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restexpress.exception.ConflictException;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(10)
class UserMutationConcurrencyTest {
    private final DataSource data = mock(DataSource.class);
    private final DeviceService devices = mock(DeviceService.class);
    private final DashboardCardService cards = mock(DashboardCardService.class);
    private final ObjectMapper json = new ObjectMapper();
    private final Map<Integer, UserModule> stored = new ConcurrentHashMap<>();
    private final AtomicReference<Runnable> beforeCas = new AtomicReference<>(() -> { });
    private UserService users;

    @BeforeEach void setup() {
        stored.put(100, user(100));
        when(data.get(eq(UserModule.class), anyInt())).thenAnswer(call -> copy(stored.get(call.getArgument(1))));
        when(data.getAll(UserModule.class)).thenAnswer(call -> snapshot());
        when(data.get(eq(UserProfileModule.class), anyInt())).thenReturn(mock(UserProfileModule.class));
        when(data.nextId(UserModule.class)).thenReturn(200);
        when(data.compareAndSetUser(nullable(UserModule.class), any(UserModule.class))).thenAnswer(call -> {
            beforeCas.getAndSet(() -> { }).run();
            UserModule expected = call.getArgument(0);
            UserModule replacement = call.getArgument(1);
            synchronized (stored) {
                if (!json.valueToTree(expected).equals(json.valueToTree(stored.get(replacement.getId())))) return false;
                stored.put(replacement.getId(), copy(replacement));
                return true;
            }
        });
        when(data.compareAndSetUserPin(anyInt(), nullable(byte[].class), nullable(byte[].class))).thenAnswer(call -> {
            synchronized (stored) {
                UserModule current = copy(stored.get(call.getArgument(0)));
                if (current == null || !Arrays.equals(current.getPin(), call.getArgument(1))) return false;
                current.setPin(call.getArgument(2));
                stored.put(current.getId(), current);
                return true;
            }
        });
        doAnswer(call -> { stored.remove(call.getArgument(1)); return null; }).when(data).delete(eq(UserModule.class), anyInt());
        when(devices.getDevices(anyBoolean())).thenReturn(List.of());
        when(cards.getNewDashboardCardColumns(any())).thenAnswer(call -> new DashboardColumnsView());
        when(cards.getUpdatedColumnsView(any(), any(), anyList())).thenAnswer(call -> call.getArgument(0));
        users = new UserService(data, devices, cards, "standard");
        users.refresh();
    }

    @ParameterizedTest @ValueSource(strings = {"legacy", "whitelist", "dashboard", "automaticDashboard", "customLists", "patch"})
    void mutationsReadFreshPinAndRoleInsteadOfRewritingCachedCredentials(String mutation) {
        UserModule oldSnapshot = users.getUserById(100);
        UserModule changed = copy(stored.get(100));
        changed.setPin(new byte[]{8, 9}); changed.setUserRole(UserRole.CHILD);
        stored.put(100, changed);
        mutate(mutation);
        assertArrayEquals(new byte[]{8, 9}, stored.get(100).getPin());
        assertEquals(UserRole.CHILD, stored.get(100).getUserRole());
        assertArrayEquals(new byte[]{1, 2}, oldSnapshot.getPin());
        assertEquals(UserRole.OTHER, oldSnapshot.getUserRole());
        assertArrayEquals(new byte[]{8, 9}, users.getUserById(100).getPin());
        verify(data, never()).save(any(UserModule.class), anyInt());
    }

    @ParameterizedTest @ValueSource(strings = {"legacy", "whitelist", "dashboard", "automaticDashboard", "customLists", "patch"})
    void concurrentPinAndRoleReplacementAbortsStaleMutationWithoutPublication(String mutation) {
        UserModule cached = users.getUserById(100);
        var listener = mock(UserService.UserChangeListener.class); users.addListener(listener);
        beforeCas.set(() -> {
            UserModule changed = copy(stored.get(100)); changed.setPin(new byte[]{7, 8}); changed.setUserRole(UserRole.PARENT);
            stored.put(100, changed);
        });
        assertThrows(ConflictException.class, () -> mutate(mutation));
        assertArrayEquals(new byte[]{7, 8}, stored.get(100).getPin());
        assertEquals(UserRole.PARENT, stored.get(100).getUserRole());
        assertEquals(json.valueToTree(cached), json.valueToTree(users.getUserById(100)));
        verifyNoInteractions(listener);
        verify(cards, never()).createParentalControlCard(anyInt(), anyString(), anyString());
        verify(cards, never()).removeParentalControlCard(anyInt());
    }

    @Test void failedPersistenceCannotMutatePublishedNestedDashboardWhitelistOrPin() {
        UserModule published = users.getUserById(100);
        when(cards.getUpdatedColumnsView(any(), any(), anyList())).thenAnswer(call -> {
            DashboardColumnsView view = call.getArgument(0); view.getOneColumn().clear(); return view;
        });
        when(data.compareAndSetUser(any(), any())).thenThrow(new IllegalStateException("unavailable"));
        assertThrows(IllegalStateException.class, () -> users.updateUserDashboardView(100));
        assertEquals(1, published.getDashboardColumnsView().getOneColumn().size());
        assertEquals(1, users.getUserById(100).getDashboardColumnsView().getOneColumn().size());
        // Legacy controller callers may modify returned objects before requesting a save.
        published.getWhiteListConfigByDomains().clear(); published.getPin()[0] = 99;
        published.getDashboardColumnsView().getOneColumn().clear();
        assertFalse(users.getUserById(100).getWhiteListConfigByDomains().isEmpty());
        assertArrayEquals(new byte[]{1, 2}, users.getUserById(100).getPin());
        assertThrows(IllegalStateException.class, () -> users.updateUser(100, published.getWhiteListConfigByDomains()));
        assertFalse(users.getUserById(100).getWhiteListConfigByDomains().isEmpty());
    }

    @ParameterizedTest @ValueSource(strings = {"pin", "update", "delete", "patch"})
    void staleRefreshCannotOverwriteCommittedMutationOrRecreateDeletedUser(String operation) throws Exception {
        CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(data.getAll(UserModule.class)).thenAnswer(call -> {
            List<UserModule> result = snapshot();
            if (first.getAndSet(false)) { captured.countDown(); await(release); }
            return result;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> refresh = executor.submit(users::refresh); await(captured);
            switch (operation) {
                case "pin" -> users.setPin(100, "");
                case "update" -> users.updateUser(100, 20, "Renamed", null, null, UserRole.OTHER, null);
                case "delete" -> assertTrue(users.deleteUser(100));
                case "patch" -> users.patchSettings(100, FamilySettingsPatch.user("{\"name\":\"Renamed\"}"));
                default -> throw new AssertionError();
            }
            release.countDown(); refresh.get(3, TimeUnit.SECONDS);
            if (operation.equals("delete")) assertTrue(users.getUsers(false).isEmpty());
            else if (operation.equals("pin")) assertNull(users.getUserById(100).getPin());
            else assertEquals("Renamed", users.getUserById(100).getName());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void olderRefreshCannotReplaceMoreRecentRefreshEvenWithoutAWrite() throws Exception {
        CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        when(data.getAll(UserModule.class)).thenAnswer(call -> {
            List<UserModule> result = snapshot();
            if (first.getAndSet(false)) { captured.countDown(); await(release); }
            return result;
        });
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> old = executor.submit(users::refresh); await(captured);
            stored.get(100).setPin(new byte[]{9}); users.refresh();
            release.countDown(); old.get(3, TimeUnit.SECONDS);
            assertArrayEquals(new byte[]{9}, users.getUserById(100).getPin());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void persistenceAndListenerCallbacksNeverHoldTheCachePublicationLock() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            beforeCas.set(() -> {
                try { assertEquals(1, executor.submit(() -> users.getUsers(false).size()).get(2, TimeUnit.SECONDS)); }
                catch (Exception e) { throw new AssertionError("Persistence blocked cache reads", e); }
            });
            users.addListener(user -> {
                try { assertEquals(1, executor.submit(() -> users.getUsers(false).size()).get(2, TimeUnit.SECONDS)); }
                catch (Exception e) { throw new AssertionError("Listener blocked cache reads", e); }
            });
            users.updateUser(100, 44, 55);
        } finally { executor.shutdownNow(); }
    }

    @Test void delayedLegacyCommitAndLocalPinResetCannotPublishInReverseOrder() throws Exception {
        CountDownLatch committed = new CountDownLatch(1), release = new CountDownLatch(1), pinStarted = new CountDownLatch(1);
        when(data.compareAndSetUser(any(), any())).thenAnswer(call -> {
            stored.put(100, copy(call.getArgument(1))); committed.countDown(); await(release); return true;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> legacy = executor.submit(() -> users.updateUser(100, Map.of())); await(committed);
            Future<?> reset = executor.submit(() -> { pinStarted.countDown(); users.setPin(100, ""); }); await(pinStarted);
            release.countDown(); legacy.get(3, TimeUnit.SECONDS); reset.get(3, TimeUnit.SECONDS);
            assertNull(stored.get(100).getPin()); assertNull(users.getUserById(100).getPin());
        } finally { release.countDown(); executor.shutdownNow(); }
    }

    @Test void parentDashboardRefreshRetainsConcurrentRoleAndPinUpdate() {
        stored.get(100).setUserRole(UserRole.PARENT);
        UserModule child = user(200); child.setUserRole(UserRole.OTHER); stored.put(200, child);
        when(data.getAll(UserModule.class)).thenAnswer(call -> {
            List<UserModule> result = snapshot();
            if (stored.get(200).getUserRole() == UserRole.CHILD) {
                UserModule latest = copy(stored.get(100)); latest.setUserRole(UserRole.CHILD); latest.setPin(new byte[]{7}); stored.put(100, latest);
            }
            return result;
        });
        users.updateUser(200, 20, "Child", null, null, UserRole.CHILD, null);
        assertEquals(UserRole.CHILD, stored.get(100).getUserRole());
        assertArrayEquals(new byte[]{7}, stored.get(100).getPin());
    }

    @Test void restoreReadsCurrentPinAndCannotOverwriteAConcurrentlyCreatedUser() {
        stored.get(100).setPin(new byte[]{8});
        users.restoreDefaultSystemUser("restored", 100);
        assertArrayEquals(new byte[]{8}, stored.get(100).getPin());
        beforeCas.set(() -> stored.put(200, user(200)));
        assertThrows(ConflictException.class, () -> users.restoreDefaultSystemUser("new", 200));
        assertEquals("Alice200", stored.get(200).getName());
        assertFalse(stored.get(200).isSystem());
        assertTrue(users.getUsers(false).stream().noneMatch(user -> user.getId() == 200));
    }

    @Test void rejectedCreateHasNoDashboardOrListenerSideEffects() {
        beforeCas.set(() -> stored.put(200, user(200)));
        var listener = mock(UserService.UserChangeListener.class); users.addListener(listener);
        assertThrows(ConflictException.class, () -> users.createUser(20, "Child", null, null, UserRole.CHILD, null));
        verify(cards, never()).createParentalControlCard(anyInt(), anyString(), anyString());
        verifyNoInteractions(listener);
        assertEquals("Alice200", stored.get(200).getName());
    }

    @Test void authenticationReadsCurrentCredentialAndRoleEvenWhenGeneralCacheIsStale() {
        stored.get(100).setPin(new byte[]{9});
        stored.get(100).setUserRole(UserRole.CHILD);
        assertArrayEquals(new byte[]{1, 2}, users.getUserById(100).getPin());
        UserModule actual = users.getUserForAuthentication(100);
        assertArrayEquals(new byte[]{9}, actual.getPin());
        assertEquals(UserRole.CHILD, actual.getUserRole());
        actual.getPin()[0] = 88;
        assertArrayEquals(new byte[]{9}, stored.get(100).getPin());
        stored.remove(100);
        assertNull(users.getUserForAuthentication(100));
    }

    private void mutate(String kind) {
        switch (kind) {
            case "legacy" -> users.updateUser(100, 20, "Renamed", null, null, UserRole.CHILD, null);
            case "whitelist" -> users.updateUser(100, Map.of("new.example", new WhiteListConfig(true, true)));
            case "dashboard" -> users.updateUser(100, new DashboardColumnsView());
            case "automaticDashboard" -> users.updateUserDashboardView(100);
            case "customLists" -> users.updateUser(100, 44, 55);
            case "patch" -> users.patchSettings(100, FamilySettingsPatch.user("{\"name\":\"Renamed\"}"));
            default -> throw new AssertionError();
        }
    }

    private List<UserModule> snapshot() { return stored.values().stream().map(this::copy).toList(); }

    private UserModule user(int id) {
        return new UserModule(id, 20, "Alice" + id, null, null, UserRole.OTHER, false, new byte[]{1, 2},
                new HashMap<>(Map.of("old.example", new WhiteListConfig(true, false))),
                new DashboardColumnsView(new ArrayList<>(List.of(new UiCardColumnPosition(1, 1, 0, true, true))), new ArrayList<>(), new ArrayList<>()), null, null);
    }

    private UserModule copy(UserModule source) {
        if (source == null) return null;
        DashboardColumnsView v = source.getDashboardColumnsView();
        return new UserModule(source.getId(), source.getAssociatedProfileId(), source.getName(), source.getNameKey(), source.getBirthday(),
                source.getUserRole(), source.isSystem(), source.getPin() == null ? null : source.getPin().clone(),
                new HashMap<>(source.getWhiteListConfigByDomains()), v == null ? null : new DashboardColumnsView(new ArrayList<>(v.getOneColumn()),
                new ArrayList<>(v.getTwoColumn()), new ArrayList<>(v.getThreeColumn())), source.getCustomBlacklistId(), source.getCustomWhitelistId());
    }

    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS), "Barrier timed out"); }
}
