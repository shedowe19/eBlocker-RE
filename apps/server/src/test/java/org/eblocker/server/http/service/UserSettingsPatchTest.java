package org.eblocker.server.http.service;

import org.eblocker.server.common.data.*;
import org.eblocker.server.http.controller.FamilySettingsPatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restexpress.exception.ConflictException;
import org.restexpress.exception.ForbiddenException;
import org.restexpress.exception.NotFoundException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserSettingsPatchTest {
    DataSource data = mock(DataSource.class);
    DashboardCardService cards = mock(DashboardCardService.class);
    UserService users = new UserService(data, mock(DeviceService.class), cards, "default");
    UserModule current;

    @BeforeEach void init() {
        current = new UserModule(100, 20, "Alice", null, null, UserRole.OTHER, false, new byte[]{1}, null, null, null, null);
        when(data.get(UserModule.class, 100)).thenReturn(current);
        when(data.get(UserProfileModule.class, 20)).thenReturn(mock(UserProfileModule.class));
        when(data.getAll(UserModule.class)).thenReturn(List.of(current));
        when(data.compareAndSetUser(any(), any())).thenReturn(true);
    }

    @Test void commitsLatestRecordWithOnlyRequestedUserFields() {
        current.setPin(new byte[]{3,4});
        UserModule updated = users.patchSettings(100, FamilySettingsPatch.user("{\"name\":\"Bob\"}"));
        assertEquals("Bob", updated.getName());
        assertArrayEquals(new byte[]{3,4}, updated.getPin());
        assertEquals("Alice", current.getName());
        verify(data).compareAndSetUser(same(current), same(updated));
        verify(data, never()).save(any(UserModule.class), anyInt());
    }

    @Test void rejectsConcurrentChangesBeforePublishingOrRoleSideEffects() {
        when(data.compareAndSetUser(any(), any())).thenReturn(false);
        var listener = mock(UserService.UserChangeListener.class);
        users.addListener(listener);
        assertThrows(ConflictException.class, () -> users.patchSettings(100, FamilySettingsPatch.user("{\"userRole\":\"CHILD\"}")));
        verify(cards, never()).createParentalControlCard(anyInt(), anyString(), anyString());
        verifyNoInteractions(listener);
        assertEquals(UserRole.OTHER, current.getUserRole());
    }

    @Test void roleChangeKeepsExistingDependentDashboardBehavior() {
        users.patchSettings(100, FamilySettingsPatch.user("{\"userRole\":\"CHILD\"}"));
        verify(cards).createParentalControlCard(100, "PARENTAL_CONTROL", "FAM");
    }

    @Test void refusesMissingAndBuiltInUsers() {
        assertThrows(NotFoundException.class, () -> users.patchSettings(404, FamilySettingsPatch.user("{\"name\":\"Bob\"}")));
        current.setSystem(true);
        assertThrows(ForbiddenException.class, () -> users.patchSettings(100, FamilySettingsPatch.user("{\"name\":\"Bob\"}")));
        verify(data, never()).compareAndSetUser(any(), any());
    }
}
