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
import org.eblocker.server.http.controller.DashboardCardController;
import org.eblocker.server.http.controller.DeviceController;
import org.eblocker.server.http.controller.UserAgentController;
import org.eblocker.server.http.security.DashboardAuthorizationProcessor;
import org.restexpress.RestExpress;

/** Route declarations owned by the devices feature. */
@Singleton
final class DevicesRoutes {
    private final DeviceController deviceController;
    private final UserAgentController userAgentController;
    private final DashboardCardController dashboardCardController;

    @Inject
    DevicesRoutes(DeviceController deviceController,
            UserAgentController userAgentController,
            DashboardCardController dashboardCardController) {
        this.deviceController = deviceController;
        this.userAgentController = userAgentController;
        this.dashboardCardController = dashboardCardController;
    }

    void addDeviceRoutes(RestExpress server) {
        // controlbar: Used by controlbar to find current device.
        server
                .uri("/devices", deviceController)
                .action("getAllDevices", HttpMethod.GET)
                .name("controlbar.devices.route");

        server
                .uri("/api/device/icon", deviceController)
                .action("getIconSettings", HttpMethod.GET)
                .name("dashboard.icon.get.route");

        server
                .uri("/api/device/icon", deviceController)
                .action("resetIconSettings", HttpMethod.DELETE)
                .name("dashboard.icon.reset.route");

        server
                .uri("/api/device/icon", deviceController)
                .action("setIconSettings", HttpMethod.POST)
                .name("dashboard.icon.set.route");

        server
                .uri("/api/device/iconpos/{iconPos}", deviceController)
                .action("setIconPosition", HttpMethod.POST)
                .name("public.icon.position.set.route");
    }

    void addControlBarDeviceRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/device/ads/{deviceId}", deviceController)
                .action("updateDeviceDnsAdsEnabledStatus", HttpMethod.PUT)
                .name("controlbar.device.dns.ads.update");

        server
                .uri("/api/controlbar/device/trackers/{deviceId}", deviceController)
                .action("updateDeviceDnsTrackersEnabledStatus", HttpMethod.PUT)
                .name("controlbar.device.dns.trackers.update");

