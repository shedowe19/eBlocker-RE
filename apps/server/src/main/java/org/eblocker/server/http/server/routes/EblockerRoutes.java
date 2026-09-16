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
import org.eblocker.server.http.server.StaticFileController;
import org.restexpress.RestExpress;

/**
 * Composes feature routes in the legacy matching order. Route names and flags are
 * authorization contracts; keep the static-file catch-all last.
 */
@Singleton
public final class EblockerRoutes {
    private final ProtectionRoutes protectionRoutes;
    private final DevicesRoutes devicesRoutes;
    private final ExperienceRoutes experienceRoutes;
    private final NetworkRoutes networkRoutes;
    private final FamilyRoutes familyRoutes;
    private final SystemRoutes systemRoutes;
    private final VpnRoutes vpnRoutes;
    private final AuthenticationRoutes authenticationRoutes;
    private final DiagnosticsRoutes diagnosticsRoutes;
    private final StaticFileController staticFileController;

    @Inject
    public EblockerRoutes(ProtectionRoutes protectionRoutes,
            DevicesRoutes devicesRoutes,
            ExperienceRoutes experienceRoutes,
            NetworkRoutes networkRoutes,
            FamilyRoutes familyRoutes,
            SystemRoutes systemRoutes,
            VpnRoutes vpnRoutes,
            AuthenticationRoutes authenticationRoutes,
            DiagnosticsRoutes diagnosticsRoutes,
            StaticFileController staticFileController) {
        this.protectionRoutes = protectionRoutes;
        this.devicesRoutes = devicesRoutes;
        this.experienceRoutes = experienceRoutes;
        this.networkRoutes = networkRoutes;
        this.familyRoutes = familyRoutes;
        this.systemRoutes = systemRoutes;
        this.vpnRoutes = vpnRoutes;
        this.authenticationRoutes = authenticationRoutes;
        this.diagnosticsRoutes = diagnosticsRoutes;
        this.staticFileController = staticFileController;
    }

