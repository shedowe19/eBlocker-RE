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
import org.eblocker.server.http.controller.AnonymousController;
import org.eblocker.server.http.controller.MobileConnectionCheckController;
import org.eblocker.server.http.controller.MobileDnsCheckController;
import org.eblocker.server.http.controller.OpenVpnController;
import org.eblocker.server.http.controller.OpenVpnServerController;
import org.restexpress.RestExpress;

/** Route declarations owned by the vpn feature. */
@Singleton
final class VpnRoutes {
    private final AnonymousController anonymousController;
    private final OpenVpnController openVpnController;
    private final OpenVpnServerController openVpnServerController;
    private final MobileConnectionCheckController mobileConnectionCheckController;
    private final MobileDnsCheckController mobileDnsCheckController;

    @Inject
    VpnRoutes(AnonymousController anonymousController,
            OpenVpnController openVpnController,
            OpenVpnServerController openVpnServerController,
            MobileConnectionCheckController mobileConnectionCheckController,
            MobileDnsCheckController mobileDnsCheckController) {
        this.anonymousController = anonymousController;
        this.openVpnController = openVpnController;
        this.openVpnServerController = openVpnServerController;
        this.mobileConnectionCheckController = mobileConnectionCheckController;
        this.mobileDnsCheckController = mobileDnsCheckController;
    }

    void addVpnRoutes(RestExpress server) {
        server
                .uri("/anonymous/vpn/profiles", openVpnController)
                .action("getProfiles", HttpMethod.GET)
                .name("controlbar.vpn.profiles.get.route");// Called from controlbar

        server
                .uri("/anonymous/vpn/profiles/status/{device}", openVpnController)
                .action("getVpnStatusByDevice", HttpMethod.GET)
                .name("errorpage.vpn.profiles.get.status.device");// Called from controlbar and squid error page

        server
                .uri("/anonymous/vpn/profile/{id}/status", openVpnController)
                .action("getVpnStatus", HttpMethod.GET)
                .name("controlbar.vpn.profile.get.status");// Called from controlbar

        server // Similar to the one below - but used in a different place
                .uri("/anonymous/vpn/profile/{id}/status/{device}", openVpnController)
                .action("setVpnDeviceStatus", HttpMethod.PUT)
                .name("controlbar.vpn.profile.status.device.set");// Called from controlbar

        server // Similar to the one above - but used in a different place
                .uri("/anonymous/vpn/profile/{id}/status-this", openVpnController)
                .action("setVpnThisDeviceStatus", HttpMethod.PUT)
                .name("errorpageExclusive.vpn.profile.status.device.set");// Called from squid error page
    }

    void addControlBarOpenVpnRoutes(RestExpress server) {
        server
                .uri("/api/vpn/profiles", openVpnController)
                .action("getProfiles", HttpMethod.GET)
                .name("controlbar.vpn.getProfiles.route");
        // get VPN status: in setVpnActivationState, but basically the poller

        server
                .uri("/api/vpn/profiles/{id}/status", openVpnController)
                .action("getVpnStatus", HttpMethod.GET)
                .name("controlbar.vpn.getVpnStatus.route");
        // updateVpnStatus /anonymous/vpn/profiles/status/me

        server
                .uri("/api/vpn/profiles/status/{device}", openVpnController)
                .action("getVpnStatusByDevice", HttpMethod.GET)
                .name("controlbar.vpn.getVpnStatusByDevice.route");
        // setVpnActivationState

        server
                .uri("/api/vpn/profiles/{id}/status/{device}", openVpnController)
                .action("setVpnDeviceStatus", HttpMethod.PUT)
                .name("controlbar.vpn.setVpnDeviceStatus.route");

        server
                .uri("/api/tor/config", anonymousController)
                .action("getConfig", HttpMethod.GET)
                .name("controlbar.tor.getConfig.route");

        server
                .uri("/api/tor/config", anonymousController)
                .action("putConfig", HttpMethod.PUT)
                .name("controlbar.tor.putConfig.route");
    }

