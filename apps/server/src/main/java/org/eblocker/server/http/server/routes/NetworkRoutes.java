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
package org.eblocker.server.http.server.routes;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.netty.handler.codec.http.HttpMethod;
import org.eblocker.server.http.controller.ConnectionCheckController;
import org.eblocker.server.http.controller.DnsController;
import org.eblocker.server.http.controller.NetworkController;
import org.eblocker.server.http.controller.NetworkSettingsController;
import org.eblocker.server.http.controller.NetworkAgentController;
import org.eblocker.server.http.controller.WireGuardControlController;
import org.restexpress.RestExpress;

/** Route declarations owned by the network feature. */
@Singleton
final class NetworkRoutes {
    private final NetworkController networkController;
    private final NetworkSettingsController networkSettingsController;
    private final NetworkAgentController networkAgentController;
    private final WireGuardControlController wireGuardControlController;
    private final DnsController dnsController;
    private final ConnectionCheckController connectionCheckController;

    @Inject
    NetworkRoutes(NetworkController networkController,
            NetworkSettingsController networkSettingsController,
            NetworkAgentController networkAgentController,
            WireGuardControlController wireGuardControlController,
            DnsController dnsController,
            ConnectionCheckController connectionCheckController) {
        this.networkController = networkController;
        this.networkSettingsController = networkSettingsController;
        this.networkAgentController = networkAgentController;
        this.wireGuardControlController = wireGuardControlController;
        this.dnsController = dnsController;
        this.connectionCheckController = connectionCheckController;
    }

    void addNetworkRoutes(RestExpress server) {
        server
                .uri("/network/setupPageInfo", networkController)
                .action("getSetupPageInfo", HttpMethod.GET)
                .name("public.network.config.setuppage.get.route");
    }

    void addAdminConsoleDnsRoutes(RestExpress server) {
        server.uri("/api/adminconsole/dns/config/resolvers", networkSettingsController)
                .action("patchResolvers", HttpMethod.PATCH).name("adminconsole.dns.resolvers.settings.patch");
        server.uri("/api/adminconsole/dns/config/records", networkSettingsController)
                .action("patchRecords", HttpMethod.PATCH).name("adminconsole.dns.records.settings.patch");
        server.uri("/api/adminconsole/dns/status", networkSettingsController)
                .action("patchStatus", HttpMethod.PATCH).name("adminconsole.dns.status.settings.patch");

        server
                .uri("/api/adminconsole/dns/config/resolvers", dnsController)
                .action("getDnsResolvers", HttpMethod.GET)
                .name("adminconsole.dns.config.resolvers.get");

        server
                .uri("/api/adminconsole/dns/config/resolvers", dnsController)
                .action("setDnsResolvers", HttpMethod.PUT)
                .name("adminconsole.dns.config.resolvers.set");

        server
                .uri("/api/adminconsole/dns/config/records", dnsController)
                .action("getLocalDnsRecords", HttpMethod.GET)
                .name("adminconsole.dns.config.records.get");

        server
                .uri("/api/adminconsole/dns/config/records", dnsController)
                .action("setLocalDnsRecords", HttpMethod.PUT)
                .name("adminconsole.dns.config.records.set");

        server
                .uri("/api/adminconsole/dns/cache", dnsController)
                .action("flushCache", HttpMethod.DELETE)
                .name("adminconsole.dns.cache.flush");

        server
                .uri("/api/adminconsole/dns/status", dnsController)
                .action("getStatus", HttpMethod.GET)
                .name("adminconsole.dns.status.get");

        server
                .uri("/api/adminconsole/dns/status", dnsController)
                .action("setStatus", HttpMethod.PUT)
                .name("adminconsole.dns.status.set");

        server
                .uri("/api/adminconsole/dns/stats", dnsController)
                .action("getResolverStats", HttpMethod.GET)
                .name("adminconsole.dns.resolver.stats.get");

        // ** New Adminconsole: webrtc
    }

