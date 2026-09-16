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
import org.eblocker.server.http.controller.ConfigurationBackupController;
import org.eblocker.server.http.controller.FactoryResetController;
import org.eblocker.server.http.controller.FeatureToggleController;
import org.eblocker.server.http.controller.LanguageController;
import org.eblocker.server.http.controller.SettingsController;
import org.eblocker.server.http.controller.TasksController;
import org.eblocker.server.http.controller.TimestampController;
import org.eblocker.server.http.controller.TimezoneController;
import org.eblocker.server.http.controller.UpdateController;
import org.eblocker.server.http.controller.boot.SystemStatusController;
import org.restexpress.RestExpress;

/** Route declarations owned by the system feature. */
@Singleton
final class SystemRoutes {
    private final SystemStatusController systemStatusController;
    private final FactoryResetController factoryResetController;
    private final UpdateController updateController;
    private final LanguageController languageController;
    private final TimezoneController timezoneController;
    private final TimestampController timestampController;
    private final SettingsController settingsController;
    private final ConfigurationBackupController configBackupController;
    private final TasksController tasksController;
    private final FeatureToggleController featureToggleController;

    @Inject
    SystemRoutes(SystemStatusController systemStatusController,
            FactoryResetController factoryResetController,
            UpdateController updateController,
            LanguageController languageController,
            TimezoneController timezoneController,
            TimestampController timestampController,
            SettingsController settingsController,
            ConfigurationBackupController configBackupController,
            TasksController tasksController,
            FeatureToggleController featureToggleController) {
        this.systemStatusController = systemStatusController;
        this.factoryResetController = factoryResetController;
        this.updateController = updateController;
        this.languageController = languageController;
        this.timezoneController = timezoneController;
        this.timestampController = timestampController;
        this.settingsController = settingsController;
        this.configBackupController = configBackupController;
        this.tasksController = tasksController;
        this.featureToggleController = featureToggleController;
    }

    void addTimestampRoutes(RestExpress server) {
        server
                .uri("/api/localtimestamp", timestampController)
                .action("getLocalTimestamp", HttpMethod.GET)
                .name("dashboard.timestamp.route");
    }

    void addControlBarTimestampRoutes(RestExpress server) {
        server
                .uri("/api/controlbar/localtimestamp", timestampController)
                .action("getLocalTimestamp", HttpMethod.GET)
                .name("controlbar.timestamp.route");
    }

    void addAdminConsoleSettingsRoutes(RestExpress server) {
        // ** New Adminconsole: Settings status routes
        server
                .uri("/api/adminconsole/settings", settingsController)
                .action("getLocaleSettings", HttpMethod.GET)
                .name("public.locale.get.route"); // public, so that private window can load settings

        server
                .uri("/api/adminconsole/settings", settingsController)
                .action("setLocale", HttpMethod.PUT)
                .name("adminconsole.locale.put.route");

        // ** New Adminconsole: System status routes

        server
                .uri("/api/adminconsole/systemstatus/shutdown", systemStatusController)
                .action("shutdown", HttpMethod.POST)
                .name("adminconsole.systemstatus.shutdown");

        server
                .uri("/api/adminconsole/systemstatus/reboot", systemStatusController)
                .action("reboot", HttpMethod.POST)
                .name("adminconsole.systemstatus.reboot");

        //
        // Must be public routes, as these are used, before the system is really started.
        // In particular, before the DB and the admin password are available.
        // So it's impossible to authenticate the user!
        //
        // The shutdown/reboot APIs MUST check that the system is not fully booted,
        // but is in ERROR state instead.
        // If not in ERROR state, the requests MUST be rejected.
        //

        server
                .uri("/api/adminconsole/systemstatus", systemStatusController)
                .action("get", HttpMethod.GET)
                .name("public.adminconsole.systemstatus.get");

        server
                .uri("/api/adminconsole/systemstatus/shutdown/onerror", systemStatusController)
                .action("shutdownOnError", HttpMethod.POST)
                .name("public.adminconsole.systemstatus.shutdown");

        server
                .uri("/api/adminconsole/systemstatus/reboot/onerror", systemStatusController)
                .action("rebootOnError", HttpMethod.POST)
                .name("public.adminconsole.systemstatus.reboot");
    }

    void addAdminConsoleUpdateRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/updates/status", updateController)
                .action("getUpdatingStatus", HttpMethod.GET)
                .name("adminconsole.updates.status.get.route");

        server
                .uri("/api/adminconsole/updates/autoupdate", updateController)
                .action("getAutoUpdateInformation", HttpMethod.GET)
                .name("adminconsole.updates.autoupdate.get.route");

