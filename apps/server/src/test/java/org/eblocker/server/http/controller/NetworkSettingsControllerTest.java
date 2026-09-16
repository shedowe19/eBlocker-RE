/* Copyright 2026 eBlocker contributors. Licensed under EUPL-1.2. */
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eblocker.server.common.ObjectMapperProvider;
import org.eblocker.server.common.data.NetworkConfiguration;
import org.eblocker.server.common.data.NetworkIp6Configuration;
import org.eblocker.server.common.data.dns.DnsResolvers;
import org.eblocker.server.common.network.NetworkServices;
import org.eblocker.server.common.network.NetworkStateMachine;
import org.eblocker.server.common.network.unix.EblockerDnsServer;
import org.eblocker.server.http.service.DnsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.restexpress.Request;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkSettingsControllerTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new org.eblocker.server.common.data.IpAddressModule());
    private final NetworkServices network = mock(NetworkServices.class);
    private final NetworkStateMachine state = mock(NetworkStateMachine.class);
    private final EblockerDnsServer dns = mock(EblockerDnsServer.class);
    private final DnsService service = mock(DnsService.class);
    private final NetworkSettingsController controller = new NetworkSettingsController(json, network, state, dns, service);

    @Test void rejectsStaleIpv4IncludingChangedDhcpLeaseBeforeSideEffects() throws Exception {
        NetworkConfiguration old = network();
        NetworkConfiguration current = network(); current.setDhcpLeaseTime(1800);
        when(state.getConfigurationSnapshot()).thenReturn(current);
        assertThrows(ConflictException.class, () -> controller.patchNetwork(change(old, old), null));
        verify(state, never()).updateConfiguration(any());
    }

    @Test void ipv4CompareAndMutationShareStateMonitorAndPreserveObservedFields() throws Exception {
        NetworkConfiguration current = network(); current.setVpnIpAddress("10.2.0.1");
        NetworkConfiguration desired = network(); desired.setDhcpLeaseTime(1800); desired.setVpnIpAddress("10.99.0.1");
        java.util.concurrent.atomic.AtomicReference<NetworkConfiguration> snapshot = new java.util.concurrent.atomic.AtomicReference<>(current);
        when(state.getConfigurationSnapshot()).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(state)); return snapshot.get();
        });
        when(state.updateConfiguration(any())).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(state));
            NetworkConfiguration after = new NetworkConfiguration(current);
            after.setRevision("rev-2"); after.setRebootNecessary(true); after.setPendingReboot(true);
            after.setPendingConfiguration(invocation.getArgument(0)); snapshot.set(after);
            return true;
        });
        NetworkConfiguration result = controller.patchNetwork(change(current, desired), null);
        assertEquals(600, result.getDhcpLeaseTime());
        assertEquals(1800, result.getPendingConfiguration().getDhcpLeaseTime());
        assertEquals("rev-2", result.getRevision());
        assertEquals("10.2.0.1", result.getVpnIpAddress());
        assertTrue(result.isRebootNecessary());
        assertEquals(600, current.getDhcpLeaseTime());
    }

    @Test void ipv6IgnoresChangingObservedAddressesButNotSettingConflicts() throws Exception {
        NetworkIp6Configuration current = new NetworkIp6Configuration();
        when(network.getNetworkIp6Configuration()).thenAnswer(invocation -> {
            assertTrue(Thread.holdsLock(network)); return current;
        });
        doAnswer(invocation -> { assertTrue(Thread.holdsLock(network)); return null; })
                .when(network).updateNetworkIp6Configuration(any());
        var desired = Map.of("routerAdvertisementsEnabled", true, "privacyExtensionsEnabled", true);
        assertTrue(controller.patchIp6(change(current, desired), null).isPrivacyExtensionsEnabled());
        current.setPrivacyExtensionsEnabled(true);
        assertThrows(ConflictException.class, () -> controller.patchIp6(change(new NetworkIp6Configuration(), desired), null));
        verify(network, times(1)).updateNetworkIp6Configuration(any());
    }

    @Test void resolverChangesIgnoreDhcpObservationAndUseDnsWriterMonitor() throws Exception {
        DnsResolvers original = resolvers();
        DnsResolvers current = resolvers(); current.setDhcpNameServers(List.of("192.168.1.1"));
        DnsResolvers next = resolvers(); next.setCustomNameServers(List.of("9.9.9.9"));
        when(service.getDnsResolvers()).thenAnswer(invocation -> { assertTrue(Thread.holdsLock(dns)); return current; });
        when(service.setDnsResolvers(any())).thenAnswer(invocation -> { assertTrue(Thread.holdsLock(dns)); return invocation.getArgument(0); });
        assertEquals(List.of("9.9.9.9"), controller.patchResolvers(change(original, next), null).getCustomNameServers());
        current.setCustomResolverMode("random");
        assertThrows(ConflictException.class, () -> controller.patchResolvers(change(original, next), null));
        verify(service, times(1)).setDnsResolvers(any());
    }

    @Test void staleRecordsNeverReplaceNewerRecords() throws Exception {
        when(service.getLocalDnsRecords()).thenAnswer(invocation -> { assertTrue(Thread.holdsLock(dns)); return List.of(); });
        assertThrows(ConflictException.class, () -> controller.patchRecords(request("{\"expected\":[{\"name\":\"old\"}],\"value\":[]}"), null));
        verify(service, never()).setLocalDnsRecords(any());
    }

    @Test void statusSharesNetworkWriterMonitorAndRejectsStaleValue() throws Exception {
        when(service.isEnabled()).thenAnswer(invocation -> { assertTrue(Thread.holdsLock(state)); return true; });
        when(service.setStatus(false)).thenAnswer(invocation -> { assertTrue(Thread.holdsLock(state)); return false; });
        assertFalse(controller.patchStatus(change(true, false), null));
        assertThrows(ConflictException.class, () -> controller.patchStatus(change(false, true), null));
        verify(service, times(1)).setStatus(anyBoolean());
    }

    @ParameterizedTest
    @ValueSource(strings={"{}", "null", "[]", "{\"expected\":true,\"value\":false,\"extra\":0}",
        "{\"expected\":true,\"value\":false,\"value\":true}", "{\"expected\":null,\"value\":false}",
        "{\"expected\":true,\"value\":\"false\"}", "{\"expected\":true,\"value\":false} true"})
    void rejectsAmbiguousOrInvalidBodiesBeforeReadingState(String body) {
        assertThrows(BadRequestException.class, () -> controller.patchStatus(request(body), null));
        verifyNoInteractions(service, state, dns, network);
    }

    @Test void rejectsMissingIpv4SettingsAndOversizedInput() throws Exception {
        assertThrows(BadRequestException.class, () -> controller.patchNetwork(change(Map.of(), Map.of()), null));
        assertThrows(BadRequestException.class, () -> controller.patchStatus(request(" ".repeat(262145)), null));
        verifyNoInteractions(service, state, dns, network);
    }

    private Request change(Object expected, Object value) throws Exception {
        return request(json.writeValueAsString(Map.of("expected", expected, "value", value)));
    }
    private Request request(String body) {
        Request request = mock(Request.class);
        when(request.getBodyAsStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
    private NetworkConfiguration network() {
        NetworkConfiguration config = new NetworkConfiguration(); config.setAutomatic(true); config.setDhcpLeaseTime(600); config.setRevision("rev-1"); return config;
    }
    private DnsResolvers resolvers() {
        DnsResolvers resolvers = new DnsResolvers(); resolvers.setDefaultResolver("dhcp");
        resolvers.setCustomResolverMode("default"); return resolvers;
    }
}
