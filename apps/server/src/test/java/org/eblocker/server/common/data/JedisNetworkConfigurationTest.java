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
import org.eblocker.server.common.data.openvpn.ExternalAddressType;
import org.eblocker.server.common.data.openvpn.PortForwardingMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JedisNetworkConfigurationTest {
    private Jedis jedis;
    private JedisDataSource dataSource;

    @BeforeEach
    void setup() {
        jedis = mock(Jedis.class);
        JedisPool pool = mock(JedisPool.class);
        when(pool.getResource()).thenReturn(jedis);
        dataSource = new JedisDataSource(pool, new ObjectMapper());
    }

    @Test
    void missingConfigurationRetainsInstallationDefaults() {
        assertNull(dataSource.getGateway());
        assertNull(dataSource.getResolvedDnsGateway());
        assertFalse(dataSource.isExpertMode());
        assertEquals(NetworkStateId.PLUG_AND_PLAY, dataSource.getCurrentNetworkState());
        assertTrue(dataSource.getDhcpRange().isEmpty());
        assertNull(dataSource.getDhcpLeaseTime());
        assertTrue(dataSource.areRouterAdvertisementsEnabled());
        assertFalse(dataSource.arePrivacyExtensionsEnabled());
        assertFalse(dataSource.getOpenVpnServerState());
        assertTrue(dataSource.getOpenVpnServerFirstRun());
        assertNull(dataSource.getOpenVpnServerHost());
        assertNull(dataSource.getOpenVpnMappedPort());
        assertEquals(PortForwardingMode.AUTO, dataSource.getOpenVpnPortForwardingMode());
        assertNull(dataSource.getOpenVpnExternalAddressType());
        assertNull(dataSource.getCurrentTorExitNodes());
    }

    @Test
    void readsExistingNetworkAndTunnelValues() {
        Map<String, String> stored = Map.ofEntries(
                Map.entry("gateway", "10.0.0.1"), Map.entry("resolved_dns_gateway", "fd00::53"),
                Map.entry("network_is_expert_mode", "true"), Map.entry("networkState", "LOCAL_DHCP"),
                Map.entry("dhcpLeaseTime", "7200"), Map.entry("router_advertisements_enabled", "false"),
                Map.entry("privacy_extensions_enabled", "true"), Map.entry("OpenVpnServerEnabled", "true"),
                Map.entry("OpenVpnFirstRun", "false"), Map.entry("OpenVpnHost", "vpn.example.test"),
                Map.entry("OpenVpnMappedPort", "1194"), Map.entry("OpenVpnPortForwardingMode", "MANUAL"),
                Map.entry("OpenVpnExternalAddressType", "DYN_DNS"),
                Map.entry("torCurrentExitNodes", "[\"de\",\"at\"]"));
        when(jedis.get(anyString())).thenAnswer(call -> stored.get(call.getArgument(0)));
        when(jedis.hget("dhcpRange", "first")).thenReturn("10.0.0.10");
        when(jedis.hget("dhcpRange", "last")).thenReturn("10.0.0.99");

        assertEquals("10.0.0.1", dataSource.getGateway());
        assertEquals("fd00::53", dataSource.getResolvedDnsGateway());
        assertTrue(dataSource.isExpertMode());
        assertEquals(NetworkStateId.LOCAL_DHCP, dataSource.getCurrentNetworkState());
        assertEquals("10.0.0.10", dataSource.getDhcpRange().getFirstIpAddress());
        assertEquals("10.0.0.99", dataSource.getDhcpRange().getLastIpAddress());
        assertEquals(7200, dataSource.getDhcpLeaseTime());
        assertFalse(dataSource.areRouterAdvertisementsEnabled());
        assertTrue(dataSource.arePrivacyExtensionsEnabled());
        assertTrue(dataSource.getOpenVpnServerState());
        assertFalse(dataSource.getOpenVpnServerFirstRun());
        assertEquals("vpn.example.test", dataSource.getOpenVpnServerHost());
        assertEquals(1194, dataSource.getOpenVpnMappedPort());
        assertEquals(PortForwardingMode.MANUAL, dataSource.getOpenVpnPortForwardingMode());
        assertEquals(ExternalAddressType.DYN_DNS, dataSource.getOpenVpnExternalAddressType());
        assertEquals(Set.of("de", "at"), dataSource.getCurrentTorExitNodes());
    }

    @Test
    void writesTheExistingRedisKeysAndDataFormats() throws Exception {
        dataSource.setGateway("10.0.0.1");
        dataSource.setResolvedDnsGateway("fd00::53");
        dataSource.setIsExpertMode(true);
        dataSource.setCurrentNetworkState(NetworkStateId.EXTERNAL_DHCP);
        dataSource.setDhcpRange(new DhcpRange("10.0.0.10", "10.0.0.99"));
        dataSource.setDhcpLeaseTime(3600);
        dataSource.setRouterAdvertisementsEnabled(false);
        dataSource.setPrivacyExtensionsEnabled(true);
        dataSource.setOpenVpnServerState(true);
        dataSource.setOpenVpnServerFirstRun(false);
        dataSource.setOpenVpnServerHost("vpn.example.test");
        dataSource.setOpenVpnMappedPort(1194);
        dataSource.setOpenVpnPortForwardingMode(PortForwardingMode.MANUAL);
        dataSource.setOpenVpnExternalAddressType(ExternalAddressType.FIXED_IP);
        dataSource.saveCurrentTorExitNodes(Set.of("de", "at"));

        verify(jedis).set("gateway", "10.0.0.1");
        verify(jedis).set("resolved_dns_gateway", "fd00::53");
        verify(jedis).set("network_is_expert_mode", "true");
        verify(jedis).set("networkState", "EXTERNAL_DHCP");
        verify(jedis).hset("dhcpRange", "first", "10.0.0.10");
        verify(jedis).hset("dhcpRange", "last", "10.0.0.99");
        verify(jedis).set("dhcpLeaseTime", "3600");
        verify(jedis).set("router_advertisements_enabled", "false");
        verify(jedis).set("privacy_extensions_enabled", "true");
        verify(jedis).set("OpenVpnServerEnabled", "true");
        verify(jedis).set("OpenVpnFirstRun", "false");
        verify(jedis).set("OpenVpnHost", "vpn.example.test");
        verify(jedis).set("OpenVpnMappedPort", "1194");
        verify(jedis).set("OpenVpnPortForwardingMode", "MANUAL");
        verify(jedis).set("OpenVpnExternalAddressType", "FIXED_IP");
        ArgumentCaptor<String> countries = ArgumentCaptor.forClass(String.class);
        verify(jedis).set(eq("torCurrentExitNodes"), countries.capture());
        assertEquals(Set.of("de", "at"), new ObjectMapper().readValue(countries.getValue(), Set.class));
    }

    @Test
    void nullableUpdatesKeepStoredValuesAndRangeCanBeClearedExplicitly() {
        dataSource.setDhcpLeaseTime(null);
        dataSource.setOpenVpnMappedPort(null);
        dataSource.setOpenVpnPortForwardingMode(null);
        dataSource.setOpenVpnExternalAddressType(null);
        verifyNoInteractions(jedis);

        dataSource.setDhcpRange(new DhcpRange(null, "10.0.0.99"));
        verify(jedis).hset("dhcpRange", "last", "10.0.0.99");
        verify(jedis, never()).hset(eq("dhcpRange"), eq("first"), anyString());
        verify(jedis, never()).del("dhcpRange");
        dataSource.clearDhcpRange();
        verify(jedis).del("dhcpRange");
    }

    @Test
    void malformedStoredNumbersAndEnumsRemainVisibleToCallers() {
        when(jedis.get(anyString())).thenReturn("invalid");
        assertThrows(NumberFormatException.class, dataSource::getDhcpLeaseTime);
        assertThrows(NumberFormatException.class, dataSource::getOpenVpnMappedPort);
        assertThrows(IllegalArgumentException.class, dataSource::getCurrentNetworkState);
        assertThrows(IllegalArgumentException.class, dataSource::getOpenVpnPortForwardingMode);
        assertThrows(IllegalArgumentException.class, dataSource::getOpenVpnExternalAddressType);
        // Unlike enum and number fields, malformed JSON has historically fallen back to null.
        assertNull(dataSource.getCurrentTorExitNodes());
    }

    @Test
    void legacyBooleanParsingRetainsItsFieldSpecificCaseRules() {
        when(jedis.get(anyString())).thenReturn("TRUE");
        assertFalse(dataSource.isExpertMode());
        assertFalse(dataSource.getOpenVpnServerState());
        assertFalse(dataSource.getOpenVpnServerFirstRun());
        assertTrue(dataSource.areRouterAdvertisementsEnabled());
        assertTrue(dataSource.arePrivacyExtensionsEnabled());
    }
}
