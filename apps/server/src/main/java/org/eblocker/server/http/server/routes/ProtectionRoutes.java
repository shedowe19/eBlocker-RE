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
import org.eblocker.server.http.controller.AppWhitelistModuleController;
import org.eblocker.server.http.controller.BlockerController;
import org.eblocker.server.http.controller.CustomDomainFilterConfigController;
import org.eblocker.server.http.controller.DomainWhiteListController;
import org.eblocker.server.http.controller.FilterController;
import org.eblocker.server.http.controller.FilterStatisticsController;
import org.eblocker.server.http.controller.SSLController;
import org.eblocker.server.http.security.DashboardAuthorizationProcessor;
import org.restexpress.RestExpress;

/** Route declarations owned by the protection feature. */
@Singleton
final class ProtectionRoutes {
    private final FilterController filterController;
    private final DomainWhiteListController domainWhiteListController;
    private final SSLController sslController;
    private final AppWhitelistModuleController appModulesController;
    private final FilterStatisticsController filterStatisticsController;
    private final CustomDomainFilterConfigController customDomainFilterConfigController;
    private final BlockerController blockerController;

    @Inject
    ProtectionRoutes(FilterController filterController,
            DomainWhiteListController domainWhiteListController,
            SSLController sslController,
            AppWhitelistModuleController appModulesController,
            FilterStatisticsController filterStatisticsController,
            CustomDomainFilterConfigController customDomainFilterConfigController,
            BlockerController blockerController) {
        this.filterController = filterController;
        this.domainWhiteListController = domainWhiteListController;
        this.sslController = sslController;
        this.appModulesController = appModulesController;
        this.filterStatisticsController = filterStatisticsController;
        this.customDomainFilterConfigController = customDomainFilterConfigController;
        this.blockerController = blockerController;
    }

    void addFilterRoutes(RestExpress server) {
        // dashboard
        server
                .uri("/summary/whitelist/config", filterController)
                .action("getConfig", HttpMethod.GET)
                .name("dashboard.filter.config.route");

        // dashboard

        server
                .uri("/summary/whitelist/config", filterController)
                .action("putConfig", HttpMethod.PUT)
                .name("dashboard.filter.save.config.route");
    }

    void addDomainWhiteListRoutes(RestExpress server) {
        // dashboard
        server
                .uri("/summary/whitelist/all", domainWhiteListController)
                .action("getWhitelist", HttpMethod.GET)
                .name("public.whitelist.get.route");

        // dashboard

        server
                .uri("/summary/whitelist/all", domainWhiteListController)
                .action("setWhitelist", HttpMethod.PUT)
                .name("public.whitelist.set.route");

        // dashboard

        server
                .uri("/summary/whitelist/update", domainWhiteListController)
                .action("updateWhitelistEntry", HttpMethod.PUT)
                .name("public.whitelist.update.route");
    }

