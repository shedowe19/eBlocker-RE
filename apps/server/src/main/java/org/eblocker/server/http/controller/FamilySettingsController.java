/* Copyright 2026 eBlocker contributors. Licensed under EUPL-1.2. */
package org.eblocker.server.http.controller;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserModuleTransport;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.http.controller.converter.UserModuleConverter;
import org.eblocker.server.http.service.DeviceService;
import org.eblocker.server.http.service.ParentalControlFilterListsService;
import org.eblocker.server.http.service.ParentalControlService;
import org.eblocker.server.http.service.UserService;
import org.restexpress.Request;
import org.restexpress.Response;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ForbiddenException;
import org.restexpress.exception.NotFoundException;
import java.nio.charset.StandardCharsets;

/** Admin-console-only narrow mutations, registered separately from the frozen legacy contract. */
@Singleton
public final class FamilySettingsController {
    private final UserService users;
    private final ParentalControlService profiles;
    private final ParentalControlFilterListsService filters;
    private final DeviceService devices;
    private final DeviceController deviceController;

    @Inject
    public FamilySettingsController(UserService users, ParentalControlService profiles,
            ParentalControlFilterListsService filters, DeviceService devices, DeviceController deviceController) {
        this.users = users; this.profiles = profiles; this.filters = filters;
        this.devices = devices; this.deviceController = deviceController;
    }

    public UserModuleTransport patchUser(Request request, Response response) {
        return UserModuleConverter.getUserModuleTransport(users.patchSettings(
                FamilySettingsPatch.id(request.getHeader("id")), FamilySettingsPatch.user(body(request))));
    }

    public UserProfileModule patchProfile(Request request, Response response) {
        FamilySettingsPatch patch = FamilySettingsPatch.profile(body(request));
        validateFilters(patch);
        return profiles.changeProfileSettings(FamilySettingsPatch.id(request.getHeader("id")), patch::applyProfile);
    }

    public UserProfileModule createProfile(Request request, Response response) {
        FamilySettingsPatch patch = FamilySettingsPatch.profile(body(request));
        validateFilters(patch);
        UserProfileModule profile = new UserProfileModule(null, null, "", null, null, false, false,
                java.util.Set.of(), java.util.Set.of(), UserProfileModule.InternetAccessRestrictionMode.BLACKLIST,
                java.util.Set.of(), java.util.Map.of(), false, false, null);
        patch.applyProfile(profile);
        if (profile.getName() == null || profile.getName().isBlank()) throw new BadRequestException("error.family.invalidName");
        return profiles.storeManagedProfile(profile);
    }

    private void validateFilters(FamilySettingsPatch patch) {
        for (Integer filterId : patch.changedFilterIds()) {
            if (filters.getParentalControlFilterMetaData(filterId) == null) {
                throw new BadRequestException("error.family.unknownFilter");
            }
        }
    }

    public Device patchAssignment(Request request, Response response) {
        Integer userId = FamilySettingsPatch.assignment(body(request));
        String deviceId = request.getHeader("deviceId");
        Device current = devices.getDeviceById(deviceId);
        if (current == null) throw new NotFoundException("error.device.settings.notFound");
        int targetId = userId == null ? current.getDefaultSystemUser() : userId;
        UserModule target = users.getUserById(targetId);
        if (target == null) throw new NotFoundException("error.family.userNotFound");
        if (target.isSystem() && targetId != current.getDefaultSystemUser()) {
            throw new ForbiddenException("error.family.foreignSystemUser");
        }
        return deviceController.assignUser(deviceId, targetId);
    }

    private static String body(Request request) {
        if (request.getBody() == null) throw new BadRequestException("error.family.invalidSettings");
        return request.getBody().toString(StandardCharsets.UTF_8);
    }
}