    public void register(RestExpress server) {
        protectionRoutes.addFilterRoutes(server);
        devicesRoutes.addDeviceRoutes(server);
        experienceRoutes.addRedirectRoutes(server);
        networkRoutes.addNetworkRoutes(server);
        familyRoutes.addParentalControlRoutes(server);
        protectionRoutes.addDomainWhiteListRoutes(server);
        experienceRoutes.addDeviceRegistrationRoutes(server);
        protectionRoutes.addSSLRoutes(server);
        systemRoutes.addTimestampRoutes(server);
        experienceRoutes.addUserMessagesRoutes(server);
        vpnRoutes.addVpnRoutes(server);
        protectionRoutes.addControlBarFilterRoutes(server);
        experienceRoutes.addControlBarControlBarRoutes(server);
        protectionRoutes.addControlBarFilterGetStatsRoutes(server);
        experienceRoutes.addControlBarControlBarGetDeviceRoutes(server);
        devicesRoutes.addControlBarDeviceRoutes(server);
        experienceRoutes.addControlBarControlBarGetUsersRoutes(server);
        familyRoutes.addControlBarUserRoutes(server);
        experienceRoutes.addControlBarControlBarGetConsoleIpRoutes(server);
        vpnRoutes.addControlBarOpenVpnRoutes(server);
        devicesRoutes.addControlBarDeviceGetShowWarningsRoutes(server);
        vpnRoutes.addControlBarAnonymousRoutes(server);
        devicesRoutes.addControlBarUserAgentRoutes(server);
        experienceRoutes.addControlBarMessageCenterRoutes(server);
        systemRoutes.addControlBarTimestampRoutes(server);
        familyRoutes.addControlBarParentalControlRoutes(server);
        protectionRoutes.addControlBarFilterStatisticsRoutes(server);
        familyRoutes.addControlBarFilterListsRoutes(server);
        protectionRoutes.addControlBarSslRoutes(server);
        systemRoutes.addAdminConsoleSettingsRoutes(server);
        authenticationRoutes.addAdminConsoleAuthenticationRoutes(server);
        experienceRoutes.addAdminConsoleDeviceRegistrationRoutes(server);
        systemRoutes.addAdminConsoleUpdateRoutes(server);
        familyRoutes.addAdminConsoleUserRoutes(server);
        devicesRoutes.addAdminConsoleDeviceRoutes(server);
        protectionRoutes.addAdminConsoleSslRoutes(server);
        networkRoutes.addAdminConsoleDnsRoutes(server);
        vpnRoutes.addAdminConsoleAnonymousRoutes(server);
        experienceRoutes.addAdminConsoleFeatureRoutes(server);
        systemRoutes.addAdminConsoleLanguageRoutes(server);
        diagnosticsRoutes.addAdminConsoleEventRoutes(server);
        systemRoutes.addAdminConsoleFactoryResetRoutes(server);
        familyRoutes.addAdminConsoleFilterListsRoutes(server);
        devicesRoutes.addAdminConsoleUserAgentRoutes(server);
        networkRoutes.addAdminConsoleNetworkRoutes(server);
        vpnRoutes.addAdminConsoleOpenVpnRoutes(server);
        diagnosticsRoutes.addAdminConsoleRecordingRoutes(server);
        vpnRoutes.addAdminConsoleOpenVpnServerRoutes(server);
        experienceRoutes.addAdminConsoleCustomerInfoRoutes(server);
        diagnosticsRoutes.addAdminConsoleTransactionRecorderRoutes(server);
        protectionRoutes.addAdminConsoleFilterStatisticsRoutes(server);
        devicesRoutes.addAdminConsoleDeviceGetPauseByDeviceIdRoutes(server);
        experienceRoutes.addAdminConsoleControlBarRoutes(server);
        systemRoutes.addAdminConsoleTasksRoutes(server);
        diagnosticsRoutes.addAdminConsoleDoctorRoutes(server);
        experienceRoutes.addAdvicePageControlBarRoutes(server);
        devicesRoutes.addAdvicePageDeviceRoutes(server);
        devicesRoutes.addErrorPageRoutes(server);
        familyRoutes.addFilterListsRoutes(server);
        diagnosticsRoutes.addTransactionRecorderRoutes(server);
        systemRoutes.addDashboardSettingsRoutes(server);
        authenticationRoutes.addDashboardAuthenticationRoutes(server);
        devicesRoutes.addDashboardDeviceRoutes(server);
        familyRoutes.addDashboardParentalControlRoutes(server);
        experienceRoutes.addDashboardControlBarRoutes(server);
        familyRoutes.addDashboardParentalControlSetMaxUsageRoutes(server);
        protectionRoutes.addDashboardFilterStatisticsRoutes(server);
        experienceRoutes.addDashboardDeviceRegistrationRoutes(server);
        devicesRoutes.addDashboardDashboardCardRoutes(server);
        familyRoutes.addDashboardUserRoutes(server);
        experienceRoutes.addDashboardControlBarSetOperatingUserRoutes(server);
        familyRoutes.addDashboardUserChangePinRoutes(server);
        vpnRoutes.addDashboardOpenVpnServerRoutes(server);
        protectionRoutes.addDashboardCustomDomainFilterConfigRoutes(server);
        networkRoutes.addDashboardDnsRoutes(server);
        experienceRoutes.addDashboardDeviceRegistrationRegistrationStatusRoutes(server);
        vpnRoutes.addDashboardOpenVpnRoutes(server);
        devicesRoutes.addDashboardDeviceGetShowWarningsRoutes(server);
        vpnRoutes.addDashboardAnonymousRoutes(server);
        devicesRoutes.addDashboardUserAgentRoutes(server);
        protectionRoutes.addDashboardSslRoutes(server);
        familyRoutes.addDashboardFilterListsRoutes(server);
        diagnosticsRoutes.addDashboardDomainRecorderRoutes(server);
        devicesRoutes.addAdminDashboardDeviceRoutes(server);
        authenticationRoutes.addAdminDashboardAuthenticationRoutes(server);
        experienceRoutes.addPageContextRoutes(server);
        experienceRoutes.addReminderRoutes(server);
        systemRoutes.addConfigurationBackupRoutes(server);
        systemRoutes.addFeatureToggleRoutes(server);
        networkRoutes.addConnectionCheckRoutes(server);
        protectionRoutes.addBlockerRoutes(server);

        // This must be the last route (catch all):
        server
                .regex("/.*", staticFileController)
                .method(HttpMethod.GET)
                .name("static.route")
                .noSerialization();
    }
}