    void addSSLRoutes(RestExpress server) {
        server
                .uri("/api/ssl/caCertificate.crt", sslController)
                .action("getCACertificateByteStream", HttpMethod.GET)
                .noSerialization()
                .name("public.ssl.root.ca.download.route");

        server
                .uri("/api/ssl/firefox/caCertificate.crt", sslController)
                .action("getCACertificateByteStreamForFirefox", HttpMethod.GET)
                .noSerialization()
                .name("public.ssl.root.ca.download.firefox.route");

        server
                .uri("/api/ssl/firefox/renewalCertificate.crt", sslController)
                .action("getRenewalCertificateByteStreamForFirefox", HttpMethod.GET)
                .noSerialization()
                .name("public.ssl.renewal.download.firefox.route");

        server
                .uri("/api/ssl/renewalCertificate.crt", sslController)
                .action("getRenewalCertificateByteStream", HttpMethod.GET)
                .noSerialization()
                .name("public.ssl.root.renewal.download.route");
        //TODO: Is this really required for the ControlBar?

        server
                .uri("/ssl/status", sslController)
                .action("getSSLState", HttpMethod.GET)
                .name("controlbar.ssl.status.get.route");

        server
                .uri("/ssl/whitelist", sslController)
                .action("addUrlToSSLWhitelist", HttpMethod.POST)
                .name("errorpageExclusive.whitelist.set.route");// Available only for errorpage and console, not for controlbar

        server.uri("/ssl/test/{serialNumber}", sslController)
                .action("markCertificateStatus", HttpMethod.POST)
                .name("public.ssl.root.ca.test");

        server
                .uri("/api/ssl/device/status", sslController)
                .action("setDeviceStatus", HttpMethod.POST)
                .name("dashboard.sll.device.status.post");

        server
                .uri("/api/ssl/status", sslController)
                .action("getSslDashboardStatus", HttpMethod.GET)
                .name("dashboard.ssl.status.get");

        server
                .uri("/api/ssl/errors", sslController)
                .action("getFailedConnections", HttpMethod.GET)
                .name("ssl.failed.connections.get");

        server
                .uri("/api/ssl/errors", sslController)
                .action("clearFailedConnections", HttpMethod.DELETE)
                .name("ssl.failed.connections.delete");

        server
                .uri("/api/ssl/errors/recording", sslController)
                .action("getErrorRecordingEnabled", HttpMethod.GET)
                .name("ssl.error.recording.enabled.get");

        server
                .uri("/api/ssl/errors/recording", sslController)
                .action("setErrorRecordingEnabled", HttpMethod.PUT)
                .name("ssl.error.recording.enabled.set");
    }

    void addControlBarFilterRoutes(RestExpress server) {
        // public: Must be available, even before ControlBar is open.
        // There is a certain level of protection due to the pageContextId!
        server
                .uri("/filter/badge/{pageContextId}", filterController)
                .action("getBadge", HttpMethod.GET)
                .name("public.filter.badge.route");
    }

    void addControlBarFilterGetStatsRoutes(RestExpress server) {
        server
                .uri("/api/filter/stats/{pageContextId}", filterController)
                .action("getStats", HttpMethod.GET)
                .name("controlbar.filter.getStats.route");

        server
                .uri("/api/filter/config", filterController)
                .action("getConfig", HttpMethod.GET)
                .name("controlbar.filter.getConfig.route");

        server
                .uri("/api/filter/config", filterController)
                .action("putConfig", HttpMethod.PUT)
                .name("controlbar.filter.putConfig.route");

        server
                .uri("/api/filter/blockedAds/{pageContextId}", filterController)
                .action("getBlockedAdsSet", HttpMethod.GET)
                .name("controlbar.filter.getBlockedAdsSet.route");

        server
                .uri("/api/whitelist/{pageContextId}", domainWhiteListController)
                .action("getDomainStatus", HttpMethod.GET)
                .name("controlbar.whitelist.getDomainStatus.route");

        server
                .uri("/api/whitelist/{pageContextId}", domainWhiteListController)
                .method(HttpMethod.PUT)
                .name("controlbar.whitelist.save.route");

        server
                .uri("/api/filter/blockedTrackings/{pageContextId}", filterController)
                .action("getBlockedTrackingsSet", HttpMethod.GET)
                .name("controlbar.filter.getBlockedTrackingsSet.route");

        // ** New Controlbar: General "get * by device"
    }

    void addControlBarFilterStatisticsRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/stats/filter", filterStatisticsController)
                .action("getStats", HttpMethod.GET)
                .name("public.controlbar.stats.filter.get");
    }

    void addControlBarSslRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/ssl/status", sslController)
                .action("getSslDashboardStatus", HttpMethod.GET)
                .name("controlbar.ssl.status.get");
    }

    void addAdminConsoleSslRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/ssl/status", sslController)
                .action("getSSLState", HttpMethod.GET)
                .name("adminconsole.ssl.status.get.route");

        server
                .uri("/api/adminconsole/ata/status", sslController)
                .action("setAutoTrustAppState", HttpMethod.POST)
                .name("adminconsole.ssl.ata.status.set.route");

        server
                .uri("/api/adminconsole/ata/status", sslController)
                .action("getAutoTrustAppState", HttpMethod.GET)
                .name("adminconsole.ssl.ata.status.get.route");

        server
                .uri("/api/adminconsole/ssl/status", sslController)
                .action("setSSLState", HttpMethod.POST)
                .name("adminconsole.ssl.status.set.route");

        server
                .uri("/api/adminconsole/ssl/status/renewal", sslController)
                .action("getSslDashboardStatus", HttpMethod.GET)
                .name("adminconsole.ssl.status.renewal.get");

        server
                .uri("/api/adminconsole/ssl/rootca", sslController)
                .action("createNewRootCA", HttpMethod.POST)
                .name("adminconsole.ssl.root.ca.set.route");

        server
                .uri("/api/adminconsole/ssl/rootca", sslController)
                .action("getRootCaCertificate", HttpMethod.GET)
                .name("adminconsole.ssl.root.ca.get");

        server.uri("/api/adminconsole/ssl/rootca/options", sslController)
                .action("getDefaultCaOptions", HttpMethod.GET)
                .name("adminconsole.ssl.root.ca.options");

        server
                .uri("/api/adminconsole/ssl/certs/status", sslController)
                .action("areCertificatesReady", HttpMethod.GET)
                .name("adminconsole.ssl.certs.status.get.route");

        server
                .uri("/api/adminconsole/ssl/whitelist", sslController)
                .action("addUrlToSSLWhitelist", HttpMethod.POST)
                .name("adminconsole.whitelist.set.route");

        server
                .uri("/api/adminconsole/ssl/errors", sslController)
                .action("getFailedConnections", HttpMethod.GET)
                .name("adminconsole.failed.connections.get");

        server
                .uri("/api/adminconsole/ssl/errors", sslController)
                .action("clearFailedConnections", HttpMethod.DELETE)
                .name("adminconsole.failed.connections.delete");

        server
                .uri("/api/adminconsole/ssl/errors/recording", sslController)
                .action("getErrorRecordingEnabled", HttpMethod.GET)
                .name("adminconsole.error.recording.enabled.get");

        server
                .uri("/api/adminconsole/ssl/errors/recording", sslController)
                .action("setErrorRecordingEnabled", HttpMethod.PUT)
                .name("adminconsole.error.recording.enabled.set");

        // ** New Adminconsole: Trusted apps

        server
                .uri("/api/adminconsole/trustedapps/id", appModulesController)
                .action("create", HttpMethod.POST)
                .name("adminconsole.app.modules.post.route");

        server
                .uri("/api/adminconsole/trustedapps/id/{id}", appModulesController)
                .action("read", HttpMethod.GET)
                .name("adminconsole.app.modules.get.route");

        server
                .uri("/api/adminconsole/trustedapps/id/{id}", appModulesController)
                .action("update", HttpMethod.PUT)
                .name("adminconsole.app.modules.put.route");

        server
                .uri("/api/adminconsole/trustedapps/id/{id}", appModulesController)
                .action("delete", HttpMethod.DELETE)
                .name("adminconsole.app.modules.delete.route");

        server
                .uri("/api/adminconsole/trustedapps/all", appModulesController)
                .action("getAppWhitelistModules", HttpMethod.GET)
                .name("adminconsole.app.modules.getall.route");

        server
                .uri("/api/adminconsole/trustedapps/enable", appModulesController)
                .action("enableAppWhitelistModule", HttpMethod.PUT)
                .name("adminconsole.app.modules.enable.route");

        server
                .uri("/api/adminconsole/trustedapps/unique", appModulesController)
                .action("isUnique", HttpMethod.GET)
                .name("adminconsole.app.modules.get.unique.route");

        // ** New Adminconsole: Trusted Domains

        server
                .uri("/api/adminconsole/trusteddomains/onlyenabled", appModulesController)
                .action("getOnlyEnabledAppWhitelistModules", HttpMethod.GET)
                .name("adminconsole.app.modules.get.enabled.route");

        server
                .uri("/api/adminconsole/trusteddomains/delete", sslController)
                .action("removeWhitelistedUrl", HttpMethod.PUT)
                .name("adminconsole.ssl.whitelist.delete.route");

        server
                .uri("/api/adminconsole/trusteddomains/deleteall", sslController)
                .action("removeAllWhitelistedUrl", HttpMethod.PUT)
                .name("adminconsole.ssl.whitelist.delete.all.route");

        // ** New Adminconsole: DNS
    }

    void addAdminConsoleFilterStatisticsRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/stats/total", filterStatisticsController)
                .action("getTotalStats", HttpMethod.GET)
                .name("adminconsole.stats.total.get");

        server
                .uri("/api/adminconsole/stats/domain", filterStatisticsController)
                .action("getBlockedDomainsStats", HttpMethod.GET)
                .name("adminconsole.blockedStats.filter.get");

        // ** New Adminconsole: Pausing the current device:
    }

    void addDashboardFilterStatisticsRoutes(RestExpress server) {
        server
                .uri("/api/filter/stats/device/{deviceId}", filterStatisticsController)
                .action("getStats", HttpMethod.GET)
                .name("dashboard.stats.route")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);

        server
                .uri("/api/filter/totalStats", filterStatisticsController)
                .action("getTotalStats", HttpMethod.GET)
                .name("dashboard.totalStats.route.get");

        server
                .uri("/api/filter/totalStats", filterStatisticsController)
                .action("resetTotalStats", HttpMethod.DELETE)
                .name("dashboard.totalStats.route.delete");

        server
                .uri("/api/filter/blockeddomains/{deviceId}", filterStatisticsController)
                .action("getBlockedDomainsStats", HttpMethod.GET)
                .name("dashboard.blockedStats.filter.get")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);

        server
                .uri("/api/filter/blockeddomains/{deviceId}", filterStatisticsController)
                .action("resetBlockedDomainsStats", HttpMethod.DELETE)
                .name("dashboard.blockedStats.filter.delete")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);

        // Registraion data
    }

    void addDashboardCustomDomainFilterConfigRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/customdomainfilter/{userId}", customDomainFilterConfigController)
                .action("getFilter", HttpMethod.GET)
                .name("dashboard.custom.domain.filter.get")
                .flag(DashboardAuthorizationProcessor.VERIFY_USER_ID);

        server
                .uri("/api/dashboard/customdomainfilter/{userId}", customDomainFilterConfigController)
                .action("setFilter", HttpMethod.PUT)
                .name("dashboard.custom.domain.filter.set")
                .flag(DashboardAuthorizationProcessor.VERIFY_USER_ID);

        // Dashboard Dns
    }

    void addDashboardSslRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/ssl/whitelist", sslController)
                .action("addUrlToSSLWhitelist", HttpMethod.POST)
                .name("dashboard.whitelist.set.route");

        // ** Error pages
    }

    void addBlockerRoutes(RestExpress server) {
        server
                .uri("/api/blockers/", blockerController)
                .action("getBlockers", HttpMethod.GET)
                .name("adminconsole.blocker.getAll");

        server
                .uri("/api/blockers/", blockerController)
                .action("createBlocker", HttpMethod.POST)
                .name("adminconsole.blocker.post");

        server
                .uri("/api/blockers/{id}", blockerController)
                .action("updateBlocker", HttpMethod.PUT)
                .name("adminconsole.blocker.put");

        server
                .uri("/api/blockers/{id}", blockerController)
                .action("removeBlocker", HttpMethod.DELETE)
                .name("adminconsole.blocker.delete");
    }
}