        server
                .uri("/api/adminconsole/updates/automaticUpdatesStatus", updateController)
                .action("setAutomaticUpdatesStatus", HttpMethod.POST)
                .name("adminconsole.updates.autoupdate.set.route");

        server
                .uri("/api/adminconsole/updates/status", updateController)
                .action("setUpdatingStatus", HttpMethod.POST)
                .name("adminconsole.updates.status.set.route");
        //        server
        //            .uri("/api/adminconsole/updates/download", updateController)
        //            .action("downloadUpdates", HttpMethod.GET)
        //            .name("adminconsole.updates.download.set.route");

        server
                .uri("/api/adminconsole/updates/check", updateController)
                .action("getUpdatesCheckStatus", HttpMethod.GET)
                .name("adminconsole.updates.check.set.route");

        server
                .uri("/api/adminconsole/updates/automaticUpdatesConfig", updateController)
                .action("setAutomaticUpdatesConfig", HttpMethod.POST)
                .name("adminconsole.updates.autoupdate.config.set.route");

        // ** New Adminconsole: Users
    }

    void addAdminConsoleLanguageRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/language", languageController)
                .action("setLanguage", HttpMethod.POST)
                .name("adminconsole.language.set.route");

        // ** New Adminconsole: System Timezone

        server
                .uri("/api/adminconsole/timezone/continents", timezoneController)
                .action("getTimezoneCategories", HttpMethod.GET)
                .name("adminconsole.timezone.continents.get.route");

        server
                .uri("/api/adminconsole/timezone/continent/countries", timezoneController)
                .action("getTimeZoneStringsForCategory", HttpMethod.PUT)
                .name("adminconsole.timezone.continents.put.countries.route");

        // ** New Adminconsole: System Events
    }

    void addAdminConsoleFactoryResetRoutes(RestExpress server) {
        server.uri("/api/adminconsole/factoryreset", factoryResetController)
                .action("factoryReset", HttpMethod.GET)
                .name("adminconsole.system.factoryreset.route");

        // ** New Adminconsole: ParentalControl filterlists
    }

    void addAdminConsoleTasksRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/tasks/log", tasksController)
                .action("getLog", HttpMethod.GET)
                .name("adminconsole.tasks.log");

        server
                .uri("/api/adminconsole/tasks/viewConfig", tasksController)
                .action("getViewConfig", HttpMethod.GET)
                .name("adminconsole.tasks.view.config.get");

        server
                .uri("/api/adminconsole/tasks/viewConfig", tasksController)
                .action("setViewConfig", HttpMethod.PUT)
                .name("adminconsole.tasks.view.config.set");

        server
                .uri("/api/adminconsole/tasks/stats", tasksController)
                .action("getPoolStats", HttpMethod.GET)
                .name("adminconsole.tasks.stats.get");
    }

    void addDashboardSettingsRoutes(RestExpress server) {
        server
                .uri("/api/settings", settingsController)
                .action("getLocaleSettings", HttpMethod.GET)
                .name("public.dashboard.settings.get.route");

        server
                .uri("/api/settings/timezone", settingsController)
                .action("setTimeZone", HttpMethod.PUT)
                .name("dashboard.locale.put.timezone.route");
    }

    void addConfigurationBackupRoutes(RestExpress server) {
        server
                .uri("/api/configbackup/export", configBackupController)
                .action("exportConfiguration", HttpMethod.POST)
                .name("adminconsole.configbackup.export");

        server
                .uri("/api/configbackup/download/{configBackupFileReference}", configBackupController)
                .action("downloadConfiguration", HttpMethod.GET)
                .name("adminconsole.configbackup.download")
                .noSerialization();

        server
                .uri("/api/configbackup/upload", configBackupController)
                .action("uploadConfiguration", HttpMethod.PUT)
                .name("adminconsole.configbackup.upload");

        server
                .uri("/api/configbackup/verify", configBackupController)
                .action("verifyConfiguration", HttpMethod.POST)
                .name("adminconsole.configbackup.verify");

        server
                .uri("/api/configbackup/import", configBackupController)
                .action("importConfiguration", HttpMethod.POST)
                .name("adminconsole.configbackup.import");
    }

    void addFeatureToggleRoutes(RestExpress server) {
        server
                .uri("/api/featuretoggle/{name}", featureToggleController)
                .action("getFeatureToggle", HttpMethod.GET)
                .name("adminconsole.featuretoggle.get");
    }
}
