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
package org.eblocker.server.http.service;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.common.data.migrations.DefaultEntities;
import org.eblocker.server.common.data.parentalcontrol.ParentalControlFilterMetaData;
import org.eblocker.server.common.data.systemstatus.SubSystem;
import org.eblocker.server.common.startup.SubSystemInit;
import org.eblocker.server.common.startup.SubSystemService;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;
import org.restexpress.exception.ForbiddenException;
import org.restexpress.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Singleton
@SubSystemService(value = SubSystem.EVENT_LISTENER, allowUninitializedCalls = true)
public class ParentalControlService {

    private static final Logger LOG = LoggerFactory.getLogger(AppModuleService.class);
    private static final Logger STATUS = LoggerFactory.getLogger("STATUS");

    private final DataSource dataSource;
    private final UserService userService;

    private final ConcurrentMap<Integer, UserProfileModule> profiles = new ConcurrentHashMap<>(32, 0.75f, 1);
    private final List<ParentalControlProfileChangeListener> listeners = new ArrayList<>();

    @Inject
    public ParentalControlService(DataSource dataSource,
                                  UserService userService) {
        this.dataSource = dataSource;
        this.userService = userService;
    }

    @SubSystemInit
    public void init() {
        // Fill cache for user profiles with content
        getProfilesFromDataSource().forEach(p -> profiles.put(p.getId(), p));
        assureConsistency(profiles);
    }

    public synchronized UserProfileModule storeNewProfile(UserProfileModule profile) {
        return storeProfile(profile, true);
    }

    /** Explicitly managed reusable profiles survive without a currently assigned user. */
    public synchronized UserProfileModule storeManagedProfile(UserProfileModule profile) {
        return storeProfile(profile, false);
    }

    private UserProfileModule storeProfile(UserProfileModule profile, boolean forSingleUser) {
        if (profile.getId() != null) {
            throw new BadRequestException("Must not provide ID for new entity");
        }
        int id = dataSource.nextId(UserProfileModule.class);
        if (id <= 0) {
            throw new ConflictException("Cannot create new user profile: No new id available");
        }
        profile.setId(id);
        //		profile.setNameKey(null);
        profile.setDescriptionKey(null);
        profile.setBuiltin(false);
        profile.setStandard(false);
        // Legacy per-user creation retains its ownership flag; explicit reusable profiles are independent.
        // Migrations do not call this code and directly calls dataSource.save, so that old profiles
        // have forSingleUser set to false allowing to migrate them into new structure (one profile for each user)
        // which in turn sets setForSingleUser to true (so not to migrate twice)
        profile.setForSingleUser(forSingleUser);
        UserProfileModule savedProfile = dataSource.save(profile, id);
        profiles.put(savedProfile.getId(), savedProfile);
        notifyListeners(savedProfile);
        return savedProfile;
    }

    public List<UserProfileModule> getProfiles() {
        return new ArrayList<>(profiles.values());
    }

    public synchronized UserProfileModule updateProfile(UserProfileModule profile) {
        UserProfileModule dbProfile = dataSource.get(UserProfileModule.class, profile.getId());
        if (dbProfile == null) throw new NotFoundException("User profile does not exist");
        // Whether a profile is builtin or not, cannot be changed
        profile.setBuiltin(dbProfile.isBuiltin());
        profile.setStandard(dbProfile.isStandard());
        // If a profile is builtin, its name and description cannot be changed
        if (dbProfile.isBuiltin()) {
            profile.setName(dbProfile.getName());
            profile.setDescription(dbProfile.getDescription());
            profile.setNameKey(dbProfile.getNameKey());
            profile.setDescriptionKey(dbProfile.getDescriptionKey());
        } else {
            //			profile.setNameKey(null);
            profile.setDescriptionKey(null);
        }

        UserProfileModule savedProfile = dataSource.save(profile, profile.getId());
        profiles.put(savedProfile.getId(), savedProfile);
        notifyListeners(savedProfile);
        return savedProfile;
    }

    /** Applies a narrow change to the latest profile under the same lock as profile replacement and deletion. */
    public synchronized UserProfileModule changeProfileSettings(int profileId, Consumer<UserProfileModule> change) {
        UserProfileModule current = profiles.get(profileId);
        if (current == null) {
            throw new NotFoundException("User profile does not exist");
        }
        // Built-in templates and the limbo profile must not acquire per-user overrides.
        // The standard profile remains editable for devices without an assigned user.
        if (current.isBuiltin() && !current.isStandard()) {
            throw new ForbiddenException("Built-in profile settings cannot be changed here");
        }
        UserProfileModule updated = current.copy();
        change.accept(updated);
        return updateProfile(updated);
    }

