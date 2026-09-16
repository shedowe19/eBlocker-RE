// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.http.controller.impl;

import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserRole;
import org.eblocker.server.common.page.PageContextStore;
import org.eblocker.server.common.session.Session;
import org.eblocker.server.common.session.SessionStore;
import org.eblocker.server.http.security.AppContext;
import org.eblocker.server.http.security.SecurityProcessor;
import org.eblocker.server.http.service.DeviceService;
import org.eblocker.server.http.service.ParentalControlSearchEngineConfigService;
import org.eblocker.server.http.service.ParentalControlService;
import org.eblocker.server.http.service.ParentalControlUsageService;
import org.eblocker.server.http.service.UserService;
import org.junit.Before;
import org.junit.Test;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ForbiddenException;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class ParentalControlManagementAuthorizationTest {
    private ParentalControlControllerImpl controller;
    private ParentalControlService profiles;
    private ParentalControlUsageService usage;
    private DeviceService devices;
    private UserService users;
    private UserModule operatingUser;
    private UserModule child;
    private Request request;
    private Response response;
    private SessionStore sessions;

    @Before
    public void setUp() {
        profiles = mock(ParentalControlService.class);
        usage = mock(ParentalControlUsageService.class);
        devices = mock(DeviceService.class);
        users = mock(UserService.class);
        sessions = mock(SessionStore.class);
        Session session = mock(Session.class);
        when(sessions.getSession(any())).thenReturn(session);
        when(session.getDeviceId()).thenReturn("requesting-device");
        Device device = new Device();
        device.setOperatingUser(11);
        when(devices.getDeviceById("requesting-device")).thenReturn(device);
        operatingUser = mock(UserModule.class);
        when(operatingUser.getUserRole()).thenReturn(UserRole.PARENT);
        when(users.getUserById(11)).thenReturn(operatingUser);
        child = mock(UserModule.class);
        when(child.getAssociatedProfileId()).thenReturn(7);
        when(child.getUserRole()).thenReturn(UserRole.CHILD);
        when(users.getUsers(false)).thenReturn(Collections.singletonList(child));
        request = mock(Request.class);
        response = mock(Response.class);
        when(request.getHeader("id")).thenReturn("7");
        when(request.getBodyAs(Integer.class)).thenReturn(10);
        when(request.getBodyAs(Boolean.class)).thenReturn(true);
        controller = new ParentalControlControllerImpl(sessions, mock(PageContextStore.class), profiles, usage,
                mock(ParentalControlSearchEngineConfigService.class), devices, users);
    }

    private void invokeEveryManagementWrite() {
        controller.setMaxUsage(request, response);
        controller.setContentFilter(request, response);
        controller.setInternetAccessStatus(request, response);
        controller.addOnlineTimeForToday(request, response);
        controller.resetBonusTimeForToday(request, response);
    }

    private void assertAllWritesForbidden() {
        assertThrows(ForbiddenException.class, () -> controller.setMaxUsage(request, response));
        assertThrows(ForbiddenException.class, () -> controller.setContentFilter(request, response));
        assertThrows(ForbiddenException.class, () -> controller.setInternetAccessStatus(request, response));
        assertThrows(ForbiddenException.class, () -> controller.addOnlineTimeForToday(request, response));
        assertThrows(ForbiddenException.class, () -> controller.resetBonusTimeForToday(request, response));
        verifyNoInteractions(profiles, usage);
    }

    @Test
    public void authenticatedAdministratorsCanManageProfilesWithoutDeviceRoleRestrictions() {
        for (AppContext context : new AppContext[]{AppContext.ADMINCONSOLE, AppContext.ADMINDASHBOARD, AppContext.CONSOLE}) {
            when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(context);
            invokeEveryManagementWrite();
        }
        verify(usage, times(3)).addBonusTimeForToday(7, 10);
        verify(profiles, times(12)).changeProfileSettings(eq(7), any());
        verifyNoInteractions(devices, users);
    }

    @Test
    public void parentOnTheRequestingDeviceCanManageChildProfiles() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.DASHBOARD);
        invokeEveryManagementWrite();
        verify(usage).addBonusTimeForToday(7, 10);
        verify(profiles, times(4)).changeProfileSettings(eq(7), any());
        verify(users, times(5)).getUserById(11);
    }

    @Test
    public void childrenAndOtherDashboardUsersCannotChangeAnyManagementSetting() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.DASHBOARD);
        for (UserRole role : new UserRole[]{UserRole.CHILD, UserRole.OTHER}) {
            when(operatingUser.getUserRole()).thenReturn(role);
            assertAllWritesForbidden();
        }
    }

    @Test
    public void parentCannotTargetAnotherParentsProfileOrAnUnassignedTemplate() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.DASHBOARD);
        when(child.getUserRole()).thenReturn(UserRole.PARENT);
        assertAllWritesForbidden();
        when(users.getUsers(false)).thenReturn(Collections.emptyList());
        assertAllWritesForbidden();
    }

    @Test
    public void absentContextSessionOrOperatingUserDoesNotGrantManagementAccess() {
        assertAllWritesForbidden();
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.DASHBOARD);
        when(sessions.getSession(any())).thenReturn(null);
        assertAllWritesForbidden();
    }

    @Test
    public void systemUsersCannotAcquireParentManagementPrivileges() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.DASHBOARD);
        when(operatingUser.isSystem()).thenReturn(true);
        assertAllWritesForbidden();
    }

    @Test
    public void missingBonusOrToggleAndMalformedProfileIdAreBadRequests() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.ADMINCONSOLE);
        when(request.getBodyAs(Integer.class)).thenReturn(null);
        assertThrows(BadRequestException.class, () -> controller.addOnlineTimeForToday(request, response));
        when(request.getBodyAs(Boolean.class)).thenReturn(null);
        assertThrows(BadRequestException.class, () -> controller.setMaxUsage(request, response));
        when(request.getHeader("id")).thenReturn("invalid");
        assertThrows(BadRequestException.class, () -> controller.resetBonusTimeForToday(request, response));
        verifyNoInteractions(profiles, usage);
    }

    @Test
    public void readOnlyProfileAccessRemainsAvailableToDashboardUsers() {
        when(request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT)).thenReturn(AppContext.DASHBOARD);
        when(operatingUser.getUserRole()).thenReturn(UserRole.CHILD);
        when(profiles.getProfiles()).thenReturn(Collections.emptyList());
        assertTrue(controller.getProfiles(request, response).isEmpty());
        verifyNoInteractions(users, devices);
    }
}
