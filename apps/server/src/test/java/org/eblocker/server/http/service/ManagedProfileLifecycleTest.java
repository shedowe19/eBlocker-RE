package org.eblocker.server.http.service;

import org.eblocker.server.common.data.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedProfileLifecycleTest {
    @Test void independentProfilesSurviveRestartWhileOrphanedPrivateProfilesAreStillCleaned() {
        DataSource source = mock(DataSource.class);
        UserService users = mock(UserService.class);
        Map<Integer, UserProfileModule> stored = new HashMap<>();
        when(source.nextId(UserProfileModule.class)).thenReturn(100, 101);
        when(source.save(any(UserProfileModule.class), anyInt())).thenAnswer(call -> {
            UserProfileModule profile = call.getArgument(0); stored.put(profile.getId(), profile.copy()); return profile;
        });
        when(source.getAll(UserProfileModule.class)).thenAnswer(call -> new ArrayList<>(stored.values()));
        when(source.getAll(UserModule.class)).thenReturn(List.of());
        doAnswer(call -> { stored.remove(call.getArgument(1)); return null; }).when(source).delete(eq(UserProfileModule.class), anyInt());
        ParentalControlService first = new ParentalControlService(source, users);
        UserProfileModule managed = first.storeManagedProfile(empty());
        UserProfileModule privateProfile = first.storeNewProfile(empty());
        assertFalse(managed.isForSingleUser());
        assertTrue(privateProfile.isForSingleUser());
        ParentalControlService restarted = new ParentalControlService(source, users);
        restarted.init();
        assertNotNull(restarted.getProfile(managed.getId()));
        assertNull(restarted.getProfile(privateProfile.getId()));
        assertTrue(stored.containsKey(managed.getId()));
        verify(source, never()).delete(UserProfileModule.class, managed.getId());
        verify(source).delete(UserProfileModule.class, privateProfile.getId());
    }

    private UserProfileModule empty() {
        return new UserProfileModule(null, "Reusable", "", null, null, false, false, Set.of(), Set.of(),
                UserProfileModule.InternetAccessRestrictionMode.BLACKLIST, Set.of(), Map.of(), false, false, null);
    }
}