    public synchronized void deleteProfile(int profileId) {
        UserProfileModule dbProfile = dataSource.get(UserProfileModule.class, profileId);
        if (dbProfile == null) throw new NotFoundException("User profile does not exist");
        if (dbProfile.isBuiltin()) {
            throw new BadRequestException("Cannot delete user profile " + profileId + ", because it is a built-in profile.");

        }

        // Check no device uses this profile
        for (UserModule user : userService.getUsers(false)) {
            if (java.util.Objects.equals(user.getAssociatedProfileId(), profileId)) {
                throw new ConflictException("Cannot delete user profile " + profileId + ", because it is assigned to user " + user.getName());
            }
        }
        dataSource.delete(UserProfileModule.class, profileId);
        profiles.remove(profileId);
    }

    public UserProfileModule getProfile(int id) {
        return profiles.get(id);
    }

    public void addListener(ParentalControlProfileChangeListener listener) {
        listeners.add(listener);
    }

    /**
     * Updates all profile filters
     *
     * @param metaData all available filters
     */
    synchronized void updateFilters(Collection<ParentalControlFilterMetaData> metaData) {
        Set<Integer> ids = metaData.stream().map(ParentalControlFilterMetaData::getId).collect(Collectors.toSet());
        getProfiles().forEach(profile -> {
            // remove non-existing filter-lists from profiles
            Set<Integer> remaining = new HashSet<>(profile.getInaccessibleSitesPackages());
            if (remaining.retainAll(ids)) {
                UserProfileModule updated = profile.copy();
                updated.setInaccessibleSitesPackages(remaining);
                UserProfileModule saved = dataSource.save(updated, updated.getId());
                profiles.put(saved.getId(), saved);
            }
        });
    }

    public interface ParentalControlProfileChangeListener {
        void onChange(UserProfileModule profile);
    }

    public UserProfileModule createDefaultProfile() {
        UserProfileModule defaultProfile = new UserProfileModule(
                DefaultEntities.PARENTAL_CONTROL_DEFAULT_PROFILE_ID,
                null,
                null,
                "PARENTAL_CONTROL_DEFAULT_PROFILE_NAME",
                "PARENTAL_CONTROL_DEFAULT_PROFILE_DESCRIPTION",
                true,
                false,
                Collections.emptySet(),
                Collections.emptySet(),
                UserProfileModule.InternetAccessRestrictionMode.BLACKLIST,
                Collections.emptySet(),
                Collections.emptyMap(),
                null,
                false,
                null
        );
        defaultProfile.setBuiltin(true);
        defaultProfile.setControlmodeTime(false);
        defaultProfile.setControlmodeUrls(false);

        UserProfileModule savedProfile = dataSource.save(defaultProfile, defaultProfile.getId());
        profiles.put(savedProfile.getId(), savedProfile);
        notifyListeners(savedProfile);
        return savedProfile;
    }

    private void notifyListeners(UserProfileModule profile) {
        listeners.forEach(listener -> listener.onChange(profile));
    }

    /**
     * Find and delete all profiles, that have been created for a user, but are not assigned to any users any more.
     * When a user is created we automatically create a profile for that user only. When the user is deleted, we
     * delete the profile as well. This check makes sure that there are not more old profiles left.
     *
     * @param profiles Map which profiles should be checked for consistency
     */
    private void assureConsistency(ConcurrentMap<Integer, UserProfileModule> profiles) {
        boolean foundInconsistencies = false;

        List<UserModule> users = dataSource.getAll(UserModule.class);

        for (Entry<Integer, UserProfileModule> entry : profiles.entrySet()) {
            UserProfileModule p = entry.getValue();
            boolean isProfileAssigned = false;

            // !! do not delete system owned profiles !!
            if (p.isForSingleUser() && !p.isBuiltin() && !p.isHidden()) {
                for (UserModule user : users) {
                    if (user.getAssociatedProfileId().equals(entry.getKey())) {
                        isProfileAssigned = true;
                    }
                }

                if (!isProfileAssigned) {
                    foundInconsistencies = true;
                    LOG.debug("Found inconsistent Profile {} : {}", entry.getKey(), p.getName());
                    dataSource.delete(UserProfileModule.class, entry.getKey());
                    profiles.remove(entry.getKey(), p);
                }
            }
        }

        STATUS.info("Checked user profiles: {}", foundInconsistencies ? "Inconsistencies found and removed" : "OK");
    }

    private List<UserProfileModule> getProfilesFromDataSource() {
        return dataSource.getAll(UserProfileModule.class);
    }

}
