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
import org.eblocker.server.http.controller.ParentalControlController;
import org.eblocker.server.http.controller.ParentalControlFilterListsController;
import org.eblocker.server.http.controller.UserController;
import org.eblocker.server.http.controller.FamilySettingsController;
import org.restexpress.RestExpress;

/** Route declarations owned by the family feature. */
@Singleton
final class FamilyRoutes {
    private final ParentalControlController parentalControlController;
    private final ParentalControlFilterListsController filterListsController;
    private final UserController userController;
    private final FamilySettingsController settingsController;

    @Inject
    FamilyRoutes(ParentalControlController parentalControlController,
            ParentalControlFilterListsController filterListsController,
            UserController userController, FamilySettingsController settingsController) {
        this.parentalControlController = parentalControlController;
        this.filterListsController = filterListsController;
        this.userController = userController;
        this.settingsController = settingsController;
    }

    void addParentalControlRoutes(RestExpress server) {
        server
                .uri("/api/squiderror/searchEngineConfig", parentalControlController)
                .action("getSearchEngineConfiguration", HttpMethod.GET)
                .name("errorpage.parentalcontrol.searchEngine.get");
    }

    void addControlBarUserRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/users/{id}/changepin", userController)
                .action("changePin", HttpMethod.POST)
                .name("controlbar.users.changePin.route");

