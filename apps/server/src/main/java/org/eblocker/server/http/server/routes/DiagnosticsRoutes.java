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
import org.eblocker.server.http.controller.DoctorController;
import org.eblocker.server.http.controller.DomainRecorderController;
import org.eblocker.server.http.controller.EventController;
import org.eblocker.server.http.controller.RecordingController;
import org.eblocker.server.http.controller.TransactionRecorderController;
import org.eblocker.server.http.controller.boot.DiagnosticsReportController;
import org.eblocker.server.http.security.DashboardAuthorizationProcessor;
import org.restexpress.RestExpress;

/** Route declarations owned by the diagnostics feature. */
@Singleton
final class DiagnosticsRoutes {
    private final DiagnosticsReportController diagnosticsReportController;
    private final RecordingController recordingController;
    private final TransactionRecorderController transactionRecorderController;
    private final EventController eventController;
    private final DomainRecorderController domainRecorderController;
    private final DoctorController doctorController;

    @Inject
    DiagnosticsRoutes(DiagnosticsReportController diagnosticsReportController,
            RecordingController recordingController,
            TransactionRecorderController transactionRecorderController,
            EventController eventController,
            DomainRecorderController domainRecorderController,
            DoctorController doctorController) {
        this.diagnosticsReportController = diagnosticsReportController;
        this.recordingController = recordingController;
        this.transactionRecorderController = transactionRecorderController;
        this.eventController = eventController;
        this.domainRecorderController = domainRecorderController;
        this.doctorController = doctorController;
    }

    void addAdminConsoleEventRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/events", eventController)
                .action("getEvents", HttpMethod.GET)
                .name("adminconsole.events.get.route");

        server
                .uri("/api/adminconsole/events/{mode}", eventController)
                .action("deleteSeveralEvents", HttpMethod.DELETE)
                .name("adminconsole.events.delete.route");

        // ** New Adminconsole: System Diagnostics Report

        server
                .uri("/api/adminconsole/diagnostics/report", diagnosticsReportController)
                .action("startReport", HttpMethod.POST)
                .name("adminconsole.diagnostics.report.create.route");

        server
                .uri("/api/adminconsole/diagnostics/report", diagnosticsReportController)
                .action("getReportStatus", HttpMethod.GET)
                .name("adminconsole.diagnostics.report.status.route");

        server
                .uri("/api/adminconsole/diagnostics/download", diagnosticsReportController)
                .action("getReport", HttpMethod.GET)
                .name("adminconsole.diagnostics.report.download.route")
                .noSerialization();

        // ** New Adminconsole: System Factory reset
    }

    void addAdminConsoleRecordingRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/recording/toggle", recordingController)
                .action("recordingStartStop", HttpMethod.POST)
                .name("adminconsole.recording.start.stop");

        server
                .uri("/api/adminconsole/recording/status", recordingController)
                .action("getRecordingStatus", HttpMethod.GET)
                .name("adminconsole.recording.get.status");

        server
                .uri("/api/adminconsole/recording/result", recordingController)
                .action("getRecordedDomainList", HttpMethod.GET)
                .name("adminconsole.recording.get.recorded.domain.list");

        // ** New Adminconsole: OPEN VPN (eBlocker mobile)
    }

    void addAdminConsoleTransactionRecorderRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/recorder", transactionRecorderController)
                .action("start", HttpMethod.POST)
                .name("adminconsole.transactionRecorder.start.route");

        server
                .uri("/api/adminconsole/recorder", transactionRecorderController)
                .action("stop", HttpMethod.DELETE)
                .name("adminconsole.transactionRecorder.stop.route");

        server
                .uri("/api/adminconsole/recorder", transactionRecorderController)
                .action("info", HttpMethod.GET)
                .name("adminconsole.transactionRecorder.info.route");

        server
                .uri("/api/adminconsole/recorder/results", transactionRecorderController)
                .action("getAll", HttpMethod.GET)
                .name("adminconsole.transactionRecorder.getall.route");

        server
                .uri("/api/adminconsole/recorder/whatifmode", transactionRecorderController)
                .action("getWhatIfMode", HttpMethod.GET)
                .name("adminconsole.transactionRecorder.whatifmode.get.route");

        server
                .uri("/api/adminconsole/recorder/whatifmode", transactionRecorderController)
                .action("setWhatIfMode", HttpMethod.PUT)
                .name("adminconsole.transactionRecorder.whatifmode.put.route");

        server
                .uri("/api/adminconsole/recorder/results/csv", transactionRecorderController)
                .action("getAllAsCSV", HttpMethod.GET)
                .name("adminconsole.transactionRecorder.getallascsv.route")
                .noSerialization();

        server
                .uri("/api/adminconsole/recorder/results/{domain}", transactionRecorderController)
                .action("get", HttpMethod.GET)
                .name("adminconsole.transactionRecorder.get.route");

        // ** New Adminconsole: Filter
    }

    void addAdminConsoleDoctorRoutes(RestExpress server) {
        server
                .uri("/api/adminconsole/doctor/diagnosis", doctorController)
                .action("runDiagnosis", HttpMethod.GET)
                .name("adminconsole.doctor.diagnosis.get");
    }

    void addTransactionRecorderRoutes(RestExpress server) {
        //TODO: Should not be public
        server
                .uri("/recorder/results/csv", transactionRecorderController)
                .action("getAllAsCSV", HttpMethod.GET)
                .name("public.transactionRecorder.getallascsv.route")
                .noSerialization();
    }

    void addDashboardDomainRecorderRoutes(RestExpress server) {
        server
                .uri("/api/dashboard/domain/recorder/{deviceId}", domainRecorderController)
                .action("getRecordedDomains", HttpMethod.GET)
                .name("dashboard.domainRecorder.get.route")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);

        server
                .uri("/api/dashboard/domain/recorder/{deviceId}", domainRecorderController)
                .action("resetRecording", HttpMethod.DELETE)
                .name("dashboard.domainRecorder.reset.route")
                .flag(DashboardAuthorizationProcessor.VERIFY_DEVICE_ID);
    }
}
