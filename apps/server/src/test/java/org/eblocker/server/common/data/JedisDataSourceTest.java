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
package org.eblocker.server.common.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

public class JedisDataSourceTest {

    private JedisPool jedisPool;
    private Jedis jedis;
    private ObjectMapper objectMapper;

    private JedisDataSource dataSource;

    @BeforeEach
    public void setup() {
        jedis = Mockito.mock(Jedis.class);
        jedisPool = Mockito.mock(JedisPool.class);
        Mockito.when(jedisPool.getResource()).thenReturn(jedis);

        objectMapper = new ObjectMapper();

        dataSource = new JedisDataSource(jedisPool, objectMapper);
    }

    @Test
    public void testSaveSingularEntity() {
        dataSource.save(new Entity());
        Mockito.verify(jedis).set(Mockito.eq("Entity"), Mockito.anyString());
    }

    @Test
    public void testSaveEntity() {
        dataSource.save(new Entity(), 5);
        Mockito.verify(jedis).set(Mockito.eq("Entity:5"), Mockito.anyString());
    }

    @Test
    public void testNextId() {
        Mockito.when(jedis.incr("Entity:sequence")).thenReturn(5L);
        assertEquals(5L, dataSource.nextId(Entity.class));
    }

    public static class Entity {
        private int id;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }
    }

    @Test
    public void testDeviceWithoutPauseFlagNotPaused() {
        String deviceId = "device:112233445566";

        // Need to set at least one parameter
        Map<String, String> map = new HashMap<>();
        map.put("TEST-KEY", "TEST-VALUE");

        // Needed to set the gateway
        Mockito.when(jedis.hgetAll(Mockito.eq(deviceId))).thenReturn(map);

        Device device = dataSource.getDevice(deviceId);

        assertFalse(device.isPaused());
    }

    @Test
    public void testDeviceSave() {
        Device device = new Device();
        device.setId("device:112233445566");
        device.setIpAddresses(Arrays.asList(IpAddress.parse("10.10.10.10"), IpAddress.parse("10.10.10.11")));

        dataSource.save(device);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(jedis).hmset(Mockito.eq("device:112233445566"), captor.capture());

        Map<String, String> map = captor.getValue();
        assertEquals("10.10.10.10,10.10.10.11", map.get("ipAddress"));
    }

    @Test
    public void testDeviceLoad() {
        String deviceId = "device:112233445566";

        Map<String, String> map = new HashMap<>();
        map.put("ipAddress", "10.10.10.10,10.10.10.11");
        Mockito.when(jedis.hgetAll(deviceId)).thenReturn(map);

        Device device = dataSource.getDevice(deviceId);
        assertNotNull(device);
        assertNotNull(device.getIpAddresses());
        assertEquals(2, device.getIpAddresses().size());
        assertTrue(device.getIpAddresses().contains(IpAddress.parse("10.10.10.10")));
        assertTrue(device.getIpAddresses().contains(IpAddress.parse("10.10.10.11")));
    }

    @Test
    public void testDeviceGateway() {
        String deviceId = "device:fb1122334455";

        Map<String, String> map = new HashMap<>();
        map.put("ipAddress", "192.168.1.1,fe80::2665:11ff:fe58:d32b");
        Mockito.when(jedis.hgetAll(deviceId)).thenReturn(map);
        Mockito.when(jedis.get("gateway")).thenReturn("192.168.1.1");

        Device device = dataSource.getDevice(deviceId);
        assertTrue(device.isGateway());
    }

    @Test
    public void testGetDeviceScanningInterval() {
        // good value:
        Mockito.when(jedis.get(JedisDataSource.KEY_DEVICE_SCANNING_INTERVAL)).thenReturn("42");
        assertEquals(Long.valueOf(42), dataSource.getDeviceScanningInterval());

        // empty value:
        Mockito.when(jedis.get(JedisDataSource.KEY_DEVICE_SCANNING_INTERVAL)).thenReturn(null);
        assertNull(dataSource.getDeviceScanningInterval());
    }

    @Test
    public void testGetDeviceScanningIntervalBadValue() {
        // bad value:
        Mockito.when(jedis.get(JedisDataSource.KEY_DEVICE_SCANNING_INTERVAL)).thenReturn("not an integer");
        assertThrows(NumberFormatException.class, () -> {
            dataSource.getDeviceScanningInterval();
        });
    }

    @Test
    public void testSetDeviceScanningInterval() {
        dataSource.setDeviceScanningInterval(42L);
        Mockito.verify(jedis).set(JedisDataSource.KEY_DEVICE_SCANNING_INTERVAL, "42");
    }

    @Test
    public void testUpdateLastSeen() {
        String deviceId = "device:abcdef123456";
        Device device = new Device();
        device.setId(deviceId);
        device.setLastSeen(Instant.ofEpochMilli(1234));
        dataSource.updateLastSeen(device);
        Mockito.verify(jedis).hset(deviceId, JedisDataSource.KEY_DEVICE_LAST_SEEN, "1234");
    }

    /**
     * Test a race condition where a device is deleted between the calls to getDeviceIds() and getDevice().
     * (See issue #420).
     */
    @Test
    public void testGetDevicesRaceCondition() {
        Mockito.when(jedis.keys("device:*")).thenReturn(Set.of("device:111111111111", "device:222222222222"));
        // device 2 was deleted
        Map<String, String> device1 = Map.of("ipAddress", "192.168.23.42");
        Mockito.when(jedis.hgetAll("device:111111111111")).thenReturn(device1);
        Set<Device> devices = dataSource.getDevices();
        assertFalse(devices.contains(null));
        assertEquals(1, devices.size());
    }

    @Test
    public void testStoredDeviceHashKeepsAllSettings() {
        Map<String, String> stored = storedDeviceHash();
        stored.put("lastSeen", "1700000000123");
        Mockito.when(jedis.hgetAll("device:112233445566")).thenReturn(stored);

        Device device = dataSource.getDevice("device:112233445566");

        assertEquals("Bedroom", device.getName());
        assertEquals(Arrays.asList(IpAddress.parse("10.0.0.2"), IpAddress.parse("fe80::2")), device.getIpAddresses());
        assertFalse(device.isEnabled());
        assertTrue(device.isPaused());
        assertFalse(device.isMessageShowInfo());
        assertFalse(device.isMessageShowAlert());
        assertFalse(device.isShowPauseDialog());
        assertFalse(device.isShowPauseDialogDoNotShowAgain());
        assertFalse(device.isShowDnsFilterInfoDialog());
        assertFalse(device.isShowWelcomePage());
        assertFalse(device.isShowBookmarkDialog());
        assertTrue(device.isControlBarAutoMode());
        assertFalse(device.isEblockerMobileEnabled());
        assertTrue(device.isMobilePrivateNetworkAccess());
        assertTrue(device.isUseAnonymizationService());
        assertTrue(device.isRoutedThroughTor());
        assertEquals(7, device.getUseVPNProfileID());
        assertFalse(device.isMalwareFilterEnabled());
        assertTrue(device.isSslEnabled());
        assertFalse(device.isSslRecordErrorsEnabled());
        assertTrue(device.isDomainRecordingEnabled());
        assertTrue(device.hasRootCAInstalled());
        assertEquals(DisplayIconMode.OFF, device.getIconMode());
        assertEquals(Device.DisplayIconPosition.LEFT, device.getIconPosition());
        assertFalse(device.getAreDeviceMessagesSettingsDefault());
        assertFalse(device.isIpAddressFixed());
        assertEquals(Ip4Address.parse("10.0.0.9"), device.getStaticIpAddress());
        assertEquals(Ip6Address.parse("fd00::9"), device.getStaticIpV6Address());
        assertEquals(3, device.getAssignedUser());
        assertEquals(4, device.getOperatingUser());
        assertEquals(5, device.getDefaultSystemUser());
        assertTrue(device.isVpnClient());
        assertEquals(FilterMode.PLUG_AND_PLAY, device.getFilterMode());
        assertFalse(device.isFilterAdsEnabled());
        assertFalse(device.isFilterTrackersEnabled());
        assertEquals(Instant.ofEpochMilli(1700000000123L), device.getLastSeen());
    }

    @Test
    public void testSaveKeepsLegacyHashFormatAndDoesNotOverwriteLastSeen() {
        Device device = new Device();
        device.setId("device:112233445566");
        device.setName("Bedroom");
        device.setIpAddresses(Arrays.asList(IpAddress.parse("10.0.0.2"), IpAddress.parse("fe80::2")));
        device.setEnabled(false);
        device.setPaused(true);
        device.setMessageShowInfo(false);
        device.setMessageShowAlert(false);
        device.setShowPauseDialog(false);
        device.setShowPauseDialogDoNotShowAgain(false);
        device.setShowDnsFilterInfoDialog(false);
        device.setShowWelcomePage(false);
        device.setShowBookmarkDialog(false);
        device.setControlBarAutoMode(true);
        device.setMobileState(false);
        device.setMobilePrivateNetworkAccess(true);
        device.setUseAnonymizationService(true);
        device.setRouteThroughTor(true);
        device.setUseVPNProfileID(7);
        device.setMalwareFilterEnabled(false);
        device.setSslEnabled(true);
        device.setSslRecordErrorsEnabled(false);
        device.setDomainRecordingEnabled(true);
        device.setHasRootCAInstalled(true);
        device.setIconMode(DisplayIconMode.OFF);
        device.setIconPosition(Device.DisplayIconPosition.LEFT);
        device.setAreDeviceMessagesSettingsDefault(false);
        device.setIpAddressFixed(false);
        device.setStaticIpAddress(Ip4Address.parse("10.0.0.9"));
        device.setStaticIpV6Address(Ip6Address.parse("fd00::9"));
        device.setAssignedUser(3);
        device.setOperatingUser(4);
        device.setDefaultSystemUser(5);
        device.setIsVpnClient(true);
        device.setFilterMode(FilterMode.PLUG_AND_PLAY);
        device.setFilterAdsEnabled(false);
        device.setFilterTrackersEnabled(false);
        device.setLastSeen(Instant.ofEpochMilli(1700000000999L));

        dataSource.save(device);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(jedis).hmset(Mockito.eq(device.getId()), captor.capture());
        assertEquals(storedDeviceHash(), captor.getValue());
        Mockito.verify(jedis, Mockito.never()).hset(Mockito.anyString(), Mockito.eq("lastSeen"), Mockito.anyString());
        Mockito.verify(jedis, Mockito.never()).del(device.getId());
    }

    @Test
    public void testClearingOptionalDeviceFieldsDeletesTheirStoredValues() {
        Device device = new Device();
        device.setId("device:112233445566");
        dataSource.save(device);

        Mockito.verify(jedis).hdel(device.getId(), "ipAddress");
        Mockito.verify(jedis).hdel(device.getId(), "vpn_profile_id");
        Mockito.verify(jedis).hdel(device.getId(), "dhcp_static_ip");
        Mockito.verify(jedis).hdel(device.getId(), "dhcp_static_ipv6");
        // A missing name and the discovery timestamp intentionally retain the previous values.
        Mockito.verify(jedis, Mockito.never()).hdel(device.getId(), "name");
        Mockito.verify(jedis, Mockito.never()).hdel(device.getId(), "lastSeen");
    }

    @Test
    public void testLegacyDeviceDefaultsRemainIndependentOfConstructorDefaults() {
        String deviceId = "device:112233445566";
        Mockito.when(jedis.hgetAll(deviceId)).thenReturn(Map.of("name", "Legacy"));
        Device device = dataSource.getDevice(deviceId);

        assertTrue(device.isEnabled());
        assertTrue(device.isMessageShowInfo());
        assertTrue(device.isMessageShowAlert());
        assertTrue(device.isShowPauseDialog());
        assertTrue(device.isShowPauseDialogDoNotShowAgain());
        assertTrue(device.isShowDnsFilterInfoDialog());
        assertTrue(device.isShowWelcomePage());
        assertTrue(device.isShowBookmarkDialog());
        assertFalse(device.isControlBarAutoMode());
        assertTrue(device.isEblockerMobileEnabled());
        assertFalse(device.isMobilePrivateNetworkAccess());
        assertTrue(device.isMalwareFilterEnabled());
        assertFalse(device.isSslEnabled());
        assertTrue(device.isSslRecordErrorsEnabled());
        assertFalse(device.isDomainRecordingEnabled());
        assertFalse(device.isUseAnonymizationService());
        assertTrue(device.isIpAddressFixed());
        assertEquals(FilterMode.AUTOMATIC, device.getFilterMode());
        assertTrue(device.isFilterAdsEnabled());
        assertTrue(device.isFilterTrackersEnabled());
    }

    @Test
    public void testLegacyAnonymizationAndDhcpFallbacks() {
        String deviceId = "device:112233445566";
        Map<String, String> stored = new HashMap<>(Map.of("useTor", "true", "dhcp_ip_fixed_by_default", "false"));
        Mockito.when(jedis.hgetAll(deviceId)).thenReturn(stored);
        assertTrue(dataSource.getDevice(deviceId).isUseAnonymizationService());
        assertFalse(dataSource.getDevice(deviceId).isIpAddressFixed());

        stored.put("useTor", "false");
        stored.put("vpn_profile_id", "7");
        assertTrue(dataSource.getDevice(deviceId).isUseAnonymizationService());

        stored.put("useAnonymizationService", "false");
        stored.put("dhcp_fixed_ip", "true");
        assertFalse(dataSource.getDevice(deviceId).isUseAnonymizationService());
        assertTrue(dataSource.getDevice(deviceId).isIpAddressFixed());
    }

    @Test
    public void testInvalidStaticAddressesDoNotDiscardOtherDeviceSettings() {
        String deviceId = "device:112233445566";
        Mockito.when(jedis.hgetAll(deviceId)).thenReturn(Map.of(
                "name", "Legacy", "dhcp_static_ip", "broken", "dhcp_static_ipv6", "broken"));

        Device device = dataSource.getDevice(deviceId);

        assertEquals("Legacy", device.getName());
        assertNull(device.getStaticIpAddress());
        assertNull(device.getStaticIpV6Address());
    }

    private static Map<String, String> storedDeviceHash() {
        return new HashMap<>(Map.ofEntries(
                Map.entry("name", "Bedroom"),
                Map.entry("ipAddress", "10.0.0.2,fe80::2"),
                Map.entry("enabled", "false"), Map.entry("paused", "true"),
                Map.entry("messageShowInfo", "false"), Map.entry("messageShowAlert", "false"),
                Map.entry("showPauseDialog", "false"), Map.entry("showPauseDialogDoNotShowAgain", "false"),
                Map.entry("showDnsFilterInfoDialog", "false"), Map.entry("showWelcomePage", "false"),
                Map.entry("showBookmarkDialog", "false"), Map.entry("isControlBarAutoMode", "true"),
                Map.entry("isMobileEnabled", "false"), Map.entry("mobilePrivateNetworkAccess", "true"),
                Map.entry("useAnonymizationService", "true"), Map.entry("useTor", "true"),
                Map.entry("vpn_profile_id", "7"), Map.entry("malware_filter_enabled", "false"),
                Map.entry("ssl_enabled", "true"), Map.entry("ssl_record_errors", "false"),
                Map.entry("domain_recording_enabled", "true"), Map.entry("root_ca_installed", "true"),
                Map.entry("display_icon_mode", "OFF"), Map.entry("display_icon_position", "LEFT"),
                Map.entry("showWarnings", "false"), Map.entry("dhcp_fixed_ip", "false"),
                Map.entry("dhcp_static_ip", "10.0.0.9"), Map.entry("dhcp_static_ipv6", "fd00::9"),
                Map.entry("parentalControlUserId", "3"), Map.entry("parentalControlOperatingUserId", "4"),
                Map.entry("defaultSystemUserId", "5"), Map.entry("IsOpenVpnClient", "true"),
                Map.entry("filter_mode", "PLUG_AND_PLAY"), Map.entry("filter_plug_and_play_ads_enabled", "false"),
                Map.entry("filter_plug_and_play_trackers_enabled", "false")));
    }
}