        // ** New Controlbar: to get IP for Dashboard, Settings and Pause-link-dashboard
    }

    void addControlBarParentalControlRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/parentalcontrol/usage", parentalControlController)
                .action("startUsage", HttpMethod.POST)
                .name("controlbar.parentalcontrol.usage.start");

        server
                .uri("/api/controlbar/parentalcontrol/usage", parentalControlController)
                .action("stopUsage", HttpMethod.DELETE)
                .name("controlbar.parentalcontrol.usage.stop");

        server
                .uri("/api/controlbar/parentalcontrol/usage", parentalControlController)
                .action("getUsage", HttpMethod.GET)
                .name("controlbar.parentalcontrol.usage");

        // ** New Controlbar: Filter
    }

    void addControlBarFilterListsRoutes(RestExpress server) {
        server
                .uri("/api/filter/meta", filterListsController)
                .action("getFilterMetaData", HttpMethod.GET)
                .name("controlbar.filterlists.metadata.get.route");

        // ** New Controlbar: Ssl
    }

    void addAdminConsoleUserRoutes(RestExpress server) {
        server.uri("/api/adminconsole/users/{id}/settings", settingsController)
                .action("patchUser", HttpMethod.PATCH)
                .name("adminconsole.users.settings.patch");
        server.uri("/api/adminconsole/userprofiles/{id}/settings", settingsController)
                .action("patchProfile", HttpMethod.PATCH)
                .name("adminconsole.userprofiles.settings.patch");
        server.uri("/api/adminconsole/userprofiles/managed", settingsController)
                .action("createProfile", HttpMethod.POST)
                .name("adminconsole.userprofiles.managed.post");
        server.uri("/api/adminconsole/devices/{deviceId}/assignment", settingsController)
                .action("patchAssignment", HttpMethod.PATCH)
                .name("adminconsole.devices.assignment.patch");
        server
                .uri("/api/adminconsole/users", userController)
                .action("getUsers", HttpMethod.GET)
                .name("adminconsole.get.users.route");// Used in error page

        server
                .uri("/api/adminconsole/users", userController)
                .action("createUser", HttpMethod.POST)
                .name("adminconsole.parentalcontrol.store.user.route");

        server
                .uri("/api/adminconsole/users", userController)
                .action("updateUser", HttpMethod.PUT)
                .name("adminconsole.parentalcontrol.put.user.route");

        server
                .uri("/api/adminconsole/users/dashboard/update/{id}", userController)
                .action("updateUserDashboardView", HttpMethod.PUT)
                .name("adminconsole.parentalcontrol.update.user.dashboard.route");

        server
                .uri("/api/adminconsole/users/dashboard/updateall", userController)
                .action("updateDashboardViewOfAllDefaultSystemUsers", HttpMethod.PUT)
                .name("adminconsole.parentalcontrol.update.all.dashboard.route");

        server
                .uri("/api/adminconsole/users/{id}", userController)
                .action("deleteUser", HttpMethod.DELETE)
                .name("adminconsole.parentalcontrol.delete.user.route");

        server
                .uri("/api/adminconsole/users/all", userController)
                .action("deleteAllUsers", HttpMethod.POST)
                .name("adminconsole.parentalcontrol.delete.all.user.route");

        server
                .uri("/api/adminconsole/users/unique", userController)
                .action("isUnique", HttpMethod.GET)
                .name("adminconsole.parentalcontrol.user.unique.route");

        server
                .uri("/api/adminconsole/users/{id}/pin", userController)
                .action("setPin", HttpMethod.POST)
                .name("adminconsole.parentalcontrol.user.set.pin.route");

        server
                .uri("/api/adminconsole/users/{id}/pin", userController)
                .action("resetPin", HttpMethod.DELETE)
                .name("adminconsole.parentalcontrol.user.reset.pin.route");

        // ** New Adminconsole: UserProfiles

        server
                .uri("/api/adminconsole/userprofiles", parentalControlController)
                .action("storeNewProfile", HttpMethod.POST)
                .name("adminconsole.store.profile.route");

        server
                .uri("/api/adminconsole/userprofiles", parentalControlController)
                .action("getProfiles", HttpMethod.GET)
                .name("adminconsole.parentalcontrol.get.profiles.route");

        server
                .uri("/api/adminconsole/userprofiles", parentalControlController)
                .action("updateProfile", HttpMethod.PUT)
                .name("adminconsole.put.profile.route");

        server
                .uri("/api/adminconsole/userprofiles/{id}", parentalControlController)
                .action("deleteProfile", HttpMethod.DELETE)
                .name("adminconsole.delete.profile.route");

        server
                .uri("/api/adminconsole/userprofiles/all", parentalControlController)
                .action("deleteAllProfiles", HttpMethod.POST)
                .name("adminconsole.delete.all.profile.route");

        server
                .uri("/api/adminconsole/userprofile/bonustime/{id}", parentalControlController)
                .action("addOnlineTimeForToday", HttpMethod.POST)
                .name("adminconsole.userprofile.set.bonustime.route");

        server
                .uri("/api/adminconsole/userprofile/bonustime/{id}", parentalControlController)
                .action("resetBonusTimeForToday", HttpMethod.DELETE)
                .name("adminconsole.userprofile.delete.bonustime.route");

        // ** New Adminconsole: Devices
    }

    void addAdminConsoleFilterListsRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/filterlists", filterListsController)
                .action("getFilterLists", HttpMethod.GET)
                .name("adminconsole.controlbar.filterlists.get.route");

        server
                .uri("/api/adminconsole/filterlists/{id}/domains", filterListsController)
                .action("getFilterListDomains", HttpMethod.GET)
                .name("adminconsole.parentalcontrol.filterlists.getdomains.route");

        server
                .uri("/api/adminconsole/filterlists/{id}/update", filterListsController)
                .action("updateFilterList", HttpMethod.PUT)
                .name("adminconsole.parentalcontrol.filterlists.update.route");

        server
                .uri("/api/adminconsole/filterlists/{id}", filterListsController)
                .action("deleteFilterList", HttpMethod.DELETE)
                .name("adminconsole.parentalcontrol.filterlists.delete.route");

        server
                .uri("/api/adminconsole/filterlists", filterListsController)
                .action("createFilterList", HttpMethod.POST)
                .name("adminconsole.parentalcontrol.filterlists.create.route");

        server
                .uri("/api/adminconsole/filterlists/unique", filterListsController)
                .action("isUnique", HttpMethod.GET)
                .name("adminconsole.parentalcontrol.filterlists.unique.route");

        server
                .uri("/api/adminconsole/filterlists/meta", filterListsController)
                .action("getFilterMetaData", HttpMethod.GET)
                .name("adminconsole.controlbar.filterlists.metadata.get.route");

        // ** New Adminconsole: Cloaking
    }

    void addFilterListsRoutes(RestExpress server) {
        server
                .uri("/filterlists", filterListsController)
                .action("getFilterLists", HttpMethod.GET)
                .name("public.controlbar.filterlists.get.route");// Public since used by squid error page
    }

    void addDashboardParentalControlRoutes(RestExpress server) {
        server
                .uri("/api/parentalcontrol/usage", parentalControlController)
                .action("startUsage", HttpMethod.POST)
                .name("dashboard.parentalcontrol.usage.start");

        server
                .uri("/api/parentalcontrol/usage", parentalControlController)
                .action("stopUsage", HttpMethod.DELETE)
                .name("dashboard.parentalcontrol.usage.stop");

        server
                .uri("/api/parentalcontrol/usage", parentalControlController)
                .action("getUsage", HttpMethod.GET)
                .name("dashboard.parentalcontrol.usage");

        server
                .uri("/api/parentalcontrol/usage/{id}", parentalControlController)
                .action("getUsageByUserId", HttpMethod.GET)
                .name("dashboard.parentalcontrol.usage.by.id");

        server
                .uri("/api/dashboard/userprofiles", parentalControlController)
                .action("getProfiles", HttpMethod.GET)
                .name("dashboard.parentalcontrol.get.profiles.route");

        server
                .uri("/api/dashboard/searchEngineConfig", parentalControlController)
                .action("getSearchEngineConfiguration", HttpMethod.GET)
                .name("dashboard.parentalcontrol.searchEngine.get");
    }

    void addDashboardParentalControlSetMaxUsageRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/userprofile/maxusage/{id}", parentalControlController)
                .action("setMaxUsage", HttpMethod.POST)
                .name("dashboard.userprofile.set.maxusage.route");

        server
                .uri("/api/dashboard/userprofile/contentfilter/{id}", parentalControlController)
                .action("setContentFilter", HttpMethod.POST)
                .name("dashboard.userprofile.set.contentfilter.route");

        server
                .uri("/api/dashboard/userprofile/inetnetaccess/{id}", parentalControlController)
                .action("setInternetAccessStatus", HttpMethod.POST)
                .name("dashboard.userprofile.set.internetaccess.route");

        server
                .uri("/api/dashboard/userprofile/inetnetaccess/{id}", parentalControlController)
                .action("getInternetAccessStatus", HttpMethod.GET)
                .name("dashboard.userprofile.get.internetaccess.route");

        server
                .uri("/api/dashboard/userprofile/bonustime/{id}", parentalControlController)
                .action("addOnlineTimeForToday", HttpMethod.POST)
                .name("dashboard.userprofile.set.bonustime.route");

        server
                .uri("/api/dashboard/userprofile/bonustime/{id}", parentalControlController)
                .action("resetBonusTimeForToday", HttpMethod.DELETE)
                .name("dashboard.userprofile.delete.bonustime.route");
    }

    void addDashboardUserRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/users", userController)
                .action("getUsers", HttpMethod.GET)
                .name("dashboard.users.getUsers.route");
    }

    void addDashboardUserChangePinRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/users/{id}/changepin", userController)
                .action("changePin", HttpMethod.POST)
                .name("dashboard.users.changePin.route");

        // eBlocker mobile
    }

    void addDashboardFilterListsRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/filterlists", filterListsController)
                .action("getFilterLists", HttpMethod.GET)
                .name("dashboard.filterlists.get.route");

        // Domain Recording
    }
}
