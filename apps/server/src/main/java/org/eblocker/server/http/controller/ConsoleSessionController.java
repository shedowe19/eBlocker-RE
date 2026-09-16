// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.controller;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.http.security.ConsoleSessionService;
import org.eblocker.server.http.security.Credentials;
import org.eblocker.server.http.utils.ControllerUtils;
import org.restexpress.Request;
import org.restexpress.Response;

/** Dedicated browser session endpoints; every entry point validates its origin and session itself. */
@Singleton
public class ConsoleSessionController {
    private final ConsoleSessionService sessions;

    @Inject
    public ConsoleSessionController(ConsoleSessionService sessions) { this.sessions = sessions; }

    public ConsoleSessionService.Status bootstrap(Request request, Response response) {
        return sessions.bootstrap(request, response);
    }

    public ConsoleSessionService.Status login(Request request, Response response) {
        return sessions.login(request, response, request.getBodyAs(Credentials.class), ControllerUtils.getRequestIPAddress(request));
    }

    public ConsoleSessionService.Status renew(Request request, Response response) {
        return sessions.renew(request, response);
    }

    public void logout(Request request, Response response) { sessions.logout(request, response); }
}