    void addControlBarAnonymousRoutes(RestExpress server) {
        server
                .uri("/api/tor/newidentity", anonymousController)
                .action("getNewTorIdentity", HttpMethod.PUT)
                .name("controlbar.tor.getNewTorIdentity.route");

        // ** New Controlbar: Cloaking
    }

    void addAdminConsoleAnonymousRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/webrtc", anonymousController)
                .action("setWebRTCBlockingState", HttpMethod.PUT)
                .name("adminconsole.anonymous.webrtc.route");

        server
                .uri("/api/adminconsole/webrtc", anonymousController)
                .action("isWebRTCBlockingEnabled", HttpMethod.GET)
                .name("adminconsole.anonymous.webrtc.route");

        // ** New Adminconsole: referrer

        server
                .uri("/api/adminconsole/referrer", anonymousController)
                .action("setHTTPRefererRemovingState", HttpMethod.PUT)
                .name("adminconsole.anonymous.referrer.route");

        server
                .uri("/api/adminconsole/referrer", anonymousController)
                .action("isHTTPRefererRemovingEnabled", HttpMethod.GET)
                .name("adminconsole.anonymous.referrer.route");

        // ** New Adminconsole: captive portal

        server
                .uri("/api/adminconsole/captiveportal", anonymousController)
                .action("setGoogleCaptivePortalRedirectState", HttpMethod.PUT)
                .name("adminconsole.anonymous.captiveportal.route");

        server
                .uri("/api/adminconsole/captiveportal", anonymousController)
                .action("getGoogleCaptivePortalRedirectState", HttpMethod.GET)
                .name("adminconsole.controlbar.anonymous.captiveportal.route");

        // ** New Adminconsole: do not track / do-not-track

        server
                .uri("/api/adminconsole/dnt", anonymousController)
                .action("getDntHeaderState", HttpMethod.GET)
                .name("adminconsole.anonymous.dntheader.get.route");

        server
                .uri("/api/adminconsole/dnt", anonymousController)
                .action("setDntHeaderState", HttpMethod.PUT)
                .name("adminconsole.anonymous.dntheader.set.route");