    void addAdminConsoleNetworkRoutes(RestExpress server) {
        server.uri("/api/adminconsole/network", networkSettingsController)
                .action("patchNetwork", HttpMethod.PATCH).name("adminconsole.network.settings.patch");
        server.uri("/api/adminconsole/network/ip6", networkSettingsController)
                .action("patchIp6", HttpMethod.PATCH).name("adminconsole.network.ip6.settings.patch");

        server
                .uri("/api/adminconsole/network/dhcpstate", networkController)
                .action("getDHCPActive", HttpMethod.GET)
                .name("adminconsole.network.config.get.network.state.route");

        server
                .uri("/api/adminconsole/network", networkController)
                .action("getConfiguration", HttpMethod.GET)
                .name("adminconsole.network.config.get.route");

        server
                .uri("/api/adminconsole/network/setupPageInfo", networkController)
                .action("getSetupPageInfo", HttpMethod.GET)
                .name("adminconsole.network.config.setuppage.get.route");

        server
                .uri("/api/adminconsole/network", networkController)
                .action("updateConfiguration", HttpMethod.PUT)
                .name("adminconsole.network.config.put.route");

        server
                .uri("/api/adminconsole/network/dhcpservers", networkController)
                .action("getDhcpServers", HttpMethod.GET)
                .name("adminconsole.network.config.get.dhcp.servers.route");

        server
                .uri("/api/adminconsole/network/ip6", networkController)
                .action("getConfigurationIp6", HttpMethod.GET)
                .name("adminconsole.network.config.ip6.get.route");

        server
                .uri("/api/adminconsole/network/ip6", networkController)
                .action("updateConfigurationIp6", HttpMethod.PUT)
                .name("adminconsole.network.config.ip6.put.route");

        server
                .uri("/api/adminconsole/network-agent/status", networkAgentController)
                .action("getStatus", HttpMethod.GET)
                .name("adminconsole.network.agent.status.get");

        server
                .uri("/api/adminconsole/wireguard/validate", networkAgentController)
                .action("validateWireGuard", HttpMethod.POST)
                .name("adminconsole.wireguard.validate.post");

        server.uri("/api/adminconsole/wireguard/profiles", wireGuardControlController)
                .action("listProfiles", HttpMethod.GET).name("adminconsole.wireguard.profiles.list");
        server.uri("/api/adminconsole/wireguard/profiles/{profileId}", wireGuardControlController)
                .action("getProfile", HttpMethod.GET).name("adminconsole.wireguard.profiles.get");
        server.uri("/api/adminconsole/wireguard/profiles/{profileId}", wireGuardControlController)
                .action("importProfile", HttpMethod.PUT).name("adminconsole.wireguard.profiles.import");
        server.uri("/api/adminconsole/wireguard/profiles/{profileId}", wireGuardControlController)
                .action("deleteProfile", HttpMethod.DELETE).name("adminconsole.wireguard.profiles.delete");
        server.uri("/api/adminconsole/wireguard/profiles/{profileId}/connect", wireGuardControlController)
                .action("connect", HttpMethod.POST).name("adminconsole.wireguard.profiles.connect");
        server.uri("/api/adminconsole/wireguard/profiles/{profileId}/disconnect", wireGuardControlController)
                .action("disconnect", HttpMethod.POST).name("adminconsole.wireguard.profiles.disconnect");
        server.uri("/api/adminconsole/wireguard/profiles/{profileId}/cancel", wireGuardControlController)
                .action("cancel", HttpMethod.POST).name("adminconsole.wireguard.profiles.cancel");

        // ** New Adminconsole: VPN
    }

    void addDashboardDnsRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/dns/status", dnsController)
                .action("getStatus", HttpMethod.GET)
                .name("dashboard.dns.status.get");

        // Dashboard setting / setup card

        server
                .uri("/api/dashboard/network/info", networkController)
                .action("getSetupPageInfo", HttpMethod.GET)
                .name("dashboard.network.config.get.route");
    }

    void addConnectionCheckRoutes(RestExpress server) {
        // Publicly available
        server
                .uri("/api/check/route", connectionCheckController)
                .action("routingTest", HttpMethod.GET)
                .name("public.connectioncheck.route");
    }
}
