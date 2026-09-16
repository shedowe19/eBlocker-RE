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
package org.eblocker.server.http.service;

import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.DeviceFactory;
import org.eblocker.server.common.data.Ip4Address;
import org.eblocker.server.common.data.IpAddress;
import org.eblocker.server.common.data.MacPrefix;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.network.IpResponseTable;
import org.eblocker.server.common.network.NetworkInterfaceWrapper;
import org.eblocker.server.common.network.NetworkInterfaceWrapper.IpAddressChangeListener;
import org.eblocker.server.common.registration.DeviceRegistrationProperties;
import org.eblocker.server.http.service.DeviceService.DeviceChangeListener;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class DeviceServiceTest {

    private static final String GATEWAY_ID = "device:000000000001";
    private static final String DEVICE_ONLINE_ID = "device:000000000003";
    private static final String DEVICE_OFFLINE_ID = "device:000000000004";
    private static final String DEVICE_MULTIPLE_IPS_ID = "device:000000000005";
    private static final String DEVICE_CONFLICT_ID = "device:000000000006";
    private static final String DEVICE_CONFLICT_ID_2 = "device:000000000007";
    private static final String EBLOCKER_IP = "192.168.1.2";
    private static final int nextUserId = 104;

    private DataSource dataSource;
    private DeviceService deviceService;
    private UserAgentService userAgentService;
    private NetworkInterfaceWrapper networkInterfaceWrapper;
    private DeviceService.DeviceChangeListener listener;
    private List<Device> devices;
    private DeviceRegistrationProperties deviceRegistrationProperties;
    private DeviceFactory deviceFactory;
    private IpResponseTable ipResponseTable;
    private Clock clock;
    private MacPrefix macPrefix;

    @Before
    public void setup() throws IOException {
        // setup data source and mock devices
        dataSource = Mockito.mock(DataSource.class);
        listener = Mockito.mock(DeviceService.DeviceChangeListener.class);
        userAgentService = Mockito.mock(UserAgentService.class);
        networkInterfaceWrapper = Mockito.mock(NetworkInterfaceWrapper.class);
        deviceRegistrationProperties = Mockito.mock(DeviceRegistrationProperties.class);
        macPrefix = new MacPrefix();
        deviceFactory = new DeviceFactory(dataSource, macPrefix);
        ipResponseTable = new IpResponseTable();
        clock = Mockito.mock(Clock.class);

        devices = new ArrayList<>();
        devices.add(createMockDevice(GATEWAY_ID, "192.168.1.1", 100, true, true, true, false));
        devices.add(createMockDevice(DEVICE_CONFLICT_ID, new String[]{ EBLOCKER_IP, "192.168.1.7" }, false, true, false, false));
        devices.add(createMockDevice(DEVICE_ONLINE_ID, "192.168.1.8", 100, true, true, false, false));
        devices.add(createMockDevice(DEVICE_OFFLINE_ID, "192.168.1.4", false, false, false, false));
        devices.add(createMockDevice(DEVICE_MULTIPLE_IPS_ID, new String[]{ "192.168.1.5", "192.168.1.6" }, true, true, false, false));
        Mockito.when(dataSource.getDevices()).then(i -> copyDevices(devices));
        Mockito.doAnswer(m -> {
            Device device = m.getArgument(0);
            int i = devices.indexOf(device);
            if (i == -1) {
                devices.add(device);
            } else {
                devices.set(i, device);
            }
            return null;
        }).when(dataSource).save(Mockito.any(Device.class));

        // Mock user
        UserModule user = new UserModule(100, 100, "user", "user", null, null, false,
                null, Collections.emptyMap(), null, null, null);
        Mockito.when(dataSource.get(UserModule.class, 100)).thenReturn(user);
        UserModule userNext = new UserModule(nextUserId, 101, "user2", "user2", null, null, false, null, Collections.emptyMap(), null,
                null, null);
        Mockito.when(dataSource.get(UserModule.class, nextUserId)).thenReturn(userNext);

        Mockito.when(networkInterfaceWrapper.getFirstIPv4Address()).thenReturn(Ip4Address.parse(EBLOCKER_IP));
        // setup device service
        deviceService = new DeviceService(dataSource, deviceRegistrationProperties, userAgentService,
                networkInterfaceWrapper, deviceFactory, ipResponseTable, clock, 90, macPrefix);
        deviceService.init();
        deviceService.addListener(listener);
    }

    @Test
    public void testListenerRegistered() {
        Mockito.verify(networkInterfaceWrapper).addIpAddressChangeListener(Mockito.any(IpAddressChangeListener.class));
    }

    @Test
    public void testEblockerRemovesDeviceIpWithConflictOnStartup() {
        // Device is not removed, only the eBlocker's IP is removed from the IPs
        // the device has (it only has one other IP)
        Assert.assertEquals(1, devices.get(1).getIpAddresses().size());
        Assert.assertEquals(IpAddress.parse("192.168.1.7"), devices.get(1).getIpAddresses().get(0));
        // It previously had a fixed IP, the respective flag has been removed as
        // well
        Assert.assertFalse(devices.get(1).isIpAddressFixed());
    }

    @Test
    public void testEblockerRemovesDeviceIpWithConflictOnListenerCall() {
        // Device is not removed, only the eBlocker's IP is removed from the IPs
        // the device has (it only has one other IP)
        Assert.assertEquals(1, devices.get(1).getIpAddresses().size());
        Assert.assertEquals(IpAddress.parse("192.168.1.7"), devices.get(1).getIpAddresses().get(0));
        // It previously had a fixed IP, the respective flag has been removed as
        // well
        Assert.assertFalse(devices.get(1).isIpAddressFixed());

        // This device was not present during startup and thus is still conflicting
        devices.add(createMockDevice(DEVICE_CONFLICT_ID_2, new String[]{ EBLOCKER_IP }, false, true, false, false));

        // Call the listener
        ArgumentCaptor<IpAddressChangeListener> captor = ArgumentCaptor.forClass(IpAddressChangeListener.class);
        Mockito.verify(networkInterfaceWrapper).addIpAddressChangeListener(captor.capture());
        IpAddressChangeListener listener = captor.getValue();
        listener.onIpAddressChange(true, false);
        // Device has its IP removed, has no other IP
        Assert.assertEquals(0, devices.get(5).getIpAddresses().size());
        // Its fixed-IP-flag has been removed as well
        Assert.assertFalse(devices.get(5).isIpAddressFixed());
    }

    @Test
    public void testUpdateLastSeen() {
        ipResponseTable.put("000000000003", Ip4Address.parse("192.168.0.23"), 0);
        Mockito.verify(dataSource).updateLastSeen(devices.get(2));
    }

    @Test
    public void testDoNotUpdateLastSeen() {
        // Non-existing device:
        ipResponseTable.put("ffffff000001", Ip4Address.parse("192.168.0.23"), 0);
        Mockito.verify(dataSource, Mockito.never()).updateLastSeen(Mockito.any());
   }

    @Test
    public void testGetDeviceById() {
        for (Device device : devices) {
            Device retrievedDevice = deviceService.getDeviceById(device.getId());
            Assert.assertNotNull(retrievedDevice);
            Assert.assertEquals(device.getId(), retrievedDevice.getId());
        }
    }

    @Test
    public void testGetDeviceByIp() {
        for (Device device : devices) {
            for (IpAddress ip : device.getIpAddresses()) {
                Device retrievedDevice = deviceService.getDeviceByIp(ip);
                Assert.assertNotNull(retrievedDevice);
                Assert.assertEquals(device.getId(), retrievedDevice.getId());
                Assert.assertTrue(retrievedDevice.getIpAddresses().contains(ip));
            }
        }
    }

    @Test
    public void testNewDeviceById() {
        // force cache refresh to avoid lazy loading
        deviceService.refresh();

        // setup new device
        Device newDevice = createMockDevice("device:800000000004", "192.168.1.3", true, true, false, false);
        devices.add(newDevice);

        // retrieve device
        Device retrievedDevice = deviceService.getDeviceById("device:800000000004");

        // check retrieved device
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(newDevice.getId(), retrievedDevice.getId());
    }

    @Test
    public void testNewDeviceByIp() {
        // force cache refresh to avoid lazy loading
        deviceService.refresh();

        // setup new device
        Device newDevice = createMockDevice("device:800000000004", "192.168.1.3", true, true, false, false);
        devices.add(newDevice);

        // retrieve device
        Device retrievedDevice = deviceService.getDeviceByIp(IpAddress.parse("192.168.1.3"));

        // check retrieved device
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(newDevice.getId(), retrievedDevice.getId());
        Assert.assertEquals(newDevice.getIpAddresses(), retrievedDevice.getIpAddresses());
    }

    @Test
    public void testIpChange() {
        Device device = devices.get(0);

        // retrieve device by ip
        Device retrievedDevice = deviceService.getDeviceByIp(device.getIpAddresses().get(0));
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // change ip of device
        IpAddress newIp = IpAddress.parse("192.168.2.1");
        IpAddress oldIp = device.getIpAddresses().get(0);
        device.setIpAddresses(Collections.singletonList(newIp));

        // retrieve again
        retrievedDevice = deviceService.getDeviceByIp(newIp);
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // check entry with old ip has been removed
        Assert.assertNull(deviceService.getDeviceByIp(oldIp));
    }

    @Test
    public void testRemoval() {
        Device device = devices.get(3);

        // ensure entry is cached
        Device retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        retrievedDevice = deviceService.getDeviceByIp(device.getIpAddresses().get(0));
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // remove entry
        devices.remove(device);

        // refresh cache and check entry is gone
        deviceService.refresh();
        Assert.assertNull(deviceService.getDeviceById(device.getId()));
        Assert.assertNull(deviceService.getDeviceByIp(device.getIpAddresses().get(0)));
    }

    @Test
    public void testDeleteOnlineDevice() {
        Device device = devices.get(2);

        deviceService.delete(device);
        Mockito.verify(dataSource, Mockito.never()).delete(device);

        Assert.assertEquals(deviceService.getDeviceById(device.getId()), device);
        Assert.assertEquals(deviceService.getDeviceByIp(device.getIpAddresses().get(0)), device);
        Mockito.verify(listener, Mockito.never()).onDelete(device);
    }

    @Test
    public void testDeleteOfflineDevice() {
        Device device = devices.get(3);

        deviceService.delete(device);
        Mockito.verify(dataSource).delete(device);

        // remove entry
        devices.remove(device);

        Assert.assertNull(deviceService.getDeviceById(device.getId()));
        Assert.assertNull(deviceService.getDeviceByIp(device.getIpAddresses().get(0)));
        Mockito.verify(listener).onDelete(device);
    }

    @Test
    public void testUpdate() {
        // ensure original device is in cache
        Device device = devices.get(0);
        Device retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());
        Assert.assertEquals(device.getOperatingUser(), retrievedDevice.getOperatingUser());

        // setup updated device
        device = createMockDevice(devices.get(0).getId(), "192.168.3.1", devices.get(0).getOperatingUser(), true, true, false, false);

        // update device
        deviceService.updateDevice(device);

        // check it has been updated correctly
        retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // check previous ip mapping has been removed
        Assert.assertNull(deviceService.getDeviceByIp(IpAddress.parse("192.168.1.1")));

        // check current ip is assigned
        retrievedDevice = deviceService.getDeviceByIp(device.getIpAddresses().get(0));
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // check listener has been notified
        Mockito.verify(listener).onChange(retrievedDevice);
    }

    @Test
    public void testRefreshDoesNotOverwriteCompletedUpdateWithOlderSnapshot() throws Exception {
        try (BlockedRefresh refresh = new BlockedRefresh()) {
            Device updated = createMockDevice(DEVICE_ONLINE_ID, "192.168.3.8", 100, true, true, false, false);
            updated.setName("Updated during refresh");
            updated.setEnabled(false);
            updated.setPaused(true);
            deviceService.updateDevice(updated);

            refresh.finish();

            Assert.assertSame("Refresh replaced a successfully saved device", updated,
                    deviceService.getDeviceById(DEVICE_ONLINE_ID));
            Assert.assertSame(updated, deviceService.getDeviceByIp(IpAddress.parse("192.168.3.8")));
            Assert.assertTrue(deviceService.getDeviceById(DEVICE_ONLINE_ID).isPaused());
            Assert.assertFalse(deviceService.getDeviceById(DEVICE_ONLINE_ID).isEnabled());
            Mockito.verify(listener).onChange(updated);
            Mockito.verify(listener, Mockito.never()).onDelete(Mockito.any());
        }
    }

    @Test
    public void testOlderRefreshDoesNotDeleteNewlySavedDevice() throws Exception {
        try (BlockedRefresh refresh = new BlockedRefresh()) {
            Device created = createMockDevice("device:800000000004", "192.168.3.8", 100, false, true, false, false);
            deviceService.updateDevice(created);
            refresh.finish();

            Assert.assertTrue(deviceService.getDevices(false).contains(created));
            Assert.assertSame(created, deviceService.getDeviceByIp(IpAddress.parse("192.168.3.8")));
            Mockito.verify(dataSource, Mockito.never()).delete(created);
            Mockito.verify(listener, Mockito.never()).onDelete(Mockito.any());
        }
    }

    @Test
    public void testOlderRefreshDoesNotRestoreDeletedDevice() throws Exception {
        Mockito.doAnswer(invocation -> devices.remove(invocation.getArgument(0)))
                .when(dataSource).delete(Mockito.any(Device.class));
        try (BlockedRefresh refresh = new BlockedRefresh()) {
            Device deleted = deviceService.getDeviceById(DEVICE_OFFLINE_ID);
            Assert.assertSame(deleted, deviceService.delete(deleted));
            refresh.finish();

            Assert.assertFalse(deviceService.getDevices(false).contains(deleted));
            Mockito.verify(listener).onDelete(deleted);
            Mockito.verify(dataSource).delete(deleted);
        }
    }

    @Test
    public void testOlderRefreshDoesNotUndoIpConflictResolution() throws Exception {
        try (BlockedRefresh refresh = new BlockedRefresh()) {
            Device created = createMockDevice("device:800000000004", "192.168.1.5", 100, true, true, false, false);
            deviceService.updateDevice(created);
            refresh.finish();

            Device previousOwner = deviceService.getDeviceById(DEVICE_MULTIPLE_IPS_ID);
            Assert.assertEquals(Collections.singletonList(IpAddress.parse("192.168.1.6")), previousOwner.getIpAddresses());
            Assert.assertFalse(previousOwner.isIpAddressFixed());
            Assert.assertSame(created, deviceService.getDeviceByIp(IpAddress.parse("192.168.1.5")));
            Assert.assertSame(previousOwner, deviceService.getDeviceByIp(IpAddress.parse("192.168.1.6")));
            Mockito.verify(listener).onChange(previousOwner);
            Mockito.verify(listener).onChange(created);
        }
    }

    @Test
    public void testOlderRefreshDoesNotUndoLastSeenUpdate() throws Exception {
        try (BlockedRefresh refresh = new BlockedRefresh()) {
            Instant lastSeen = Instant.ofEpochSecond(1234);
            Device device = deviceService.getDeviceById(DEVICE_ONLINE_ID);
            deviceService.updateLastSeen(device, lastSeen);
            refresh.finish();

            Assert.assertEquals(lastSeen, deviceService.getDeviceById(DEVICE_ONLINE_ID).getLastSeen());
            Mockito.verify(dataSource).updateLastSeen(device);
        }
    }

    @Test
    public void testOlderRefreshDoesNotOverwriteNewerRefresh() throws Exception {
        try (BlockedRefresh refresh = new BlockedRefresh()) {
            devices.get(2).setName("Changed directly in data source");
            deviceService.refresh();
            refresh.finish();

            Assert.assertEquals("Changed directly in data source", deviceService.getDeviceById(DEVICE_ONLINE_ID).getName());
        }
    }

    @Test
    public void testChangeListenerCanWaitForRefreshOnAnotherThread() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Mockito.doAnswer(invocation -> {
                executor.submit(deviceService::refresh).get(5, TimeUnit.SECONDS);
                return null;
            }).when(listener).onChange(Mockito.any(Device.class));
            Device updated = createMockDevice(DEVICE_ONLINE_ID, "192.168.3.8", 100, true, true, false, false);
            deviceService.updateDevice(updated);

            Assert.assertEquals(updated.getIpAddresses(), deviceService.getDeviceById(DEVICE_ONLINE_ID).getIpAddresses());
            Mockito.verify(listener).onChange(updated);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testRefreshDeletionListenerCanWaitForRefreshOnAnotherThread() throws Exception {
        Device deleted = deviceService.getDeviceById(DEVICE_OFFLINE_ID);
        devices.remove(deleted);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Mockito.doAnswer(invocation -> {
                Assert.assertFalse(executor.submit(() -> deviceService.getDevices(true))
                        .get(5, TimeUnit.SECONDS).contains(deleted));
                return null;
            }).when(listener).onDelete(Mockito.any(Device.class));
            deviceService.refresh();

            Mockito.verify(listener).onDelete(deleted);
            Mockito.verify(dataSource).delete(deleted);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void testRefreshKeepsMissingOnlineDeviceWithoutDeletionCallback() {
        Device online = deviceService.getDeviceById(DEVICE_ONLINE_ID);
        devices.remove(online);
        deviceService.refresh();

        Assert.assertSame(online, deviceService.getDeviceById(DEVICE_ONLINE_ID));
        Mockito.verify(dataSource, Mockito.never()).delete(online);
        Mockito.verify(listener, Mockito.never()).onDelete(online);
    }

    @Test
    public void testDeleteUncachedDeviceDoesNotNotifyWithNull() {
        Device missing = createMockDevice("device:800000000004", "192.168.3.8", false, true, false, false);
        Assert.assertNull(deviceService.delete(missing));
        Mockito.verify(dataSource).delete(missing);
        Mockito.verify(listener, Mockito.never()).onDelete(Mockito.any());
    }

    @Test
    public void testUpdateOperatingUserNotExisting() {
        // ensure original device is in cache
        Device device = devices.get(0);
        Device retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // setup updated device referencing non-existing user
        device = createMockDevice(devices.get(0).getId(), "192.168.1.1", devices.get(0).getOperatingUser() + 1, true, true, false, false);

        // update device
        try {
            deviceService.updateDevice(device);
            Assert.fail();
        } catch (Exception e) {
            // Exception was expected
        }

        // check it has not been updated
        retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());
        Assert.assertNotEquals(device.getOperatingUser(), retrievedDevice.getOperatingUser());

        // check device has not been stored
        Mockito.verify(dataSource, Mockito.never()).save(device);
        // the only device stored is the conflicting device
        Mockito.verify(dataSource).save(devices.get(1));
        Mockito.verify(dataSource, Mockito.times(1)).save(Mockito.any(Device.class));
    }

    @Test
    public void testUpdateIpConflict() {

        // adding a new device with known IP
        Device device = createMockDevice("device:0000000000fe", "192.168.1.4", devices.get(0).getOperatingUser(), true, true, false, false);

        deviceService.updateDevice(device);

        // check it has been updated
        Device retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // check previous device with that IP has no IP address now
        Device oldDevice = devices.get(3);
        Assert.assertTrue(oldDevice.getIpAddresses().isEmpty());
        Assert.assertFalse(oldDevice.isIpAddressFixed());

        // check listener has been notified
        Mockito.verify(listener).onChange(retrievedDevice);
    }

    @Test
    public void testUpdateIpConflictMultipleIps() {
        // adding a new device with second ip of device 5
        Device device = createMockDevice("device:0000000000fe", "192.168.1.6", devices.get(0).getOperatingUser(), true, true, false, false);

        deviceService.updateDevice(device);

        // check it has been updated
        Device retrievedDevice = deviceService.getDeviceById(device.getId());
        Assert.assertNotNull(retrievedDevice);
        Assert.assertEquals(device.getId(), retrievedDevice.getId());
        Assert.assertEquals(device.getIpAddresses(), retrievedDevice.getIpAddresses());

        // check second ip has been removed from previous owner but address is still fixed because the primary address
        // is still there
        Device oldDevice = devices.get(4);
        Assert.assertEquals(1, oldDevice.getIpAddresses().size());
        Assert.assertTrue(oldDevice.getIpAddresses().contains(IpAddress.parse("192.168.1.5")));
        Assert.assertTrue(oldDevice.isIpAddressFixed());

        // check listener has been notified
        Mockito.verify(listener).onChange(retrievedDevice);
    }

    @Test
    public void testCacheRetrievalWithoutRefresh() {
        // ensure all devices are cached
        deviceService.refresh();

        // create additional device
        Device newDevice = createMockDevice("device:800000000004", "192.168.1.3", true, true, false, false);
        devices.add(newDevice);

        // check only previously cached devices are returned
        Collection<Device> retrievedDevices = deviceService.getDevices(false);
        Assert.assertEquals(5, retrievedDevices.size());
        Assert.assertFalse(retrievedDevices.contains(newDevice));
    }

    @Test
    public void testCacheRetrievalWithRefresh() {
        // ensure all devices are cached
        deviceService.refresh();

        // create additional device
        Device newDevice = createMockDevice("device:800000000004", "192.168.1.3", true, true, false, false);
        devices.add(newDevice);

        // check all devices are returned
        Collection<Device> retrievedDevices = deviceService.getDevices(true);
        Assert.assertEquals(6, retrievedDevices.size());
        Assert.assertTrue(retrievedDevices.contains(newDevice));
    }

    @Test
    public void testResetDevice() {
        DeviceChangeListener listener = Mockito.mock(DeviceChangeListener.class);

        Mockito.doAnswer(m -> {
            Device device = m.getArgument(0);
            device.setAssignedUser(nextUserId);
            device.setDefaultSystemUser(nextUserId);
            device.setOperatingUser(nextUserId);
            return null;
        }).when(listener).onReset(Mockito.any(Device.class));

        deviceService.addListener(listener);

        deviceService.resetDevice(DEVICE_ONLINE_ID);

        Device device = devices.get(2);

        Mockito.verify(listener).onReset(Mockito.eq(device));
        Assert.assertEquals(nextUserId, device.getAssignedUser());
        Assert.assertEquals(nextUserId, device.getDefaultSystemUser());
        Assert.assertEquals(nextUserId, device.getOperatingUser());
    }

    @Test
    public void testSetOnlineStatus() {
        Mockito.when(clock.instant()).thenReturn(Instant.ofEpochMilli(92000L));
        Device device = createMockDevice("device:111111111111", "192.168.0.2", false, false, false, false);
        deviceService.setOnlineStatus(device);
        Assert.assertFalse(device.isOnline());

        // Not online any more
        device.setLastSeen(Instant.ofEpochMilli(1234L));
        deviceService.setOnlineStatus(device);
        Assert.assertFalse(device.isOnline());

        // Still online
        device.setLastSeen(Instant.ofEpochMilli(3000L));
        deviceService.setOnlineStatus(device);
        Assert.assertTrue(device.isOnline());
    }

    private Device createMockDevice(String id, String ip, boolean online, boolean ipAddressFixed, boolean isGateway, boolean isEblocker) {
        return createMockDevice(id, new String[]{ ip }, online, ipAddressFixed, isGateway, isEblocker);
    }

    private Device createMockDevice(String id, String[] ips, boolean online, boolean ipAddressFixed, boolean isGateway, boolean isEblocker) {
        Device device = new Device();
        device.setId(id);
        if (ips != null) {
            device.setIpAddresses(Arrays.stream(ips).map(IpAddress::parse).collect(Collectors.toList()));
        }
        device.setOnline(online);
        device.setIpAddressFixed(ipAddressFixed);
        device.setIsGateway(isGateway);
        device.setIsEblocker(isEblocker);
        return device;
    }

    private Device createMockDevice(String id, String ip, int operatingUser, boolean online, boolean ipAddressFixed, boolean isGateway, boolean isEblocker) {
        Device device = createMockDevice(id, ip, online, ipAddressFixed, isGateway, isEblocker);
        device.setOperatingUser(operatingUser);
        device.setAssignedUser(operatingUser);
        return device;
    }

    private Set<Device> copyDevices(Collection<Device> devices) {
        return devices.stream().map(this::copyDevice).collect(Collectors.toSet());
    }

    private Device copyDevice(Device device) {
        Device copy = new Device();
        copy.setId(device.getId());
        copy.setIpAddresses(device.getIpAddresses());
        copy.setAssignedUser(device.getAssignedUser());
        copy.setOperatingUser(device.getOperatingUser());
        copy.setOnline(device.isOnline());
        copy.setIpAddressFixed(device.isIpAddressFixed());
        copy.setIsGateway(device.isGateway());
        copy.setIsEblocker(device.isEblocker());
        copy.setName(device.getName());
        copy.setEnabled(device.isEnabled());
        copy.setPaused(device.isPaused());
        copy.setLastSeen(device.getLastSeen());
        return copy;
    }

    /** Allows a newer operation to complete before the first refresh returns its old snapshot. */
    private final class BlockedRefresh implements AutoCloseable {
        private final CountDownLatch releaseSnapshot = new CountDownLatch(1);
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final Future<?> refresh;

        private BlockedRefresh() throws InterruptedException {
            CountDownLatch snapshotRead = new CountDownLatch(1);
            AtomicBoolean firstRead = new AtomicBoolean(true);
            Mockito.when(dataSource.getDevices()).thenAnswer(invocation -> {
                Set<Device> snapshot = copyDevices(devices);
                if (firstRead.compareAndSet(true, false)) {
                    snapshotRead.countDown();
                    Assert.assertTrue("Timed out releasing the old snapshot", releaseSnapshot.await(5, TimeUnit.SECONDS));
                }
                return snapshot;
            });
            refresh = executor.submit(deviceService::refresh);
            if (!snapshotRead.await(5, TimeUnit.SECONDS)) {
                close();
                Assert.fail("Refresh did not read its snapshot");
            }
        }

        private void finish() throws Exception {
            releaseSnapshot.countDown();
            refresh.get(5, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            releaseSnapshot.countDown();
            executor.shutdownNow();
        }
    }
}
