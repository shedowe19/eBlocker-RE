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
import org.eblocker.server.http.controller.ControlBarController;
import org.eblocker.server.http.controller.CustomerInfoController;
import org.eblocker.server.http.controller.DeviceRegistrationController;
import org.eblocker.server.http.controller.FeatureController;
import org.eblocker.server.http.controller.MessageCenterController;
import org.eblocker.server.http.controller.PageContextController;
import org.eblocker.server.http.controller.ProductMigrationController;
import org.eblocker.server.http.controller.RedirectController;
import org.eblocker.server.http.controller.ReminderController;
import org.eblocker.server.http.controller.SetupWizardController;
import org.eblocker.server.http.controller.SplashController;
import org.eblocker.server.http.controller.TosController;
import org.restexpress.RestExpress;

/** Route declarations owned by the experience feature. */
@Singleton
final class ExperienceRoutes {
    private final RedirectController redirectController;
    private final DeviceRegistrationController deviceRegistrationController;
    private final SetupWizardController setupWizardController;
    private final MessageCenterController messageCenterController;
    private final ControlBarController controlBarController;
    private final SplashController splashController;
    private final PageContextController pageContextController;
    private final ReminderController reminderController;
    private final ProductMigrationController productMigrationController;
    private final CustomerInfoController customerInfoController;
    private final FeatureController featureController;
    private final TosController tosController;

    @Inject
    ExperienceRoutes(RedirectController redirectController,
            DeviceRegistrationController deviceRegistrationController,
            SetupWizardController setupWizardController,
            MessageCenterController messageCenterController,
            ControlBarController controlBarController,
            SplashController splashController,
            PageContextController pageContextController,
            ReminderController reminderController,
            ProductMigrationController productMigrationController,
            CustomerInfoController customerInfoController,
            FeatureController featureController,
            TosController tosController) {
        this.redirectController = redirectController;
        this.deviceRegistrationController = deviceRegistrationController;
        this.setupWizardController = setupWizardController;
        this.messageCenterController = messageCenterController;
        this.controlBarController = controlBarController;
        this.splashController = splashController;
        this.pageContextController = pageContextController;
        this.reminderController = reminderController;
        this.productMigrationController = productMigrationController;
        this.customerInfoController = customerInfoController;
        this.featureController = featureController;
        this.tosController = tosController;
    }

    void addRedirectRoutes(RestExpress server) {
        //
        // Redirects must be public.
        // They are protected by the transaction UUID.
        //
        server
                .uri("/redirect/{decision}/{uuid}", redirectController)
                .method(HttpMethod.GET)
                .name("public.redirect.route");

        server
                .uri("/redirect/{decision}/{uuid}", redirectController)
                .method(HttpMethod.PUT)
                .name("public.redirect.store.route");

        server
                //.uri("/redirect/{domain}", redirectController)
                //.method(HttpMethod.DELETE)
                .uri("/redirect-delete/{domain}", redirectController)
                .action("delete", HttpMethod.GET)  //  Easier to test
                .name("public.redirect.delete.route");

        // Used by 1px.svg

        server.uri("/redirect/prepare", redirectController)
                .action("prepare", HttpMethod.GET)
                .name("public.redirect.prepare.route");
    }

    void addDeviceRegistrationRoutes(RestExpress server) {
        // public (required to load the console)
        server
                .uri("/registration", deviceRegistrationController)
                .action("registrationStatus", HttpMethod.GET)
                .name("public.registration.status.route");
    }

