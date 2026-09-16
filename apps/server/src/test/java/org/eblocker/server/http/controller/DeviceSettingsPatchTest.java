package org.eblocker.server.http.controller;

import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.IpAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restexpress.exception.BadRequestException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DeviceSettingsPatchTest {
    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "true", "{}", "{", "{} {}",
            "{\"name\":null}", "{\"name\":42}", "{\"name\":\"   \"}", "{\"name\":\"bad\\nname\"}",
            "{\"enabled\":null}", "{\"enabled\":\"false\"}", "{\"enabled\":0}",
            "{\"filterAdsEnabled\":[]}", "{\"sslEnabled\":{}}", "{\"name\":\"Desk\",\"id\":\"other\"}",
            "{\"enabled\":true,\"enabled\":false}"})
    void rejectsMalformedOrAmbiguousPatches(String json) {
        assertThrows(BadRequestException.class, () -> DeviceSettingsPatch.parse(json));
    }

    @Test
    void validatesNameLengthAfterTrimming() {
        assertEquals("a".repeat(50), DeviceSettingsPatch.parse("{\"name\":\" " + "a".repeat(50) + " \"}")
                .applyTo(new Device()).getName());
        assertThrows(BadRequestException.class,
                () -> DeviceSettingsPatch.parse("{\"name\":\"" + "a".repeat(51) + "\"}"));
    }

    @Test
    void preservesUnspecifiedFieldsWithoutMutatingTheCachedDevice() {
        Device original = new Device();
        original.setId("device:001122334455");
        original.setName("Before");
        original.setIpAddresses(List.of(IpAddress.parse("192.0.2.2"), IpAddress.parse("2001:db8::2")));
        original.setAssignedUser(18);
        original.setOperatingUser(19);
        original.setDefaultSystemUser(20);
        original.setUseVPNProfileID(21);
        original.setUseAnonymizationService(true);
        original.setLastSeen(Instant.parse("2026-09-16T00:01:02Z"));
        original.setSslEnabled(true);
        original.setFilterAdsEnabled(false);
        original.setMobilePrivateNetworkAccess(true);
        original.markAsCurrentDevice();

        Device updated = DeviceSettingsPatch.parse("{\"name\":\" After \"}").applyTo(original);
        assertNotSame(original, updated);
        assertEquals("Before", original.getName());
        assertEquals("After", updated.getName());
        assertEquals(original.getId(), updated.getId());
        assertEquals(original.getIpAddresses(), updated.getIpAddresses());
        assertEquals(18, updated.getAssignedUser());
        assertEquals(19, updated.getOperatingUser());
        assertEquals(20, updated.getDefaultSystemUser());
        assertEquals(21, updated.getUseVPNProfileID());
        assertEquals(original.getLastSeen(), updated.getLastSeen());
        assertTrue(updated.isUseAnonymizationService());
        assertTrue(updated.isSslEnabled());
        assertFalse(updated.isFilterAdsEnabled());
        assertTrue(updated.isMobilePrivateNetworkAccess());
        assertTrue(updated.isCurrentDevice());
    }

    @Test
    void acceptsExplicitFalseValuesForAllEditableBooleans() {
        Device original = new Device();
        original.setSslEnabled(true);
        Device updated = DeviceSettingsPatch.parse("{\"enabled\":false,\"filterAdsEnabled\":false,"
                + "\"filterTrackersEnabled\":false,\"malwareFilterEnabled\":false,\"sslEnabled\":false}")
                .applyTo(original);
        assertFalse(updated.isEnabled());
        assertFalse(updated.isFilterAdsEnabled());
        assertFalse(updated.isFilterTrackersEnabled());
        assertFalse(updated.isMalwareFilterEnabled());
        assertFalse(updated.isSslEnabled());
        assertTrue(original.isEnabled());
        assertTrue(original.isSslEnabled());
    }
}
