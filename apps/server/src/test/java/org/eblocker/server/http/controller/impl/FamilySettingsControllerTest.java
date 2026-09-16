package org.eblocker.server.http.controller.impl;

import io.netty.handler.codec.http.HttpMethod;
import org.eblocker.server.common.data.*;
import org.eblocker.server.http.controller.DeviceController;
import org.eblocker.server.http.controller.FamilySettingsController;
import org.eblocker.server.http.service.*;
import org.junit.jupiter.api.Test;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FamilySettingsControllerTest {
    UserService users = mock(UserService.class);
    ParentalControlService profiles = mock(ParentalControlService.class);
    ParentalControlFilterListsService filters = mock(ParentalControlFilterListsService.class);
    DeviceService devices = mock(DeviceService.class);
    DeviceController deviceController = mock(DeviceController.class);
    FamilySettingsController controller = new FamilySettingsController(users, profiles, filters, devices, deviceController);

    Request request(String body) {
        Request request = ControllerTestUtils.createRequest(body, HttpMethod.PATCH, "/api/adminconsole/devices/device:abc/assignment");
        request.addHeader("deviceId", "device:abc");
        request.addHeader("id", "20");
        return request;
    }

    @Test void rejectsUnknownFilterWithoutApplyingAnyProfileChange() {
        assertThrows(BadRequestException.class, () -> controller.patchProfile(request("{\"accessibleSitesPackages\":[999]}"), new Response()));
        verifyNoInteractions(profiles);
    }

    @Test void resetAssignmentSelectsOnlyTheDevicesOwnDefault() {
        Device device = new Device(); device.setId("device:abc"); device.setDefaultSystemUser(5);
        UserModule own = mock(UserModule.class); when(own.isSystem()).thenReturn(true);
        when(devices.getDeviceById("device:abc")).thenReturn(device);
        when(users.getUserById(5)).thenReturn(own);
        controller.patchAssignment(request("{\"userId\":null}"), new Response());
        verify(deviceController).assignUser("device:abc", 5);
        UserModule foreign = mock(UserModule.class); when(foreign.isSystem()).thenReturn(true);
        when(users.getUserById(6)).thenReturn(foreign);
        assertThrows(ForbiddenException.class, () -> controller.patchAssignment(request("{\"userId\":6}"), new Response()));
        verify(deviceController, never()).assignUser("device:abc", 6);
    }

    @Test void missingUserOrDeviceCannotBeAssigned() {
        assertThrows(NotFoundException.class, () -> controller.patchAssignment(request("{\"userId\":100}"), new Response()));
        when(devices.getDeviceById("device:abc")).thenReturn(new Device());
        assertThrows(NotFoundException.class, () -> controller.patchAssignment(request("{\"userId\":100}"), new Response()));
        verifyNoInteractions(deviceController);
    }
}
