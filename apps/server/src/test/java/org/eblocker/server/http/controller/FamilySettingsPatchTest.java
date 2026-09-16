package org.eblocker.server.http.controller;

import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.common.data.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restexpress.exception.BadRequestException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class FamilySettingsPatchTest {
    @ParameterizedTest
    @ValueSource(strings = {"{}", "null", "[]", "{\"pin\":\"secret\"}", "{\"name\":\"a\",\"name\":\"b\"}",
            "{\"system\":true}", "{\"birthday\":[2023,2,29]}", "{\"associatedProfileId\":1.5}",
            "{\"associatedProfileId\":2147483648}", "{\"userRole\":\"ADMIN\"}", "{\"name\":\"\"}", "{\"name\":true}"})
    void rejectsUnknownPrivilegedCoercedDuplicateAndMalformedUserFields(String body) {
        assertThrows(BadRequestException.class, () -> FamilySettingsPatch.user(body));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"bonusTimeUsage\":null}", "{\"builtin\":false}", "{\"forSingleUser\":false}",
            "{\"maxUsageTimeByDay\":{\"MONDAY\":1441}}", "{\"maxUsageTimeByDay\":{\"MONDAY\":-1}}",
            "{\"maxUsageTimeByDay\":{\"TODAY\":60}}", "{\"controlmodeTime\":\"true\"}",
            "{\"accessibleSitesPackages\":[1,1]}", "{\"internetAccessRestrictionMode\":3}",
            "{\"internetAccessContingents\":[{\"onDay\":1,\"fromMinutes\":60,\"tillMinutes\":60}]}",
            "{\"internetAccessContingents\":[{\"onDay\":10,\"fromMinutes\":0,\"tillMinutes\":60}]}"})
    void rejectsInternalOwnershipUsageAndInvalidTimeWindows(String body) {
        assertThrows(BadRequestException.class, () -> FamilySettingsPatch.profile(body));
    }

    @Test void userPatchPreservesCredentialsAndDomainStateAndNeverMutatesOriginal() {
        UserModule original = new UserModule(50, 20, "Old", "translated", LocalDate.of(2001, 1, 2), UserRole.CHILD,
                false, new byte[]{1,2,3}, null, null, 31, 32);
        UserModule updated = FamilySettingsPatch.user("{\"name\":\" New \",\"birthday\":null}").applyUser(original);
        assertEquals("New", updated.getName());
        assertNull(updated.getNameKey());
        assertNull(updated.getBirthday());
        assertArrayEquals(original.getPin(), updated.getPin());
        assertNotSame(original.getPin(), updated.getPin());
        assertEquals(20, updated.getAssociatedProfileId());
        assertEquals(31, updated.getCustomBlacklistId());
        assertEquals(UserRole.CHILD, updated.getUserRole());
        assertEquals("Old", original.getName());
        assertNotNull(original.getBirthday());
    }

    @Test void schedulePatchMergesOnlyRequestedDaysAndKeepsControlState() {
        UserProfileModule original = profile();
        original.setInternetBlocked(true);
        original.setForSingleUser(true);
        UserProfileModule updated = original.copy();
        FamilySettingsPatch.profile("{\"maxUsageTimeByDay\":{\"MONDAY\":1440},\"internetAccessContingents\":[{\"onDay\":9,\"fromMinutes\":0,\"tillMinutes\":1440}]}").applyProfile(updated);
        assertEquals(Map.of(DayOfWeek.MONDAY, 1440, DayOfWeek.TUESDAY, 90), updated.getMaxUsageTimeByDay());
        assertEquals(60, original.getMaxUsageTimeByDay().get(DayOfWeek.MONDAY));
        assertTrue(updated.isInternetBlocked());
        assertTrue(updated.isForSingleUser());
        var window = updated.getInternetAccessContingents().iterator().next();
        assertNull(window.getTotalMinutes());
        assertTrue(original.getInternetAccessContingents().isEmpty());
    }

    @Test void preservesExistingWindowMetadataWithoutInventingUsageSemantics() {
        UserProfileModule profile = profile();
        FamilySettingsPatch.profile("{\"internetAccessContingents\":[{\"onDay\":1,\"fromMinutes\":60,\"tillMinutes\":180,\"totalMinutes\":90}]}").applyProfile(profile);
        assertEquals(90, profile.getInternetAccessContingents().iterator().next().getTotalMinutes());
    }

    @Test void nullAssignmentMeansRestoreOwnDefaultAndNothingElseIsWritable() {
        assertNull(FamilySettingsPatch.assignment("{\"userId\":null}"));
        assertEquals(123, FamilySettingsPatch.assignment("{\"userId\":123}"));
        assertThrows(BadRequestException.class, () -> FamilySettingsPatch.assignment("{\"userId\":123,\"enabled\":false}"));
    }

    static UserProfileModule profile() {
        return new UserProfileModule(20, "Profile", "", null, null, false, false, Set.of(), Set.of(),
                UserProfileModule.InternetAccessRestrictionMode.BLACKLIST, Set.of(),
                Map.of(DayOfWeek.MONDAY, 60, DayOfWeek.TUESDAY, 90), true, false, null);
    }
}
