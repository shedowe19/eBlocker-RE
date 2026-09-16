package org.eblocker.server.http.controller.impl;

import io.netty.handler.codec.http.HttpMethod;
import org.eblocker.server.common.PauseDeviceController;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.DeviceFactory;
import org.eblocker.server.common.data.IpAddress;
import org.eblocker.server.common.network.NetworkInterfaceWrapper;
import org.eblocker.server.common.network.NetworkStateMachine;
import org.eblocker.server.common.openvpn.OpenVpnService;
import org.eblocker.server.common.registration.DeviceRegistrationProperties;
import org.eblocker.server.common.service.FeatureToggleRouter;
import org.eblocker.server.http.service.AnonymousService;
import org.eblocker.server.http.service.DevicePermissionsService;
import org.eblocker.server.http.service.DeviceScanningService;
import org.eblocker.server.http.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;
import org.restexpress.exception.ForbiddenException;
import org.restexpress.exception.NotFoundException;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DeviceSettingsControllerTest {
    private static final String ID = "device:001122334455";
    private final DeviceService devices = mock(DeviceService.class);
    private final NetworkStateMachine network = mock(NetworkStateMachine.class);
    private final PauseDeviceController pause = mock(PauseDeviceController.class);
    private final AnonymousService anonymity = mock(AnonymousService.class);
    private final AtomicReference<Device> stored = new AtomicReference<>();
    private DeviceControllerImpl controller;

    @BeforeEach
    void setUp() {
        Device original = new Device();
        original.setId(ID);
        original.setName("Before");
        original.setDefaultSystemUser(5);
        original.setAssignedUser(5);
        original.setOperatingUser(5);
        original.setIpAddresses(List.of(IpAddress.parse("192.0.2.2")));
        stored.set(original);
        when(devices.getDeviceById(ID)).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> { stored.set(invocation.getArgument(0)); return null; })
                .when(devices).updateDevice(any(Device.class));
        NetworkInterfaceWrapper nic = mock(NetworkInterfaceWrapper.class);
        when(nic.getHardwareAddressHex()).thenReturn("aabbccddeeff");
        controller = new DeviceControllerImpl(anonymity, mock(DevicePermissionsService.class),
                mock(DeviceScanningService.class), devices, mock(DeviceRegistrationProperties.class),
                mock(FeatureToggleRouter.class), nic, network, mock(OpenVpnService.class), pause,
                mock(DeviceFactory.class));
    }

    private Request request(String id, String json) {
        Request request = ControllerTestUtils.createRequest(json, HttpMethod.PATCH,
                "/api/adminconsole/devices/" + id + "/settings");
        request.addHeader("deviceId", id);
        return request;
    }

    private Device patch(String json) {
        return controller.updateDeviceSettings(request(ID, json), new Response());
    }

    @Test
    void returnsActualSavedDeviceAndRefreshesOnlineStatus() {
        doAnswer(invocation -> { Device device = invocation.getArgument(0); device.setOnline(true); return null; })
                .when(devices).setOnlineStatus(any(Device.class));
        Device before = stored.get();
        before.markAsCurrentDevice();
        Device response = patch("{\"name\":\" New name \",\"enabled\":false}");
        assertEquals("New name", stored.get().getName());
        assertFalse(response.isEnabled());
        assertTrue(response.isOnline());
        assertFalse(response.isCurrentDevice(), "The caller must not inherit another request's current-device flag");
        assertEquals(5, response.getDefaultSystemUser());
        assertTrue(response.isFilterAdsEnabled());
        assertNotSame(before, stored.get());
        assertEquals("Before", before.getName());
        assertTrue(before.isEnabled());
        assertNotSame(stored.get(), response);
        verify(network).deviceStateChanged(stored.get());
    }

    @Test
    void nameAndFilterChangesDoNotReconfigureRouting() {
        stored.get().setUseAnonymizationService(true);
        stored.get().setUseVPNProfileID(17);
        Device response = patch("{\"name\":\"New name\",\"filterAdsEnabled\":false}");
        assertTrue(response.isUseAnonymizationService());
        assertEquals(17, response.getUseVPNProfileID());
        verify(devices).updateDevice(any());
        verifyNoInteractions(network, anonymity);
    }

    @Test
    void sslChangesRefreshFirewallWithoutReconfiguringAnonymization() {
        Device response = patch("{\"sslEnabled\":true}");
        assertTrue(response.isSslEnabled());
        verify(network).deviceStateChanged(any(Device.class));
        verifyNoInteractions(anonymity);
    }

    @Test
    void unknownAndInfrastructureDevicesAreNeverWritten() {
        assertThrows(NotFoundException.class,
                () -> controller.updateDeviceSettings(request("missing", "{\"name\":\"X\"}"), new Response()));
        assertThrows(ForbiddenException.class,
                () -> controller.updateDeviceSettings(request("device:aabbccddeeff", "{\"name\":\"X\"}"), new Response()));
        stored.get().setIsGateway(true);
        assertThrows(ForbiddenException.class, () -> patch("{\"name\":\"X\"}"));
        stored.get().setIsGateway(false);
        stored.get().setIsEblocker(true);
        assertThrows(ForbiddenException.class, () -> patch("{\"enabled\":false}"));
        verify(devices, never()).updateDevice(any());
    }

    @Test
    void pausedDevicesAllowIndependentSettingsButRejectProtectionChanges() {
        stored.get().setPaused(true);
        stored.get().setEnabled(false);
        assertThrows(ConflictException.class, () -> patch("{\"enabled\":true,\"name\":\"Not saved\"}"));
        assertEquals("Before", stored.get().getName());
        Device response = patch("{\"name\":\"Paused tablet\",\"filterAdsEnabled\":false}");
        assertEquals("Paused tablet", response.getName());
        assertTrue(response.isPaused());
        assertFalse(response.isEnabled());
        assertFalse(response.isFilterAdsEnabled());
        verify(pause, never()).pauseDevice(any(), eq(0L));
    }

    @Test
    void invalidPayloadAndFailedValidationDoNotMutateCachedState() {
        Device original = stored.get();
        assertThrows(BadRequestException.class, () -> patch("{\"name\":\"X\",\"sslEnabled\":\"true\"}"));
        verify(devices, never()).updateDevice(any());
        doThrow(new BadRequestException("invalid user")).when(devices).updateDevice(any());
        assertThrows(BadRequestException.class, () -> patch("{\"name\":\"X\"}"));
        assertSame(original, stored.get());
        assertEquals("Before", original.getName());
        verifyNoInteractions(network, anonymity);
    }

    @Test
    void propagatesPostSaveFailuresWithoutOverwritingTheCommittedState() {
        RuntimeException failure = new IllegalStateException("firewall unavailable");
        doThrow(failure).when(network).deviceStateChanged(any(Device.class));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> patch("{\"enabled\":false}")));
        assertFalse(stored.get().isEnabled());
        verify(devices, times(1)).updateDevice(any());
    }

    @Test
    void concurrentPatchesMergeAgainstTheLatestSavedDevice() throws Exception {
        CountDownLatch firstSaving = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        doAnswer(invocation -> {
            Device device = invocation.getArgument(0);
            if (device.getName().equals("First") && device.isFilterAdsEnabled()) {
                firstSaving.countDown();
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
            }
            stored.set(device);
            return null;
        }).when(devices).updateDevice(any());
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> patch("{\"name\":\"First\"}"));
            assertTrue(firstSaving.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> patch("{\"filterAdsEnabled\":false}"));
            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertEquals("First", stored.get().getName());
            assertFalse(stored.get().isFilterAdsEnabled());
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void assignmentPreservesPausedProtectionAndRoutingAndUpdatesBothUsers() {
        Device original = stored.get();
        original.setPaused(true);
        original.setEnabled(false);
        original.setUseAnonymizationService(true);
        original.setUseVPNProfileID(17);
        Device assigned = controller.assignUser(ID, 42);
        assertEquals(42, assigned.getAssignedUser());
        assertEquals(42, assigned.getOperatingUser());
        assertEquals(5, assigned.getDefaultSystemUser());
        assertEquals(5, original.getAssignedUser());
        assertTrue(assigned.isPaused());
        assertFalse(assigned.isEnabled());
        assertEquals(17, assigned.getUseVPNProfileID());
        verifyNoInteractions(network, anonymity);
        verify(pause, never()).pauseDevice(any(), anyLong());
    }

    @Test
    void assignmentRejectsInfrastructureWithoutSaving() {
        stored.get().setIsGateway(true);
        assertThrows(ForbiddenException.class, () -> controller.assignUser(ID, 42));
        verify(devices, never()).updateDevice(any());
    }
}
