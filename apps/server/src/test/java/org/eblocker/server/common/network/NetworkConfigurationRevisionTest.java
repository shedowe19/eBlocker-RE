// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.common.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.NetworkConfiguration;
import org.eblocker.server.common.data.NetworkStateId;
import org.eblocker.server.common.data.PendingNetworkConfiguration;
import org.eblocker.server.common.data.events.EventLogger;
import org.eblocker.server.common.network.unix.DnsEnableByDefaultChecker;
import org.eblocker.server.common.network.unix.EblockerDnsServer;
import org.eblocker.server.common.network.unix.IpSets;
import org.eblocker.server.common.ssl.SslService;
import org.eblocker.server.http.controller.NetworkSettingsController;
import org.eblocker.server.http.controller.impl.NetworkControllerImpl;
import org.eblocker.server.http.service.DnsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.restexpress.Request;
import org.restexpress.exception.ConflictException;

import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkConfigurationRevisionTest {
    private final ObjectMapper json = new ObjectMapper();
    private DataSource data;
    private NetworkServices services;
    private NetworkStateMachine state;
    private AtomicReference<PendingNetworkConfiguration> persisted;
    private NetworkConfiguration runtime;

    @BeforeEach
    void setup() {
        data = mock(DataSource.class); services = mock(NetworkServices.class);
        persisted = new AtomicReference<>();
        runtime = configuration("192.168.1.2");
        when(services.getCurrentNetworkConfiguration()).thenReturn(runtime);
        when(data.getCurrentNetworkState()).thenReturn(NetworkStateId.EXTERNAL_DHCP);
        when(data.get(PendingNetworkConfiguration.class)).thenAnswer(call -> persisted.get());
        when(data.save(any(PendingNetworkConfiguration.class))).thenAnswer(call -> {
            PendingNetworkConfiguration value = call.getArgument(0); persisted.set(value); return value;
        });
        doAnswer(call -> { persisted.set(null); return null; }).when(data).delete(PendingNetworkConfiguration.class);
        state = machine("boot-a");
    }
    private NetworkStateMachine machine(String boot) {
        return new NetworkStateMachine(services, data, mock(DnsEnableByDefaultChecker.class), mock(EventLogger.class),
                mock(IpSets.class), mock(SslService.class), mock(EblockerDnsServer.class), mock(Ip6PrefixMonitor.class), boot);
    }
    private NetworkConfiguration configuration(String ip) {
        NetworkConfiguration configuration = new NetworkConfiguration();
        configuration.setIpAddress(ip); configuration.setNetworkMask("255.255.255.0"); configuration.setGateway("192.168.1.1");
        configuration.setNameServerPrimary("192.168.1.1"); configuration.setDhcpRangeFirst("192.168.1.20");
        configuration.setDhcpRangeLast("192.168.1.200"); return configuration;
    }
    private Request change(Object expected, Object value) throws Exception {
        Request request = mock(Request.class);
        when(request.getBodyAsStream()).thenReturn(new ByteArrayInputStream(json.writeValueAsBytes(Map.of("expected", expected, "value", value))));
        return request;
    }

    @Test
    void pendingStaticIpRemainsDistinctFromRuntimeAndCannotBeOverwrittenByStalePatch() throws Exception {
        NetworkSettingsController controller = new NetworkSettingsController(json, services, state, mock(EblockerDnsServer.class), mock(DnsService.class));
        NetworkConfiguration original = state.getConfigurationSnapshot();
        NetworkConfiguration desired = configuration("192.168.1.3");
        NetworkConfiguration result = controller.patchNetwork(change(original, desired), null);
        assertEquals("192.168.1.2", result.getIpAddress());
        assertEquals("192.168.1.3", result.getPendingConfiguration().getIpAddress());
        assertTrue(result.isPendingReboot()); assertTrue(result.isRebootNecessary());
        assertNotEquals(original.getRevision(), result.getRevision());
        assertThrows(ConflictException.class, () -> controller.patchNetwork(change(original, configuration("192.168.1.4")), null));
        assertThrows(ConflictException.class, () -> controller.patchNetwork(change(result, configuration("192.168.1.4")), null));
        verify(services, times(1)).applyNetworkConfiguration(any());
        assertEquals("192.168.1.3", persisted.get().configuration().getIpAddress());
    }

    @Test
    void legacyWriterAlsoRotatesRevisionAndCannotOverwritePendingDesiredState() {
        String before = state.getConfigurationSnapshot().getRevision();
        assertFalse(state.updateConfiguration(configuration("192.168.1.2")));
        assertNotEquals(before, state.getConfigurationSnapshot().getRevision());
        assertThrows(ConflictException.class, () -> state.requireConfigurationRevision(before));
        state.updateConfiguration(configuration("192.168.1.3"));
        assertThrows(ConflictException.class, () -> state.updateConfiguration(configuration("192.168.1.4")));
        assertEquals("192.168.1.3", persisted.get().configuration().getIpAddress());
    }

    @Test
    void javaRestartKeepsPendingButInvalidatesRevisionAndRealBootConsumesPending() throws Exception {
        state.updateConfiguration(configuration("192.168.1.3"));
        String oldRevision = state.getConfigurationSnapshot().getRevision();
        // The journal also survives its real JSON serialization format.
        persisted.set(json.readValue(json.writeValueAsBytes(persisted.get()), PendingNetworkConfiguration.class));
        NetworkStateMachine restarted = machine("boot-a");
        assertTrue(restarted.getConfigurationSnapshot().isPendingReboot());
        assertNotEquals(oldRevision, restarted.getConfigurationSnapshot().getRevision());
        assertThrows(ConflictException.class, () -> restarted.requireConfigurationRevision(oldRevision));
        NetworkStateMachine rebooted = machine("boot-b");
        assertFalse(rebooted.getConfigurationSnapshot().isPendingReboot());
        assertNull(persisted.get());
        assertEquals("192.168.1.2", rebooted.getConfigurationSnapshot().getIpAddress()); // actual observation, never invented B
    }

    @Test
    void unknownBootIdentityDoesNotDiscardPendingState() {
        state.updateConfiguration(configuration("192.168.1.3"));
        assertTrue(machine(null).getConfigurationSnapshot().isPendingReboot());
        assertNotNull(persisted.get());
    }

    @Test
    void failedPersistencePreventsNetworkMutationAndInvalidatesOldSnapshot() {
        String original = state.getConfigurationSnapshot().getRevision();
        when(data.save(any(PendingNetworkConfiguration.class))).thenReturn(null);
        assertThrows(IllegalStateException.class, () -> state.updateConfiguration(configuration("192.168.1.3")));
        assertNotEquals(original, state.getConfigurationSnapshot().getRevision());
        verify(services, never()).enableStaticIp(any());
        verify(services, never()).applyNetworkConfiguration(any());
    }

    @Test
    void partialNetworkFailureRetainsJournalAndBlocksAnotherWriter() {
        doThrow(new IllegalStateException("apply failed")).when(services).applyNetworkConfiguration(any());
        assertThrows(IllegalStateException.class, () -> state.updateConfiguration(configuration("192.168.1.3")));
        assertTrue(state.getConfigurationSnapshot().isPendingReboot());
        assertThrows(ConflictException.class, () -> state.updateConfiguration(configuration("192.168.1.4")));
        verify(services, times(1)).applyNetworkConfiguration(any());
    }

    @Test
    void deviceListenerCannotWriteRuntimeIpOverPendingDhcpConfiguration() {
        when(data.getCurrentNetworkState()).thenReturn(NetworkStateId.LOCAL_DHCP);
        NetworkConfiguration desired = configuration("192.168.1.3"); desired.setDhcp(true);
        state.updateConfiguration(desired);
        clearInvocations(services);
        state.deviceStateChanged();
        var argument = org.mockito.ArgumentCaptor.forClass(NetworkConfiguration.class);
        verify(services).configureDhcpServer(argument.capture());
        assertEquals("192.168.1.3", argument.getValue().getIpAddress());
    }

    @Test
    void getControllerExposesConsistentRevisionAndSnapshotsAreDefensive() {
        state.updateConfiguration(configuration("192.168.1.3"));
        NetworkControllerImpl controller = new NetworkControllerImpl(null, state, services);
        NetworkConfiguration result = (NetworkConfiguration) controller.getConfiguration(null, null);
        result.getPendingConfiguration().setIpAddress("192.168.1.99");
        result.setIpAddress("192.168.1.98");
        assertEquals("192.168.1.2", runtime.getIpAddress());
        assertEquals("192.168.1.3", state.getConfigurationSnapshot().getPendingConfiguration().getIpAddress());
    }
}