        // ** New Controlbar: Users
    }

    void addControlBarDeviceGetShowWarningsRoutes(RestExpress server) {
        server
                .uri("/api/tor/device/showwarnings", deviceController)
                .action("getShowWarnings", HttpMethod.GET)
                .name("controlbar.tor.device.getShowWarnings.route");

        server
                .uri("/api/tor/device/showwarnings", deviceController)
                .action("postShowWarnings", HttpMethod.POST)
                .name("controlbar.tor.device.postShowWarnings.route");
    }

    void addControlBarUserAgentRoutes(RestExpress server) {
        server
                .uri("/api/useragent/list", userAgentController)
                .action("getAgentList", HttpMethod.GET)
                .name("controlbar.useragent.getAgentList.route");

        server
                .uri("/api/useragent/cloaked", userAgentController)
                .action("getCloakedUserAgentByDeviceId", HttpMethod.GET)
                .name("controlbar.useragent.getCloakedUserAgentByDeviceId.route");

        server
                .uri("/api/useragent/cloaked", userAgentController)
                .action("setCloakedUserAgentByDeviceId", HttpMethod.PUT)
                .name("controlbar.useragent.setCloakedUserAgentByDeviceId.route");

        server
                .uri("/api/device/pauseStatus", deviceController)
                .action("getPauseCurrentDevice", HttpMethod.GET)
                .name("controlbar.device.get.pausestatus");

        // ** New Controlbar: Pause

        server
                .uri("/api/device/pause", deviceController)
                .action("pauseCurrentDeviceIfNotYetPausing", HttpMethod.POST)
                .name("controlbar.device.get.pause");// Pausing the current device, if not yet pausing:

        server
                .uri("/api/device/dialogStatus", deviceController)
                .action("getPauseDialogStatus", HttpMethod.GET)
                .name("controlbar.device.getPauseDialogStatus.pause");

        server
                .uri("/api/device/dialogStatus", deviceController)
                .action("updatePauseDialogStatus", HttpMethod.POST)
                .name("controlbar.device.updatePauseDialogStatus.pause");

        server
                .uri("/api/device/dialogStatusDoNotShowAgain", deviceController)
                .action("getPauseDialogStatusDoNotShowAgain", HttpMethod.GET)
                .name("controlbar.device.getPauseDialogStatusShowAgain.pause");

        server
                .uri("/api/device/dialogStatusDoNotShowAgain", deviceController)
                .action("updatePauseDialogStatusDoNotShowAgain", HttpMethod.POST)
                .name("controlbar.device.updatePauseDialogStatusDoNotShowAgain.pause");

        // ** New Controlbar: Messages
    }

    void addAdminConsoleDeviceRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/devices", deviceController)
                .action("getAllDevices", HttpMethod.GET)
                .name("adminconsole.devices.route");

        server
                .uri("/api/adminconsole/devices/scan", deviceController)
                .action("isScanningAvailable", HttpMethod.GET)
                .name("adminconsole.devices.scan.available.route");

        server
                .uri("/api/adminconsole/devices/scan", deviceController)
                .action("scanDevices", HttpMethod.POST)
                .name("adminconsole.devices.scan.route");

        server
                .uri("/api/adminconsole/devices/scanningInterval", deviceController)
                .action("getScanningInterval", HttpMethod.GET)
                .name("adminconsole.devices.get.scanning.interval");

        server
                .uri("/api/adminconsole/devices/scanningInterval", deviceController)
                .action("setScanningInterval", HttpMethod.POST)
                .name("adminconsole.devices.set.scanning.interval");

        server
                .uri("/api/adminconsole/devices/autoEnableNewDevices", deviceController)
                .action("isAutoEnableNewDevices", HttpMethod.GET)
                .name("adminconsole.devices.get.auto.enable.new.devices");

        server
                .uri("/api/adminconsole/devices/autoEnableNewDevices", deviceController)
                .action("setAutoEnableNewDevices", HttpMethod.POST)
                .name("adminconsole.devices.set.auto.enable.new.devices");

        server
                .uri("/api/adminconsole/devices/autoEnableNewDevicesAfterActivation", deviceController)
                .action("setAutoEnableNewDevicesAndResetExisting", HttpMethod.POST)
                .name("adminconsole.devices.set.auto.enable.new.devices.after.activation");

        server
                .uri("/api/adminconsole/devices/{deviceId}", deviceController)
                .action("getDeviceById", HttpMethod.GET)
                .name("adminconsole.device.get.by.id.route");

        server
                .uri("/api/adminconsole/devices/{deviceId}", deviceController)
                .action("updateDevice", HttpMethod.PUT)
                .name("adminconsole.devices.update.route");

        server
                .uri("/api/adminconsole/devices/{deviceId}/settings", deviceController)
                .action("updateDeviceSettings", HttpMethod.PATCH)
                .name("adminconsole.devices.settings.patch.route");

        server
                .uri("/api/adminconsole/devices/{deviceId}", deviceController)
                .action("deleteDevice", HttpMethod.DELETE)
                .name("adminconsole.devices.delete.route");

        server
                .uri("/api/adminconsole/devices/reset/{deviceId}", deviceController)
                .action("resetDevice", HttpMethod.PUT)
                .name("adminconsole.devices.reset.route");

        // ** New Adminconsole: SSL
    }

    void addAdminConsoleUserAgentRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/useragent/cloaked", userAgentController)
                .action("getCloakedUserAgentByDeviceId", HttpMethod.GET)
                .name("adminconsole.useragents.getCloakedUserAgentByDeviceId.route");

        server
                .uri("/api/adminconsole/useragent/list", userAgentController)
                .action("getAgentList", HttpMethod.GET)
                .name("adminconsole.useragent.getAgentList.route");

        server
                .uri("/api/adminconsole/useragent/cloaked", userAgentController)
                .action("setCloakedUserAgentByDeviceId", HttpMethod.PUT)
                .name("adminconsole.useragent.setCloakedUserAgentByDeviceId.route");

        // ** New Adminconsole: Network
    }

    void addAdminConsoleDeviceGetPauseByDeviceIdRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/device/pause", deviceController)
                .action("getPauseByDeviceId", HttpMethod.GET)
                .name("adminconsole.device.get.pause");

        server
                .uri("/api/adminconsole/device/pause", deviceController)
                .action("setPauseByDeviceId", HttpMethod.PUT)
                .name("adminconsole.device.put.pause");
    }

    void addAdvicePageDeviceRoutes(RestExpress server) {
        server
                .uri("/api/advice/device", deviceController)
                .action("getCurrentDevice", HttpMethod.GET)
                .name("advice.device.get");

        server
                .uri("/api/advice/device/showWelcomeFlags", deviceController)
                .action("updateShowWelcomeFlags", HttpMethod.PUT)
                .name("advice.device.updateShowWelcomeFlags");
    }

    void addErrorPageRoutes(RestExpress server) {
        server
                .uri("/api/errorpage/device/pause", deviceController)
                .action("pauseCurrentDeviceIfNotYetPausing", HttpMethod.POST)
                .name("errorpage.device.set.pause");
    }

    void addDashboardDeviceRoutes(RestExpress server) {
        server
                .uri("/api/device/pause/{deviceId}", deviceController)
                .action("getPauseByDeviceId", HttpMethod.GET)
                .name("dashboard.device.get.pause")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);

        server
                .uri("/api/device/pause/{deviceId}", deviceController)
                .action("setPauseByDeviceId", HttpMethod.PUT)
                .name("dashboard.device.put.pause")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);

        // Details about current device

        server
                .uri("/api/device/showWelcomeFlags", deviceController)
                .action("updateShowWelcomeFlags", HttpMethod.PUT)
                .name("dashboard.device.updateShowWelcomeFlags");

        server
                .uri("/api/device", deviceController)
                .action("getCurrentDevice", HttpMethod.GET)
                .name("dashboard.device.get");

        server
                .uri("/api/device/{deviceId}", deviceController)
                .action("updateDeviceDashboard", HttpMethod.PUT)
                .name("dashboard.device.update")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID)
                .flag("DENY_CHILD_ACCESS");

        server
                .uri("/api/dashboard/operatinguser/devices", deviceController)
                .action("getOperatingUserDevices", HttpMethod.GET)
                .name("dashboard.operatinguser.devices.get");
    }

    void addDashboardDashboardCardRoutes(RestExpress server) {
        server
                .uri("/api/dashboardcard", dashboardCardController)
                .action("getDashboardCards", HttpMethod.GET)
                .name("dashboard.card.get.status");

        server
                .uri("/api/dashboardcard/columns", dashboardCardController)
                .action("setDashboardColumnsView", HttpMethod.PUT)
                .name("dashboard.card.save.columns");

        server
                .uri("/api/dashboardcard/columns", dashboardCardController)
                .action("getDashboardColumnsView", HttpMethod.GET)
                .name("dashboard.card.get.columns");
    }

    void addDashboardDeviceGetShowWarningsRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/tor/device/showwarnings", deviceController)
                .action("getShowWarnings", HttpMethod.GET)
                .name("dashboard.tor.device.getShowWarnings.route");

        server
                .uri("/api/dashboard/tor/device/showwarnings", deviceController)
                .action("postShowWarnings", HttpMethod.POST)
                .name("dashboard.tor.device.postShowWarnings.route");
    }

    void addDashboardUserAgentRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/useragent/list", userAgentController)
                .action("getAgentList", HttpMethod.GET)
                .name("dashboard.useragent.getAgentList.route");

        server
                .uri("/api/dashboard/useragent/cloaked", userAgentController)
                .action("getCloakedUserAgentByDeviceId", HttpMethod.GET)
                .name("dashboard.useragent.getCloakedUserAgentByDeviceId.route");

        server
                .uri("/api/dashboard/useragent/cloaked", userAgentController)
                .action("setCloakedUserAgentByDeviceId", HttpMethod.PUT)
                .name("dashboard.useragent.setCloakedUserAgentByDeviceId.route");

        // ** Dashboard: SSL
    }

    void addAdminDashboardDeviceRoutes(RestExpress server) {
        server
                .uri("/api/admindashboard/devices", deviceController)
                .action("getAllDevices", HttpMethod.GET)
                .name("admindashboard.devices.get");

        // Only accessible with valid token (pw must have been entered for admindashboard)
    }
}
