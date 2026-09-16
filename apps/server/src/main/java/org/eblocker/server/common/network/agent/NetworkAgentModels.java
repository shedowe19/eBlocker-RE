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
package org.eblocker.server.common.network.agent;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** Public metadata accepted from agent protocol v1; secret-bearing fields have no representation. */
public final class NetworkAgentModels {
    private NetworkAgentModels() { }

    public record Envelope<T>(int schemaVersion, T data) { }

    public record Status(@JsonProperty(required = true) boolean readOnly,
                         @JsonProperty(required = true) Capabilities capabilities,
                         @JsonProperty(required = true) List<NetworkInterface> interfaces,
                         @JsonProperty(required = true) List<Route> routes) {
        public Status {
            if (capabilities == null) throw new IllegalArgumentException("Missing capabilities");
            interfaces = List.copyOf(interfaces);
            routes = List.copyOf(routes);
        }
    }

    public record Capabilities(@JsonProperty(required = true) boolean readOnly,
                               @JsonProperty(required = true) List<String> operations,
                               @JsonProperty(required = true) WireGuardCapability wireguard,
                               @JsonProperty(required = true) List<String> limitations) {
        public Capabilities {
            if (wireguard == null) throw new IllegalArgumentException("Missing capability");
            operations = List.copyOf(operations);
            limitations = List.copyOf(limitations);
        }
    }

    public record WireGuardCapability(@JsonProperty(required = true) boolean kernelFamilyRegistered,
                                      @JsonProperty(required = true) String state,
                                      @JsonProperty(required = true) boolean management,
                                      @JsonProperty(required = true) String reason) { }

    public record NetworkInterface(@JsonProperty(required = true) int index,
                                   @JsonProperty(required = true) String name,
                                   @JsonProperty(required = true) int mtu,
                                   String hardwareAddress,
                                   @JsonProperty(required = true) boolean up,
                                   @JsonProperty(required = true) boolean running,
                                   @JsonProperty(required = true) boolean loopback,
                                   @JsonProperty(required = true) boolean multicast,
                                   @JsonProperty(required = true) List<Address> addresses) {
        public NetworkInterface { addresses = List.copyOf(addresses); }
    }

    public record Address(@JsonProperty(required = true) String prefix,
                          @JsonProperty(required = true) String family,
                          @JsonProperty(required = true) String scope) { }

    public record Route(@JsonProperty(required = true) String family,
                        @JsonProperty(required = true) String destination,
                        String source, String gateway, String preferredSource, Long interfaceIndex,
                        @JsonProperty(required = true) long table,
                        @JsonProperty(required = true) long priority,
                        @JsonProperty(required = true) int protocol,
                        @JsonProperty(required = true) int scope,
                        @JsonProperty(required = true) int type,
                        @JsonProperty(required = true) long flags,
                        List<NextHop> nextHops) {
        public Route { nextHops = nextHops == null ? List.of() : List.copyOf(nextHops); }
    }

    public record NextHop(@JsonProperty(required = true) long interfaceIndex, String gateway,
                          @JsonProperty(required = true) int weight,
                          @JsonProperty(required = true) int flags) { }

    public record WireGuardPlan(@JsonProperty(required = true) List<String> interfaceAddresses,
                                @JsonProperty(required = true) List<String> dns,
                                Integer listenPort, Integer mtu,
                                @JsonProperty(required = true) List<Peer> peers,
                                @JsonProperty(required = true) boolean defaultRouteIPv4,
                                @JsonProperty(required = true) boolean defaultRouteIPv6,
                                @JsonProperty(required = true) List<String> endpointExclusions,
                                @JsonProperty(required = true) List<String> requiredCapabilities,
                                @JsonProperty(required = true) List<String> leakRisks,
                                @JsonProperty(required = true) List<String> warnings,
                                @JsonProperty(required = true) boolean applied,
                                @JsonProperty(required = true) boolean killSwitchActive) {
        public WireGuardPlan {
            interfaceAddresses = List.copyOf(interfaceAddresses);
            dns = List.copyOf(dns);
            peers = List.copyOf(peers);
            endpointExclusions = List.copyOf(endpointExclusions);
            requiredCapabilities = List.copyOf(requiredCapabilities);
            leakRisks = List.copyOf(leakRisks);
            warnings = List.copyOf(warnings);
            unsigned16(listenPort);
            unsigned16(mtu);
        }
    }

    public record Peer(@JsonProperty(required = true) String publicKey,
                       @JsonProperty(required = true) List<String> allowedIPs,
                       String endpoint, Integer persistentKeepalive,
                       @JsonProperty(required = true) boolean hasPresharedKey) {
        public Peer {
            allowedIPs = List.copyOf(allowedIPs);
            unsigned16(persistentKeepalive);
        }
    }

    private static void unsigned16(Integer value) {
        if (value != null && (value < 0 || value > 65535)) {
            throw new IllegalArgumentException("Invalid protocol number");
        }
    }
}