    void addUserMessagesRoutes(RestExpress server) {
        // controlbar and dashboard
        server
                .uri("/messages", messageCenterController)
                .action("getMessages", HttpMethod.GET)
                .name("public.messages.get.route");

        // public (loaded early in the controlbar where no token verification takes place yet)

        server
                .uri("/messages/count", messageCenterController)
                .action("getNumberOfMessages", HttpMethod.GET)
                .name("public.messages.count.route");

        // controlbar and dashboard!

        server
                .uri("/api/icon/state", controlBarController)
                .action("getIconState", HttpMethod.GET)
                .name("public.messages.iconState.route");

        // controlbar and dashboard

        server
                .uri("/messages/action", messageCenterController)
                .action("executeMessageAction", HttpMethod.POST)
                .name("public.messages.action.route");

        // controlbar and dashboard

        server
                .uri("/messages/hide", messageCenterController)
                .action("hideMessage", HttpMethod.POST)
                .name("public.messages.hide.route");

        // controlbar and dashboard

        server
                .uri("/messages/donotshowagain", messageCenterController)
                .action("setDoNotShowAgain", HttpMethod.PUT)
                .name("public.messages.donotshowagain.put.route");
    }

    void addControlBarControlBarRoutes(RestExpress server) {
        server
                .uri("/controlbar/console/ip", controlBarController)
                .action("getConsoleIp", HttpMethod.GET)
                .name("public.controlbar.console.ip.route");

        server
                .uri("/controlbar/deviceRestrictions", controlBarController)
                .action("getDeviceRestrictions", HttpMethod.GET)
                .name("public.controlbar.device.restrictions.route");

        // ** New Controlbar, with 'api' prefix
        // ** New Controlbar: Trackers / Ads
    }

    void addControlBarControlBarGetDeviceRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/device", controlBarController)
                .action("getDevice", HttpMethod.GET)
                .name("controlbar.device.getDevice.route");
    }

    void addControlBarControlBarGetUsersRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/users", controlBarController)
                .action("getUsers", HttpMethod.GET)
                .name("controlbar.users.getUsers.route");

        server
                .uri("/api/controlbar/users/operatinguser", controlBarController)
                .action("setOperatingUser", HttpMethod.PUT)
                .name("controlbar.users.setOperatingUser.route");
    }

    void addControlBarControlBarGetConsoleIpRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/console/ip", controlBarController)
                .action("getConsoleIp", HttpMethod.GET)
                .name("controlbar.console.getConsoleIp.route");

        // ** New Controlbar: General, to get ProductInfo

        server
                .uri("/api/controlbar/registration", deviceRegistrationController)
                .action("registrationStatus", HttpMethod.GET)
                .name("controlbar.registration.status.route");

        // ** New Controlbar: IP-Anon
    }

    void addControlBarMessageCenterRoutes(RestExpress server) {
        server
                .uri("/api/messages/action", messageCenterController)
                .action("executeMessageAction", HttpMethod.POST)
                .name("controlbar.messages.action.route");

        server
                .uri("/api/messages/hide", messageCenterController)
                .action("hideMessage", HttpMethod.POST)
                .name("controlbar.messages.hide.route");

        server
                .uri("/api/messages", messageCenterController)
                .action("getMessages", HttpMethod.GET)
                .name("controlbar.messages.get.route");

        server
                .uri("/api/messages/donotshowagain", messageCenterController)
                .action("setDoNotShowAgain", HttpMethod.PUT)
                .name("controlbar.messages.donotshowagain.put.route");

        // ** New Controlbar: OnlineTime
    }

    void addAdminConsoleDeviceRegistrationRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/registration", deviceRegistrationController)
                .action("registrationStatus", HttpMethod.GET)
                .name("adminconsole.registration.status.route");

        server
                .uri("/api/adminconsole/tos", tosController)
                .action("getTos", HttpMethod.GET)
                .name("adminconsole.license.get.tos.route");

        // ** New Adminconsole: license registration

        server
                .uri("/api/adminconsole/registration", deviceRegistrationController)
                .action("register", HttpMethod.POST)
                .name("adminconsole.registration.register.route");

        server
                .uri("/api/adminconsole/registration", deviceRegistrationController)
                .action("resetRegistration", HttpMethod.DELETE)
                .name("adminconsole.registration.reset.route");

        // ** New Adminconsole: upsell info

        server
                .uri("/api/adminconsole/upsellInfo/{feature}", productMigrationController)
                .action("getUpsellInfo", HttpMethod.GET)
                .name("adminconsole.productmigration.getUpsellInfo");

        // ** New Adminconsole: license setup wizard

        server
                .uri("/api/adminconsole/setup/info", setupWizardController)
                .action("getInfo", HttpMethod.GET)
                .name("adminconsole.setup.info.route");

        // ** New Adminconsole: update
    }

    void addAdminConsoleFeatureRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/compressionmode", featureController)
                .action("getCompressionMode", HttpMethod.GET)
                .name("adminconsole.features.compressionmode");

        server
                .uri("/api/adminconsole/compressionmode", featureController)
                .action("setCompressionMode", HttpMethod.PUT)
                .name("adminconsole.features.compressionmode");

        // ** New Adminconsole: System Language
    }

    void addAdminConsoleCustomerInfoRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/customerInfo", customerInfoController)
                .action("get", HttpMethod.GET)
                .name("adminconsole.customerInfo.get.route");

        server
                .uri("/api/adminconsole/customerInfo", customerInfoController)
                .action("save", HttpMethod.POST)
                .name("adminconsole.customerInfo.save.route");

        server
                .uri("/api/adminconsole/customerInfo", customerInfoController)
                .action("delete", HttpMethod.DELETE)
                .name("adminconsole.customerInfo.delete.route");

        // ** New Adminconsole: Analysis Tool (Filter / HTTP Expert Tools)
    }

    void addAdminConsoleControlBarRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/console/ip", controlBarController)
                .action("getConsoleIp", HttpMethod.GET)
                .name("public.adminconsole.console.ip.route");

        // ** New Adminconsole: Splash settings:

        server
                .uri("/api/adminconsole/splash", splashController)
                .action("get", HttpMethod.GET)
                .name("adminconsole.splash.get");

        server
                .uri("/api/adminconsole/splash", splashController)
                .action("set", HttpMethod.POST)
                .name("adminconsole.splash.update");

        // ** new adminconsole: tasks
    }

    void addAdvicePageControlBarRoutes(RestExpress server) {
        // ** advice page: to get IP for Dashboard, Settings and Pause-link-dashboard
        server
                .uri("/api/advice/console/ip", controlBarController)
                .action("getConsoleIp", HttpMethod.GET)
                .name("advice.console.getConsoleIp.route");

        // ** advice page: General, to get ProductInfo

        server
                .uri("/api/advice/registration", deviceRegistrationController)
                .action("registrationStatus", HttpMethod.GET)
                .name("advice.registration.status.route");

        // ** advice page: device
    }

    void addDashboardControlBarRoutes(RestExpress server) {
        server
                .uri("/api/userprofile", controlBarController)
                .action("getUserProfile", HttpMethod.GET)
                .name("dashboard.userprofile.route");
    }

    void addDashboardDeviceRegistrationRoutes(RestExpress server) {
        server
                .uri("/api/registrationdata", deviceRegistrationController)
                .action("registrationStatus", HttpMethod.GET)
                .name("dashboard.registration.status.route");

        // Dashboard card data
    }

    void addDashboardControlBarSetOperatingUserRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/users/operatinguser", controlBarController)
                .action("setOperatingUser", HttpMethod.PUT)
                .name("dashboard.users.setOperatingUser.route");
    }

    void addDashboardDeviceRegistrationRegistrationStatusRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/registration", deviceRegistrationController)
                .action("registrationStatus", HttpMethod.GET)
                .name("dashboard.registration.status.route");

        // ** Dashboard IP-Anon
    }

    void addPageContextRoutes(RestExpress server) {
        server
                .uri("/pagecontext/{id}/top", pageContextController)
                .action("reportTopContent", HttpMethod.POST)
                .name("public.pagecontext.top");
    }

    void addReminderRoutes(RestExpress server) {
        server
                .uri("/api/advice/reminder", reminderController)
                .action("getExpirationDate", HttpMethod.GET)
                .name("advice.reminder.get");

        server
                .uri("/api/advice/reminder", reminderController)
                .action("setNextReminder", HttpMethod.POST)
                .name("advice.reminder.post");
    }
}
