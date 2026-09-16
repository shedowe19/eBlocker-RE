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
import org.eblocker.server.http.controller.AuthenticationController;
import org.eblocker.server.http.controller.ConsoleSessionController;
import org.eblocker.server.http.security.SecurityProcessor;
import org.restexpress.RestExpress;

/** Route declarations owned by the authentication feature. */
@Singleton
final class AuthenticationRoutes {
    private final AuthenticationController authenticationController;
    private final ConsoleSessionController consoleSessionController;

    @Inject
    AuthenticationRoutes(AuthenticationController authenticationController, ConsoleSessionController consoleSessionController) {
        this.authenticationController = authenticationController;
        this.consoleSessionController = consoleSessionController;
    }

    void addAdminConsoleAuthenticationRoutes(RestExpress server) {
        // These dedicated entry points enforce TLS, same-origin and CSRF in the
        // session service, including the anonymous bootstrap and login states.
        server.uri("/api/adminconsole/authentication/session", consoleSessionController)
                .action("bootstrap", HttpMethod.POST).name("public.console.session.bootstrap");
        server.uri("/api/adminconsole/authentication/session/login", consoleSessionController)
                .action("login", HttpMethod.POST).name("public.console.session.login");
        server.uri("/api/adminconsole/authentication/session/renew", consoleSessionController)
                .action("renew", HttpMethod.POST).name("public.console.session.renew");
        server.uri("/api/adminconsole/authentication/session/logout", consoleSessionController)
                .action("logout", HttpMethod.POST).name("public.console.session.logout");
        server
                .uri("/api/adminconsole/authentication/token/{appContext}", authenticationController)
                .action("generateConsoleToken", HttpMethod.GET)
                .name("public.token.get.route");

        // FIXME hpe: has to be public, so we should reuse the existing one .. (and change path to /api/..)

        server
                .uri("/api/adminconsole/authentication/login/{appContext}", authenticationController)
                .action("login", HttpMethod.POST)
                .name("public.console.authentication.login.route");

        // Only accessible with valid token (pw must have been entered for adminconsole)

        server
                .uri("/api/adminconsole/authentication/renew/{appContext}", authenticationController)
                .action("renewToken", HttpMethod.GET)
                .name("adminconsole.authentication.renew.route");

        server
                .uri("/api/adminconsole/authentication/enable", authenticationController)
                .action("enable", HttpMethod.POST)
                .name("adminconsole.authentication.enable.route");

        // Only used from within console

        server
                .uri("/api/adminconsole/authentication/disable", authenticationController)
                .action("disable", HttpMethod.POST)
                .name("adminconsole.authentication.disable.route");

        // Must be available without authentication, if user lost password

        server
                .uri("/api/adminconsole/authentication/initiateReset", authenticationController)
                .action("initiateReset", HttpMethod.POST)
                .name("adminconsole.authentication.initiateReset.route")
                .flag(SecurityProcessor.NO_AUTHENTICATION_REQUIRED);

        // Must be available without authentication, if user lost password

        server
                .uri("/api/adminconsole/authentication/executeReset", authenticationController)
                .action("executeReset", HttpMethod.POST)
                .name("adminconsole.authentication.executeReset.route")
                .flag(SecurityProcessor.NO_AUTHENTICATION_REQUIRED);

        // Must be available without authentication, if user lost password

        server
                .uri("/api/adminconsole/authentication/cancelReset", authenticationController)
                .action("cancelReset", HttpMethod.POST)
                .name("adminconsole.authentication.cancelReset.route")
                .flag(SecurityProcessor.NO_AUTHENTICATION_REQUIRED);

        // Must be available without authentication, if user lost password

        server
                .uri("/api/adminconsole/authentication/wait", authenticationController)
                .action("passwordEntryInSeconds", HttpMethod.GET)
                .name("adminconsole.authentication.wait.route")
                .flag(SecurityProcessor.NO_AUTHENTICATION_REQUIRED);

        // ** New Adminconsole: General, to get ProductInfo, to get tos container
    }

    void addDashboardAuthenticationRoutes(RestExpress server) {
        server
                .uri("/api/token/{appContext}", authenticationController)
                .action("generateToken", HttpMethod.GET)
                .name("public.token.get.route");

        // Pausing the selected device:
    }

    void addAdminDashboardAuthenticationRoutes(RestExpress server) {
        server
                .uri("/api/admindashboard/authentication/renew/{appContext}", authenticationController)
                .action("renewToken", HttpMethod.GET)
                .name("admindashboard.authentication.renew.route");
    }
}
