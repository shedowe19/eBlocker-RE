/* Copyright 2026 eBlocker contributors. Licensed under EUPL-1.2. */
package org.eblocker.server.http.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.strategicgains.syntaxe.ValidationEngine;
import org.eblocker.server.common.data.NetworkConfiguration;
import org.eblocker.server.common.data.NetworkIp6Configuration;
import org.eblocker.server.common.data.dns.DnsResolvers;
import org.eblocker.server.common.data.dns.LocalDnsRecord;
import org.eblocker.server.common.network.NetworkServices;
import org.eblocker.server.common.network.NetworkStateMachine;
import org.eblocker.server.common.network.unix.EblockerDnsServer;
import org.eblocker.server.http.service.DnsService;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;

import java.util.List;
import java.util.Set;

/** Compare and apply under the same monitor as existing configuration writers. */
@Singleton
public final class NetworkSettingsController {
    private static final Set<String> NETWORK_FIELDS = Set.of("automatic", "expertMode", "dhcp", "dnsServer",
            "ipAddress", "networkMask", "gateway", "nameServerPrimary", "nameServerSecondary",
            "dhcpRangeFirst", "dhcpRangeLast", "ipFixedByDefault", "dhcpLeaseTime");
    private static final Set<String> REQUIRED_NETWORK_FIELDS = Set.of("automatic", "expertMode", "dhcp", "dnsServer", "ipFixedByDefault", "dhcpLeaseTime");
    private static final Set<String> IP6_FIELDS = Set.of("routerAdvertisementsEnabled", "privacyExtensionsEnabled");
    private static final Set<String> RESOLVER_FIELDS = Set.of("defaultResolver", "customResolverMode", "customNameServers");
    private final ObjectMapper json;
    private final NetworkServices network;
    private final NetworkStateMachine state;
    private final EblockerDnsServer dns;
    private final DnsService dnsService;