        // ** New Adminconsole: Compression
    }

    void addAdminConsoleOpenVpnRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/vpn/profiles", openVpnController)
                .action("getProfiles", HttpMethod.GET)
                .name("adminconsole.vpn.profiles.get.route");

        server
                .uri("/api/adminconsole/vpn/profile", openVpnController)
                .action("createProfile", HttpMethod.POST)
                .name("adminconsole.vpn.profiles.create.route");

        server
                .uri("/api/adminconsole/vpn/profile/{id}", openVpnController)
                .action("getProfile", HttpMethod.GET)
                .name("adminconsole.vpn.profile.get.route");

        server
                .uri("/api/adminconsole/vpn/profile/{id}", openVpnController)
                .action("updateProfile", HttpMethod.PUT)
                .name("adminconsole.vpn.profile.update.route");

        server
                .uri("/api/adminconsole/vpn/profile/{id}", openVpnController)
                .action("deleteProfile", HttpMethod.DELETE)
                .name("adminconsole.vpn.profile.delete.route");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/config", openVpnController)
                .action("getProfileConfig", HttpMethod.GET)
                .name("adminconsole.vpn.profile.get.config.route");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/config", openVpnController)
                .action("uploadProfileConfig", HttpMethod.PUT)
                .name("adminconsole.vpn.profile.create.config.route");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/config/{option}", openVpnController)
                .action("uploadProfileConfigOption", HttpMethod.PUT)
                .name("adminconsole.vpn.profile.set.config.option");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/status", openVpnController)
                .action("setVpnStatus", HttpMethod.PUT)
                .name("adminconsole.vpn.profile.set.status");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/status", openVpnController)
                .action("getVpnStatus", HttpMethod.GET)
                .name("adminconsole.vpn.profile.get.status");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/status/{device}", openVpnController)
                .action("getVpnDeviceStatus", HttpMethod.GET)
                .name("adminconsole.vpn.profile.status.device.get");

        server
                .uri("/api/adminconsole/vpn/profile/{id}/status/{device}", openVpnController)
                .action("setVpnDeviceStatus", HttpMethod.PUT)
                .name("adminconsole.vpn.profile.status.device.set");

        server
                .uri("/api/adminconsole/vpn/profile/status/{device}", openVpnController)
                .action("getVpnStatusByDevice", HttpMethod.GET)
                .name("adminconsole.vpn.getVpnStatusByDevice.route");

        // ** New Adminconsole: TOR

        server
                .uri("/api/adminconsole/tor/countries", anonymousController)
                .action("getTorCountries", HttpMethod.GET)
                .name("adminconsole.tor.route");

        server
                .uri("/api/adminconsole/tor/countries/selected", anonymousController)
                .action("setTorExitNodeCountries", HttpMethod.PUT)
                .name("adminconsole.tor.route");

        server
                .uri("/api/adminconsole/tor/countries/selected", anonymousController)
                .action("getCurrentTorExitNodeCountries", HttpMethod.GET)
                .name("adminconsole.tor.route");

        server
                .uri("/api/adminconsole/tor/config/{deviceId}", anonymousController)
                .action("getConfigById", HttpMethod.GET)
                .name("adminconsole.tor.getConfig.route");

        server
                .uri("/api/adminconsole/tor/config/{deviceId}", anonymousController)
                .action("putConfigById", HttpMethod.PUT)
                .name("adminconsole.tor.putConfig.route");

        server
                .uri("/api/adminconsole/tor/newidentity", anonymousController)
                .action("getNewTorIdentity", HttpMethod.PUT)
                .name("adminconsole.tor.getNewTorIdentity.route");

        // ** New Adminconsole: SSL Manual Recording (old experts tools)
    }

    void addAdminConsoleOpenVpnServerRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/openvpn/status", openVpnServerController)
                .action("getOpenVpnServerStatus", HttpMethod.GET)
                .name("adminconsole.vpn.server.status.get");

        server
                .uri("/api/adminconsole/openvpn/status", openVpnServerController)
                .action("setOpenVpnServerStatus", HttpMethod.POST)
                .name("adminconsole.vpn.server.status.set");

        server
                .uri("/api/adminconsole/openvpn/status", openVpnServerController)
                .action("resetOpenVpnServerStatus", HttpMethod.DELETE)
                .name("adminconsole.vpn.server.status.delete");

        server
                .uri("/api/adminconsole/openvpn/certificates", openVpnServerController)
                .action("getCertificates", HttpMethod.GET)
                .name("adminconsole.vpn.server.certificates.get");

        server
                .uri("/api/adminconsole/openvpn/certificates/generateDownloadUrl/{deviceId}/{deviceType}", openVpnServerController)
                .action("generateDownloadUrl", HttpMethod.GET)
                .name("adminconsole.vpn.server.certificates.generateDownloadUrl.get");

        server
                .uri("/api/adminconsole/openvpn/certificates/downloadClientConf/{deviceId}", openVpnServerController)
                .action("downloadClientConf", HttpMethod.GET)
                .name("adminconsole.vpn.server.certificates.downloadClientConf.get")
                .noSerialization();

        server
                .uri("/api/adminconsole/openvpn/enable/{deviceId}", openVpnServerController)
                .action("enableDevice", HttpMethod.POST)
                .name("adminconsole.vpn.server.enable.device.post");

        server
                .uri("/api/adminconsole/openvpn/disable/{deviceId}", openVpnServerController)
                .action("disableDevice", HttpMethod.POST)
                .name("adminconsole.vpn.server.disable.device.post");

        server
                .uri("/api/adminconsole/openvpn/privateNetworkAccess/{deviceId}", openVpnServerController)
                .action("setPrivateNetworkAccess", HttpMethod.PUT)
                .name("adminconsole.vpn.server.privateNetworkAccess.device.put");

        server
                .uri("/api/adminconsole/upnpn/{port}", openVpnServerController)
                .action("setPortForwarding", HttpMethod.PUT)
                .name("adminconsole.vpn.upnp.portForwarding");

        // ** New Adminconsole: eBlocker Mobile test connection

        server
                .uri("/api/adminconsole/openvpn/test", mobileConnectionCheckController)
                .action("start", HttpMethod.POST)
                .name("adminconsole.vpn.test.start");

        server
                .uri("/api/adminconsole/openvpn/test", mobileConnectionCheckController)
                .action("stop", HttpMethod.DELETE)
                .name("adminconsole.vpn.test.stop");

        server
                .uri("/api/adminconsole/openvpn/test", mobileConnectionCheckController)
                .action("getStatus", HttpMethod.GET)
                .name("adminconsole.vpn.test.status");

        server
                .uri("/api/adminconsole/openvpn/dns", mobileDnsCheckController)
                .action("check", HttpMethod.POST)
                .name("adminconsole.vpn.test.dns");

        // ** New Adminconsole: save customer info (for remind-me-again VPN offer)
    }

    void addDashboardOpenVpnServerRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/openvpn/filename/{deviceId}/{deviceType}", openVpnServerController)
                .action("getOpenVpnFileName", HttpMethod.GET)
                .name("dashboard.vpn.server.filename.get");

        server
                .uri("/api/dashboard/openvpn/status", openVpnServerController)
                .action("getOpenVpnServerStatus", HttpMethod.GET)
                .name("dashboard.vpn.server.status.get");

        server
                .uri("/api/dashboard/openvpn/status", openVpnServerController)
                .action("setOpenVpnServerStatus", HttpMethod.POST)
                .name("dashboard.vpn.server.status.set");

        server
                .uri("/api/dashboard/openvpn/status", openVpnServerController)
                .action("resetOpenVpnServerStatus", HttpMethod.DELETE)
                .name("dashboard.vpn.server.status.delete");

        server
                .uri("/api/dashboard/openvpn/certificates", openVpnServerController)
                .action("getCertificates", HttpMethod.GET)
                .name("dashboard.vpn.server.certificates.get");

        server
                .uri("/api/dashboard/openvpn/certificates/generateDownloadUrl/{deviceId}/{deviceType}", openVpnServerController)
                .action("generateDownloadUrl", HttpMethod.GET)
                .name("dashboard.vpn.server.certificates.generateDownloadUrl.get");

        server
                .uri("/api/dashboard/openvpn/certificates/downloadClientConf/{deviceId}", openVpnServerController)
                .action("downloadClientConf", HttpMethod.GET)
                .name("dashboard.vpn.server.certificates.downloadClientConf.get")
                .noSerialization();
    }

    void addDashboardOpenVpnRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/vpn/profiles", openVpnController)
                .action("getProfiles", HttpMethod.GET)
                .name("dashboard.vpn.getProfiles.route");
        // get VPN status: in setVpnActivationState, but basically the poller

        server
                .uri("/api/dashboard/vpn/profiles/{id}/status", openVpnController)
                .action("getVpnStatus", HttpMethod.GET)
                .name("dashboard.vpn.getVpnStatus.route");
        // updateVpnStatus /anonymous/vpn/profiles/status/me

        server
                .uri("/api/dashboard/vpn/profiles/status/{device}", openVpnController)
                .action("getVpnStatusByDevice", HttpMethod.GET)
                .name("dashboard.vpn.getVpnStatusByDevice.route");
        // setVpnActivationState

        server
                .uri("/api/dashboard/vpn/profiles/{id}/status/{device}", openVpnController)
                .action("setVpnDeviceStatus", HttpMethod.PUT)
                .name("dashboard.vpn.setVpnDeviceStatus.route");

        server // Similar to the one above - but used in a different place
                .uri("/api/dashboard/vpn/profiles/{id}/status-this", openVpnController)
                .action("setVpnThisDeviceStatus", HttpMethod.PUT)
                .name("dashboard.vpn.profile.status.device.set");// Called from squid error page

        server
                .uri("/api/dashboard/tor/config", anonymousController)
                .action("getConfig", HttpMethod.GET)
                .name("dashboard.tor.getConfig.route");

        server
                .uri("/api/dashboard/tor/config", anonymousController)
                .action("putConfig", HttpMethod.PUT)
                .name("dashboard.tor.putConfig.route");
    }

    void addDashboardAnonymousRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/tor/newidentity", anonymousController)
                .action("getNewTorIdentity", HttpMethod.PUT)
                .name("dashboard.tor.getNewTorIdentity.route");

        // ** Dashboard: Cloaking
    }
}
