// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.service;

import org.eblocker.server.common.TestClock;
import org.eblocker.server.common.data.BonusTimeUsage;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.UsageChangeEvents;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.common.network.TrafficAccounter;
import org.junit.Before;
import org.junit.Test;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ForbiddenException;
import org.restexpress.exception.NotFoundException;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ParentalControlBonusTimeTest {
    private static final int PROFILE_ID = 7;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 14, 12, 0);
    private DataSource dataSource;
    private TestClock clock;
    private ParentalControlService profiles;
    private ParentalControlUsageService usage;
    private UserProfileModule initial;
    private UserModule child;
    private AtomicReference<UserProfileModule> stored;

    @Before
    public void setUp() {
        dataSource = mock(DataSource.class);
        UserService users = mock(UserService.class);
        clock = new TestClock(NOW);
        EnumMap<DayOfWeek, Integer> limits = new EnumMap<>(DayOfWeek.class);
        limits.put(DayOfWeek.MONDAY, 60);
        initial = new UserProfileModule(PROFILE_ID, "Child", "description", null, null, false, false,
                Collections.emptySet(), Collections.emptySet(), UserProfileModule.InternetAccessRestrictionMode.BLACKLIST,
                Collections.emptySet(), limits, true, false, null);
        initial.setForSingleUser(true);
        initial.setControlmodeMaxUsage(true);
        stored = new AtomicReference<>(initial);
        child = mock(UserModule.class);
        when(child.getId()).thenReturn(5);
        when(child.getAssociatedProfileId()).thenReturn(PROFILE_ID);
        when(users.getUserById(5)).thenReturn(child);
        when(dataSource.getAll(UserModule.class)).thenReturn(Collections.singletonList(child));
        when(dataSource.getAll(UserProfileModule.class)).thenReturn(Collections.singletonList(initial));
        when(dataSource.getAll(UsageChangeEvents.class)).thenReturn(Collections.emptyList());
        when(dataSource.get(UserProfileModule.class, PROFILE_ID)).thenAnswer(call -> stored.get());
        when(dataSource.save(any(UserProfileModule.class), eq(PROFILE_ID))).thenAnswer(call -> {
            UserProfileModule saved = call.<UserProfileModule>getArgument(0).copy();
            stored.set(saved);
            return saved;
        });
        profiles = new ParentalControlService(dataSource, users);
        profiles.init();
        usage = new ParentalControlUsageService(5, 5, clock, dataSource, profiles, mock(TrafficAccounter.class), users);
        usage.init();
    }

    @Test
    public void persistsOnlyBonusChangeAndDoesNotMutatePreviouslyPublishedProfile() {
        initial.setBonusTimeUsage(new BonusTimeUsage(NOW, 10));
        UserProfileModule changed = usage.addBonusTimeForToday(PROFILE_ID, 15);
        assertEquals(Integer.valueOf(25), changed.getBonusTimeUsage().getBonusMinutes());
        assertEquals(Integer.valueOf(10), initial.getBonusTimeUsage().getBonusMinutes());
        assertEquals("description", changed.getDescription());
        assertEquals(initial.getMaxUsageTimeByDay(), changed.getMaxUsageTimeByDay());
        assertSame(stored.get(), profiles.getProfile(PROFILE_ID));
        verify(dataSource).save(any(UserProfileModule.class), eq(PROFILE_ID));
    }

    @Test
    public void preservesNegativeWithdrawalsAndClampsTheirResultAtZero() {
        initial.setBonusTimeUsage(new BonusTimeUsage(NOW, 30));
        assertEquals(Integer.valueOf(20), usage.addBonusTimeForToday(PROFILE_ID, -10).getBonusTimeUsage().getBonusMinutes());
        assertEquals(Integer.valueOf(0), usage.addBonusTimeForToday(PROFILE_ID, Integer.MIN_VALUE).getBonusTimeUsage().getBonusMinutes());
    }

    @Test
    public void discardsExpiredBonusUsingTheServerClockDate() {
        initial.setBonusTimeUsage(new BonusTimeUsage(NOW.minusDays(1), Integer.MAX_VALUE));
        BonusTimeUsage bonus = usage.addBonusTimeForToday(PROFILE_ID, 10).getBonusTimeUsage();
        assertEquals(Integer.valueOf(10), bonus.getBonusMinutes());
        assertEquals(NOW, bonus.getDateTime());
    }

    @Test
    public void rejectsOverflowBeforeChangingTheCachedOrStoredProfile() {
        initial.setBonusTimeUsage(new BonusTimeUsage(NOW, Integer.MAX_VALUE));
        assertThrows(BadRequestException.class, () -> usage.addBonusTimeForToday(PROFILE_ID, 1));
        assertSame(initial, profiles.getProfile(PROFILE_ID));
        assertEquals(Integer.valueOf(Integer.MAX_VALUE), stored.get().getBonusTimeUsage().getBonusMinutes());
        verify(dataSource, never()).save(any(UserProfileModule.class), anyInt());
    }

    @Test
    public void dailyLimitAndBonusCannotOverflowIntoANegativeDuration() {
        initial.setBonusTimeUsage(new BonusTimeUsage(NOW, Integer.MAX_VALUE));
        assertEquals(Duration.ofMinutes((long) Integer.MAX_VALUE + 60), usage.getUsageAccount(child).getMaxUsageTime());
    }

    @Test
    public void rejectsMissingProfilesAndProtectsBuiltInTemplates() {
        assertThrows(NotFoundException.class, () -> usage.addBonusTimeForToday(999, 10));
        initial.setBuiltin(true);
        assertThrows(ForbiddenException.class, () -> usage.addBonusTimeForToday(PROFILE_ID, 10));
        verify(dataSource, never()).save(any(UserProfileModule.class), anyInt());
    }

    @Test
    public void retainsEditableStandardProfileAndSupportsAtomicReset() {
        initial.setBuiltin(true);
        initial.setStandard(true);
        usage.addBonusTimeForToday(PROFILE_ID, 10);
        profiles.changeProfileSettings(PROFILE_ID, profile -> profile.setBonusTimeUsage(null));
        assertNull(stored.get().getBonusTimeUsage());
        assertTrue(stored.get().isBuiltin());
        assertTrue(stored.get().isStandard());
    }

    @Test
    public void failedPersistenceDoesNotPublishAnUnconfirmedBonus() {
        when(dataSource.save(any(UserProfileModule.class), eq(PROFILE_ID))).thenThrow(new IllegalStateException("storage unavailable"));
        assertThrows(IllegalStateException.class, () -> usage.addBonusTimeForToday(PROFILE_ID, 10));
        assertNull(profiles.getProfile(PROFILE_ID).getBonusTimeUsage());
    }

    @Test
    public void concurrentBonusGrantsAndSettingsUpdatesPreserveEveryChange() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> work = new ArrayList<>();
        try {
            for (int worker = 0; worker < 4; worker++) {
                work.add(executor.submit(() -> {
                    start.await();
                    for (int count = 0; count < 40; count++) usage.addBonusTimeForToday(PROFILE_ID, 1);
                    return null;
                }));
            }
            work.add(executor.submit(() -> {
                start.await();
                for (int count = 0; count < 40; count++) profiles.changeProfileSettings(PROFILE_ID, profile -> profile.setControlmodeUrls(true));
                return null;
            }));
            start.countDown();
            for (Future<?> future : work) future.get(20, TimeUnit.SECONDS);
            assertEquals(Integer.valueOf(160), profiles.getProfile(PROFILE_ID).getBonusTimeUsage().getBonusMinutes());
            assertTrue(profiles.getProfile(PROFILE_ID).isControlmodeUrls());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void filterRefreshCopiesPublishedSettingsAndPublishesOnlyAfterSuccessfulPersistence() {
        initial.setInaccessibleSitesPackages(Collections.singleton(99));
        when(dataSource.save(any(UserProfileModule.class), eq(PROFILE_ID))).thenAnswer(call -> {
            assertEquals(Collections.singleton(99), profiles.getProfile(PROFILE_ID).getInaccessibleSitesPackages());
            throw new IllegalStateException("storage unavailable");
        });
        assertThrows(IllegalStateException.class, () -> profiles.updateFilters(Collections.emptyList()));
        assertSame(initial, profiles.getProfile(PROFILE_ID));
        assertEquals(Collections.singleton(99), initial.getInaccessibleSitesPackages());
    }

    @Test
    public void overlappingFilterRefreshCannotOverwriteCommittedBonusTime() throws Exception {
        initial.setInaccessibleSitesPackages(Collections.singleton(99));
        CountDownLatch filterWaitingToCommit = new CountDownLatch(1);
        CountDownLatch allowFilterCommit = new CountDownLatch(1);
        AtomicBoolean firstSave = new AtomicBoolean(true);
        AtomicReference<Thread> bonusThread = new AtomicReference<>();
        when(dataSource.save(any(UserProfileModule.class), eq(PROFILE_ID))).thenAnswer(call -> {
            if (firstSave.getAndSet(false)) {
                filterWaitingToCommit.countDown();
                assertTrue(allowFilterCommit.await(10, TimeUnit.SECONDS));
            }
            UserProfileModule saved = call.<UserProfileModule>getArgument(0).copy();
            stored.set(saved);
            return saved;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> filter = executor.submit(() -> profiles.updateFilters(Collections.emptyList()));
            assertTrue(filterWaitingToCommit.await(10, TimeUnit.SECONDS));
            Future<?> bonus = executor.submit(() -> {
                bonusThread.set(Thread.currentThread());
                usage.addBonusTimeForToday(PROFILE_ID, 10);
            });
            // Wait for a real scheduling outcome, not an arbitrary sleep: the bonus writer either
            // reaches the shared commit monitor or completes while the older filter save is paused.
            // The latter interleaving reproduced the lost bonus before updateFilters used that monitor.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!bonus.isDone()) {
                Thread thread = bonusThread.get();
                ThreadInfo info = thread == null ? null : ManagementFactory.getThreadMXBean().getThreadInfo(thread.getId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null &&
                        info.getLockInfo().getIdentityHashCode() == System.identityHashCode(profiles)) break;
                assertTrue("Bonus writer did not reach the commit boundary", System.nanoTime() < deadline);
                Thread.onSpinWait();
            }
            allowFilterCommit.countDown();
            filter.get(10, TimeUnit.SECONDS);
            bonus.get(10, TimeUnit.SECONDS);
            assertNotNull(stored.get().getBonusTimeUsage());
            assertEquals(Integer.valueOf(10), stored.get().getBonusTimeUsage().getBonusMinutes());
            assertTrue(stored.get().getInaccessibleSitesPackages().isEmpty());
            assertSame(stored.get(), profiles.getProfile(PROFILE_ID));
            assertEquals(Collections.singleton(99), initial.getInaccessibleSitesPackages());
        } finally {
            allowFilterCommit.countDown();
            executor.shutdownNow();
        }
    }
}