    @Inject
    public NetworkSettingsController(ObjectMapper json, NetworkServices network, NetworkStateMachine state,
                                     EblockerDnsServer dns, DnsService dnsService) {
        this.json = json.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL).enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.network = network;
        this.state = state;
        this.dns = dns;
        this.dnsService = dnsService;
    }

    public NetworkConfiguration patchNetwork(Request request, Response response) {
        JsonNode change = read(request);
        NetworkConfiguration next = decode(change.get("value"), NetworkConfiguration.class);
        requireFields(change.get("value"), REQUIRED_NETWORK_FIELDS);
        ValidationEngine.validateAndThrow(next);
        synchronized (state) {
            JsonNode expectedRevision = change.get("expected").get("revision");
            if (expectedRevision == null || !expectedRevision.isTextual()) throw invalid();
            state.requireConfigurationRevision(expectedRevision.textValue());
            NetworkConfiguration current = state.getConfigurationSnapshot();
            compare(change.get("expected"), current, NETWORK_FIELDS);
            // Observed runtime fields are not settings supplied by the browser.
            next.setVpnIpAddress(current.getVpnIpAddress());
            next.setGlobalIp6AddressAvailable(current.isGlobalIp6AddressAvailable());
            next.setAdvisedNameServer(current.getAdvisedNameServer());
            state.updateConfiguration(next);
            return state.getConfigurationSnapshot();
        }
    }

    public NetworkIp6Configuration patchIp6(Request request, Response response) {
        JsonNode change = read(request);
        NetworkIp6Configuration next = decode(change.get("value"), NetworkIp6Configuration.class);
        requireFields(change.get("value"), IP6_FIELDS);
        synchronized (network) {
            NetworkIp6Configuration current = network.getNetworkIp6Configuration();
            compare(change.get("expected"), current, IP6_FIELDS);
            next.setLocalAddresses(current.getLocalAddresses());
            next.setGlobalAddresses(current.getGlobalAddresses());
            network.updateNetworkIp6Configuration(next);
            return next;
        }
    }

    public DnsResolvers patchResolvers(Request request, Response response) {
        JsonNode change = read(request);
        DnsResolvers next = decode(change.get("value"), DnsResolvers.class);
        requireFields(change.get("value"), Set.of("defaultResolver", "customResolverMode", "customNameServers"));
        if (next.getDefaultResolver() == null || !Set.of("dhcp", "custom", "tor").contains(next.getDefaultResolver())
                || next.getCustomNameServers() == null || next.getCustomNameServers().size() > 256
                || next.getCustomNameServers().stream().anyMatch(s -> s == null || s.length() > 1024)
                || next.getCustomResolverMode() == null
                || !Set.of("default", "random", "round_robin").contains(next.getCustomResolverMode())) {
            throw invalid();
        }
        synchronized (dns) {
            compare(change.get("expected"), dnsService.getDnsResolvers(), RESOLVER_FIELDS);
            try { return dnsService.setDnsResolvers(next); }
            catch (IllegalArgumentException e) { throw invalid(); }
        }
    }

    public List<LocalDnsRecord> patchRecords(Request request, Response response) {
        JsonNode change = read(request);
        if (!change.get("value").isArray() || change.get("value").size() > 4096) throw invalid();
        List<LocalDnsRecord> next;
        try { next = json.readerFor(new TypeReference<List<LocalDnsRecord>>() {}).readValue(change.get("value")); }
        catch (java.io.IOException e) { throw invalid(); }
        if (next.stream().anyMatch(record -> record == null || record.getName() == null
                || record.getName().isBlank() || record.getName().length() > 253)) throw invalid();
        if (next.stream().map(LocalDnsRecord::getName).distinct().count() != next.size()) throw invalid();
        synchronized (dns) {
            compare(change.get("expected"), dnsService.getLocalDnsRecords(), null);
            return dnsService.setLocalDnsRecords(next);
        }
    }

    public boolean patchStatus(Request request, Response response) {
        JsonNode change = read(request);
        if (!change.get("value").isBoolean()) throw invalid();
        synchronized (state) {
            compare(change.get("expected"), dnsService.isEnabled(), null);
            return dnsService.setStatus(change.get("value").booleanValue());
        }
    }

    private JsonNode read(Request request) {
        try {
            byte[] body = request.getBodyAsStream().readNBytes(262145);
            if (body.length > 262144) throw invalid();
            JsonNode node = json.readTree(body);
            if (node == null || !node.isObject() || node.size() != 2 || !node.hasNonNull("expected")
                    || !node.hasNonNull("value")) throw invalid();
            return node;
        } catch (java.io.IOException e) { throw invalid(); }
    }

    private <T> T decode(JsonNode value, Class<T> type) {
        try { return json.treeToValue(value, type); }
        catch (JsonProcessingException | IllegalArgumentException e) { throw invalid(); }
    }

    private void compare(JsonNode expected, Object current, Set<String> fields) {
        JsonNode actual = json.valueToTree(current);
        if (fields != null) {
            requireFields(expected, fields.equals(NETWORK_FIELDS) ? REQUIRED_NETWORK_FIELDS
                    : fields.equals(RESOLVER_FIELDS) ? Set.of("defaultResolver") : fields);
            expected = projection(expected, fields);
            actual = projection(actual, fields);
        }
        if (!actual.equals(expected)) throw new ConflictException("error.network.settingsChanged");
    }

    private ObjectNode projection(JsonNode value, Set<String> fields) {
        ObjectNode result = json.createObjectNode();
        fields.forEach(field -> result.set(field, value.has(field) ? value.get(field) : json.nullNode()));
        return result;
    }

    private static void requireFields(JsonNode value, Set<String> fields) {
        if (!value.isObject() || !fields.stream().allMatch(value::has)) throw invalid();
    }
    private static BadRequestException invalid() { return new BadRequestException("error.network.invalidSettings"); }
}
