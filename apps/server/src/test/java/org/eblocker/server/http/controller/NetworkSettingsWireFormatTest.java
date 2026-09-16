/* Copyright 2026 eBlocker contributors. Licensed under EUPL-1.2. */
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eblocker.server.common.data.IpAddressModule;
import org.eblocker.server.common.data.Ip4Address;
import org.eblocker.server.common.data.dns.DnsResolvers;
import org.eblocker.server.common.data.dns.LocalDnsRecord;
import org.eblocker.server.common.network.NetworkServices;
import org.eblocker.server.common.network.NetworkStateMachine;
import org.eblocker.server.common.network.unix.EblockerDnsServer;
import org.eblocker.server.http.service.DnsService;
import org.junit.jupiter.api.Test;
import org.restexpress.Request;
import org.restexpress.exception.BadRequestException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NetworkSettingsWireFormatTest {
    // Match JacksonJsonProcessor's real HTTP serializer, including omitted null fields.
    private final ObjectMapper wire = new ObjectMapper().registerModule(new IpAddressModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
    private final DnsService service = mock(DnsService.class);
    private final NetworkSettingsController controller = new NetworkSettingsController(
            new ObjectMapper().registerModule(new IpAddressModule()), mock(NetworkServices.class),
            mock(NetworkStateMachine.class), mock(EblockerDnsServer.class), service);

    @Test void dnsRecordsRoundTripUsesRealHttpNullOmissionAndIpStrings() throws Exception {
        var existing = new LocalDnsRecord("nas.home", false, false, Ip4Address.parse("192.168.1.20"), null, null, null);
        var updated = new LocalDnsRecord("nas.home", false, false, Ip4Address.parse("192.168.1.21"), null, null, null);
        when(service.getLocalDnsRecords()).thenReturn(List.of(existing));
        when(service.setLocalDnsRecords(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String body = wire.writeValueAsString(Map.of("expected", List.of(existing), "value", List.of(updated)));
        assertFalse(body.contains("ip6Address"));
        var result = controller.patchRecords(request(body), null);
        assertEquals("192.168.1.21", result.get(0).getIpAddress().toString());
        assertNull(result.get(0).getVpnIp6Address());
    }

    @Test void torResolverRemainsSupportedAndAbsentNullMetadataDoesNotConflict() throws Exception {
        var original = new DnsResolvers(); original.setDefaultResolver("dhcp"); original.setCustomNameServers(List.of());
        var desired = new DnsResolvers(); desired.setDefaultResolver("tor"); desired.setCustomResolverMode("default");
        when(service.getDnsResolvers()).thenReturn(original);
        when(service.setDnsResolvers(any())).thenAnswer(invocation -> invocation.getArgument(0));
        assertEquals("tor", controller.patchResolvers(request(wire.writeValueAsString(
                Map.of("expected", original, "value", desired))), null).getDefaultResolver());
    }

    @Test void explicitNullResolverIsBadRequestBeforeAnyStateMutation() {
        String body = "{\"expected\":{\"defaultResolver\":\"dhcp\"},\"value\":{\"defaultResolver\":null,\"customResolverMode\":\"default\",\"customNameServers\":[]}}";
        assertThrows(BadRequestException.class, () -> controller.patchResolvers(request(body), null));
        verifyNoInteractions(service);
    }

    private Request request(String body) {
        Request request = mock(Request.class);
        when(request.getBodyAsStream()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return request;
    }
}
