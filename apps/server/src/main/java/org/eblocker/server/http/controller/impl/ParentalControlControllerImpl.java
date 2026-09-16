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
package org.eblocker.server.http.controller.impl;

import com.google.inject.Inject;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.UsageAccount;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserRole;
import org.eblocker.server.common.session.Session;
import org.eblocker.server.common.data.parentalcontrol.SearchEngineConfiguration;
import org.eblocker.server.common.page.PageContextStore;
import org.eblocker.server.common.session.SessionStore;
import org.eblocker.server.http.controller.ParentalControlController;
import org.eblocker.server.http.server.SessionContextController;
import org.eblocker.server.http.service.DeviceService;
import org.eblocker.server.http.service.ParentalControlSearchEngineConfigService;
import org.eblocker.server.http.service.ParentalControlService;
import org.eblocker.server.http.service.ParentalControlUsageService;
import org.eblocker.server.http.service.UserService;
import org.eblocker.server.http.security.AppContext;
import org.eblocker.server.http.security.SecurityProcessor;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;
import org.restexpress.exception.ForbiddenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class ParentalControlControllerImpl extends SessionContextController implements ParentalControlController {

    private static final Logger log = LoggerFactory.getLogger(ParentalControlControllerImpl.class);

    private final ParentalControlService parentalControlService;
    private final ParentalControlUsageService parentalControlUsageService;
    private final ParentalControlSearchEngineConfigService searchEngineConfigService;
    private final DeviceService deviceService;
    private final UserService userService;

    @Inject
    public ParentalControlControllerImpl(
            SessionStore sessionStore, PageContextStore pageContextStore,
            ParentalControlService parentalControlService, ParentalControlUsageService parentalControlUsageService,
            ParentalControlSearchEngineConfigService searchEngineConfigService,
            DeviceService deviceService, UserService userService) {
        super(sessionStore, pageContextStore);
        this.parentalControlService = parentalControlService;
        this.parentalControlUsageService = parentalControlUsageService;
        this.searchEngineConfigService = searchEngineConfigService;
        this.deviceService = deviceService;
        this.userService = userService;
    }

    // Create profile
    @Override
    public UserProfileModule storeNewProfile(Request request, Response response) {
        log.info("storeNewProfile");
        UserProfileModule profile = request.getBodyAs(UserProfileModule.class);
        return parentalControlService.storeNewProfile(profile);
    }

    // Read profiles
    @Override
    public List<UserProfileModule> getProfiles(Request request, Response response) {
        log.info("getProfiles");
        return parentalControlService.getProfiles();
    }

    // Update profile
    @Override
    public UserProfileModule updateProfile(Request request, Response response) {
        log.info("updateProfile");
        UserProfileModule profile = request.getBodyAs(UserProfileModule.class);
        return parentalControlService.updateProfile(profile);
    }

    // Delete profile
    @Override
    public void deleteProfile(Request request, Response response) {
        log.info("deleteProfile");
        String idString = request.getHeader("id", "No profile module ID provided");

        int profileId = Integer.valueOf(idString);
        parentalControlService.deleteProfile(profileId);
    }

    @Override
    public void deleteAllProfiles(Request request, Response response) {
        log.info("deleteAllProfiles");
        List<Integer> ids = request.getBodyAs(List.class);
        ids.forEach(id -> parentalControlService.deleteProfile(id));
    }

    @Override
    public boolean startUsage(Request request, Response response) {
        Device device = deviceService.getDeviceById(getSession(request).getDeviceId());
        return parentalControlUsageService.startUsage(device);
    }

    @Override
    public void stopUsage(Request request, Response response) {
        Device device = deviceService.getDeviceById(getSession(request).getDeviceId());
        parentalControlUsageService.stopUsage(device);
    }

    @Override
    public UsageAccount getUsage(Request request, Response response) {
        Device device = deviceService.getDeviceById(getSession(request).getDeviceId());
        return parentalControlUsageService.getUsageAccount(device);
    }

    @Override
    public UsageAccount getUsageByUserId(Request request, Response response) {
        Integer userId = Integer.valueOf(request.getHeader("id"));
        return parentalControlUsageService.getUsageAccount(userId);
    }

    @Override
    public Map<String, SearchEngineConfiguration> getSearchEngineConfiguration(Request request, Response response) {
        return searchEngineConfigService.getConfigByLanguage();
    }

    @Override
    public void setMaxUsage(Request request, Response response) {
        int profileId = managedProfileId(request);
        boolean value = readToggle(request);
        parentalControlService.changeProfileSettings(profileId, profile -> profile.setControlmodeMaxUsage(value));
    }

    @Override
    public void setContentFilter(Request request, Response response) {
        int profileId = managedProfileId(request);
        boolean value = readToggle(request);
        parentalControlService.changeProfileSettings(profileId, profile -> profile.setControlmodeUrls(value));
    }

    @Override
    public void setInternetAccessStatus(Request request, Response response) {
        int profileId = managedProfileId(request);
        boolean value = readToggle(request);
        parentalControlService.changeProfileSettings(profileId, profile -> profile.setInternetBlocked(value));
    }

    @Override
    public boolean getInternetAccessStatus(Request request, Response response) {
        Integer profileId = Integer.valueOf(request.getHeader("id"));
        UserProfileModule upm = parentalControlService.getProfile(profileId);
        return upm.isInternetBlocked() != null ? upm.isInternetBlocked() : false;
    }

    @Override
    public void addOnlineTimeForToday(Request request, Response response) {
        int profileId = managedProfileId(request);
        Integer min = request.getBodyAs(Integer.class);
        if (min == null) {
            throw new BadRequestException("Bonus minutes are required");
        }
        parentalControlUsageService.addBonusTimeForToday(profileId, min);
    }

    @Override
    public void resetBonusTimeForToday(Request request, Response response) {
        int profileId = managedProfileId(request);
        parentalControlService.changeProfileSettings(profileId, profile -> profile.setBonusTimeUsage(null));
    }

    private boolean readToggle(Request request) {
        Boolean value = request.getBodyAs(Boolean.class);
        if (value == null) {
            throw new BadRequestException("A boolean setting is required");
        }
        return value;
    }

    private int managedProfileId(Request request) {
        final int profileId;
        try {
            profileId = Integer.parseInt(request.getHeader("id"));
        } catch (NumberFormatException e) {
            throw new BadRequestException("A valid profile ID is required");
        }
        AppContext context = (AppContext) request.getAttachment(SecurityProcessor.APP_CONTEXT_ATTACHMENT);
        if (context == AppContext.ADMINCONSOLE || context == AppContext.ADMINDASHBOARD || context == AppContext.CONSOLE) {
            return profileId;
        }
        if (context == AppContext.DASHBOARD) {
            Session session = getSession(request);
            Device device = session == null ? null : deviceService.getDeviceById(session.getDeviceId());
            UserModule operatingUser = device == null ? null : userService.getUserById(device.getOperatingUser());
            // Parental-control cards are issued to PARENT users for CHILD users' profiles.
            // Resolve the operating user from the requesting session, never from a request parameter.
            if (operatingUser != null && !operatingUser.isSystem() && operatingUser.getUserRole() == UserRole.PARENT &&
                    userService.getUsers(false).stream().anyMatch(user -> !user.isSystem() &&
                            user.getUserRole() == UserRole.CHILD && Integer.valueOf(profileId).equals(user.getAssociatedProfileId()))) {
                return profileId;
            }
        }
        throw new ForbiddenException("Parental-control management requires an administrator or parent");
    }

}
