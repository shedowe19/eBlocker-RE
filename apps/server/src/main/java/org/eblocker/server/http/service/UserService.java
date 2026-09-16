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
import com.google.inject.name.Named;
import org.eblocker.server.common.data.DataSource;
import org.eblocker.server.common.data.Device;
import org.eblocker.server.common.data.UserModule;
import org.eblocker.server.common.data.UserProfileModule;
import org.eblocker.server.common.data.UserRole;
import org.eblocker.server.common.data.WhiteListConfig;
import org.eblocker.server.common.data.dashboard.AccessRight;
import org.eblocker.server.common.data.dashboard.DashboardColumnsView;
import org.eblocker.server.common.data.migrations.DefaultEntities;
import org.eblocker.server.common.data.systemstatus.SubSystem;
import org.eblocker.server.common.startup.SubSystemInit;
import org.eblocker.server.common.startup.SubSystemService;
import org.eblocker.server.http.security.PasswordUtil;
import org.eblocker.server.http.controller.FamilySettingsPatch;
import org.restexpress.exception.BadRequestException;
import org.restexpress.exception.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Singleton
@SubSystemService(value = SubSystem.EVENT_LISTENER, allowUninitializedCalls = false)
public class UserService {
    private static final Logger LOG = LoggerFactory.getLogger(UserService.class);
    private static final Logger STATUS = LoggerFactory.getLogger("STATUS");

    private final DataSource dataSource;
    private final DeviceService deviceService;
    private final DashboardCardService dashboardCardService;
    private final ConcurrentMap<Integer, UserModule> cache;
    private final List<UserChangeListener> listeners = new ArrayList<>();
    private final String standardUserTranslation;
    // Persistence and dashboard work never run while holding this short cache publication lock.
    private final Object cacheCommitLock = new Object();
    private long cacheVersion;
    private final Object[] userMutationLocks = java.util.stream.IntStream.range(0, 64).mapToObj(i -> new Object()).toArray();
    private final Object roleChangeLock = new Object();

    @Inject
    public UserService(DataSource datasource,
                       DeviceService deviceService,
                       DashboardCardService dashboardCardService,
                       @Named("parentalControl.standardUser.translation") String standardUserTranslation) {
        this.dataSource = datasource;
        this.deviceService = deviceService;
        this.dashboardCardService = dashboardCardService;
        cache = new ConcurrentHashMap<>(64, 0.75f, 2);
        this.standardUserTranslation = standardUserTranslation;

        this.deviceService.addListener(new DeviceService.DeviceChangeListener() {
            @Override
            public void onChange(Device device) {
                // nothing to do
            }

            @Override
            public void onDelete(Device device) {
                doDelete(device.getDefaultSystemUser());
            }

            @Override
            public void onReset(Device device) {
                doReset(device);
            }
        });
    }

    public Collection<UserModule> getUsers(boolean refresh) {
        if (refresh) {
            refresh();
        }
        synchronized (cacheCommitLock) {
            return cache.values().stream().map(UserService::copyUser).collect(Collectors.toList());
        }
    }

    public UserModule createUser(
            Integer associatedProfileId,
            String name,
            String nameKey,
            LocalDate birthday,
            UserRole userRole,
            String newPin) {

        UserModule savedUser;
        synchronized (roleChangeLock) {
            if (!isUniqueCustomerCreatedName(null, name)) {
                throw new ConflictException("Name of user must be unique");
            }

            int nextId = getNextUserId();

            UserModule newUser = new UserModule(
                    nextId,
                    associatedProfileId,
                    name,
                    nameKey,
                    birthday,
                    userRole,
                    false,
                    null,
                    null,
                    null, // leave null, we need the user created here to create adequate DashboardColumnsView
                    null,
                    null);

            // now set dashboardColumnsView based on new user's role
            newUser.setDashboardColumnsView(getDashboardForUser(newUser, newUser.getUserRole()));

            if (newPin != null) {
                // New user was created with PIN
                newUser.changePin(newPin);
            }

            savedUser = createAndCacheUser(newUser);
            checkAndUpdateParentalControlData(nextId, userRole, null);
        }
        notifyListeners(savedUser);

        return savedUser;
    }

    private int getNextUserId() {
        return dataSource.nextId(UserModule.class);
    }

    public UserModule updateUser(Integer id, Integer associatedProfileId,
                                 String name, String nameKey, LocalDate birthday, UserRole userRole, String newPin) {
        UserModule savedUser;
        synchronized (roleChangeLock) {
            UserRole[] previousRole = new UserRole[1];
            savedUser = mutateUser(id, current -> {
                previousRole[0] = current.getUserRole();
                UserModule updated = current.isSystem()
                        ? updateSystemUser(id, associatedProfileId, name, nameKey, newPin, null, current)
                        : updateRegularUser(id, associatedProfileId, name, nameKey, birthday, userRole, newPin, null, current);
                updated.setDashboardColumnsView(getDashboardForUser(updated, updated.getUserRole()));
                return updated;
            });
            checkAndUpdateParentalControlData(id, savedUser.getUserRole(), previousRole[0]);
        }
        notifyListeners(savedUser);
        return savedUser;
    }

    public boolean deleteUser(Integer userId) {
        if (userId == null) {
            throw new BadRequestException("Cannot delete user without valid user id.");
        }
        // make sure it is not a system user
        if (findUpdatableUser(userId).isSystem()) {
            throw new BadRequestException("Cannot delete user " + userId + ", because it is a system user.");
        }

        synchronized (roleChangeLock) {
            boolean deleted = doDelete(userId);
            if (deleted) checkAndUpdateParentalControlData(userId, null, null);
            return deleted;
        }
    }

    private UserModule updateSystemUser(Integer id,
                                        Integer associatedProfileId,
                                        String name, String nameKey,
                                        String newPin, DashboardColumnsView dashboardColumnsView,
                                        UserModule oldUser) {
        // For system user only updating plugAndPlayFilterWhitelistId is allowed
        if (!Objects.equals(oldUser.getAssociatedProfileId(), associatedProfileId)
                || !Objects.equals(oldUser.getName(), name)
                || !Objects.equals(oldUser.getNameKey(), nameKey)
                || newPin != null) {
            throw new BadRequestException("Cannot update user " + id + ", because it is a built-in user.");
        }
        UserModule user = new UserModule(
                oldUser.getId(),
                oldUser.getAssociatedProfileId(),
                oldUser.getName(),
                oldUser.getNameKey(),
                oldUser.getBirthday(),
                oldUser.getUserRole(),
                oldUser.isSystem(),
                oldUser.getPin(),
                oldUser.getWhiteListConfigByDomains(),
                dashboardColumnsView,
                oldUser.getCustomBlacklistId(),
                oldUser.getCustomWhitelistId());
        return user;
    }

    private UserModule updateRegularUser(Integer id,
                                         Integer associatedProfileId,
                                         String name, String nameKey,
                                         LocalDate birthday, UserRole userRole,
                                         String newPin, DashboardColumnsView dashboardColumnsView,
                                         UserModule oldUser) {
        // Check ID of associated profile actually references an existing profile
        if (dataSource.get(UserProfileModule.class, associatedProfileId) == null) {
            throw new BadRequestException("User " + id
                    + " references non-existing profile "
                    + associatedProfileId);
        }
        // Check associated profile can be selected (limbo profile cannot!)
        if (associatedProfileId == DefaultEntities.PARENTAL_CONTROL_LIMBO_PROFILE_ID) {
            throw new BadRequestException("User " + id + " references unassignable profile "
                    + DefaultEntities.PARENTAL_CONTROL_LIMBO_PROFILE_ID);
        }

        // New name must be unique
        if (!isUniqueCustomerCreatedName(id, name)) {
            throw new ConflictException("Name of user must be unique");
        }

        // Create new instance, to avoid partial changes in cache, if anything goes wrong.
        UserModule user = new UserModule(
                id,
                associatedProfileId,
                name,
                nameKey,
                birthday,
                userRole,
                false,
                oldUser.getPin(),
                oldUser.getWhiteListConfigByDomains(),
                dashboardColumnsView,
                oldUser.getCustomBlacklistId(),
                oldUser.getCustomWhitelistId()
        );
        if (newPin != null) {
            user.changePin(newPin);
        }
        return user;
    }

    public UserModule updateUser(Integer id, DashboardColumnsView columns) {
        return mutateUser(id, user -> {
            user.setDashboardColumnsView(columns != null ? copyDashboard(columns) : getDashboardForUser(user, user.getUserRole()));
            return user;
        });
    }

    public UserModule updateUser(Integer id, Map<String, WhiteListConfig> whiteListConfigMap) {
        return mutateUser(id, user -> {
            user.setWhiteListConfigByDomains(whiteListConfigMap == null ? new HashMap<>() : new HashMap<>(whiteListConfigMap));
            return user;
        });
    }

    public UserModule updateUser(Integer id, Integer customBlacklistId, Integer customWhitelistId) {
        UserModule result = mutateUser(id, user -> {
            user.setCustomBlacklistId(customBlacklistId);
            user.setCustomWhitelistId(customWhitelistId);
            return user;
        });
        notifyListeners(result);
        return result;
    }

    public UserModule updateUserDashboardView(Integer id) {
        return mutateUser(id, user -> {
            user.setDashboardColumnsView(getDashboardForUser(user, user.getUserRole()));
            return user;
        });
    }

    public void updateAllDefaultDashboardView() {
        deviceService.getDevices(false).forEach(dev -> {
            updateUserDashboardView(dev.getDefaultSystemUser());
        });
    }

    public UserModule getUserById(int id) {
        UserModule user = cache.get(id);
        if (user != null) {
            return copyUser(user);
        }
        refresh();
        return copyUser(cache.get(id));
    }

    /** Authentication decisions must not depend on a cached PIN or role snapshot. */
    public UserModule getUserForAuthentication(int id) {
        synchronized (mutationLock(id)) {
            return copyUser(dataSource.get(UserModule.class, id));
        }
    }

    @SubSystemInit
    public void init() {
        refresh();
        assureConsistency();
    }

    public void refresh() {
        long snapshotVersion;
        synchronized (cacheCommitLock) { snapshotVersion = cacheVersion; }
        List<UserModule> users = dataSource.getAll(UserModule.class).stream().map(UserService::copyUser).collect(Collectors.toList());
        synchronized (cacheCommitLock) {
            if (snapshotVersion != cacheVersion) return;
            cacheVersion++;
            cache.clear();
            users.forEach(user -> cache.put(user.getId(), user));
        }
    }

    private void assureConsistency() {
        boolean foundInconsistencies = false;
        Collection<Device> devices = deviceService.getDevices(true);

        //
        // Make sure, all devices have existing and consistent users assigned.
        //
        Set<Integer> defaultSystemUsers = new HashSet<>();
        for (Device device : devices) {
            foundInconsistencies = assureConsistency(device, defaultSystemUsers) || foundInconsistencies;
        }

        //
        // Find orphaned system users
        //
        Set<Integer> obsoleteSystemUsers = new HashSet<>();
        for (UserModule user : getUsers(false)) {
            if (user.isSystem() && user.getId() != DefaultEntities.PARENTAL_CONTROL_LIMBO_USER_ID &&
                    !defaultSystemUsers.contains(user.getId()) &&
                    (user.getNameKey() == null || !user.getNameKey().equals(standardUserTranslation))) {
                obsoleteSystemUsers.add(user.getId());
            }
        }
        for (Integer userId : obsoleteSystemUsers) {
            LOG.warn("Removing obsolete system user {}", userId);
            deleteAndUncacheUser(userId);
            foundInconsistencies = true;
        }

        //
        // make sure standard user has standard profile assigned and that standard user is system user
        //
        getUsers(false).forEach(user -> {
            if (user.getNameKey() != null && user.getNameKey().equals(standardUserTranslation) &&
                    (!user.getAssociatedProfileId().equals(DefaultEntities.PARENTAL_CONTROL_DEFAULT_PROFILE_ID) ||
                            !user.isSystem())) {
                mutateUser(user.getId(), current -> {
                    if (Objects.equals(current.getNameKey(), standardUserTranslation)) {
                        current.setAssociatedProfileId(DefaultEntities.PARENTAL_CONTROL_DEFAULT_PROFILE_ID);
                        current.setSystem(true);
                    }
                    return current;
                });
            }
        });

        //
        // make sure each user has updated DashboardView
        //
        getUsers(false).
                stream().
                filter(user -> user.getAssociatedProfileId() != null).
                forEach(user -> updateUserDashboardView(user.getId()));

        //
        // make sure all visible users have userRole
        //
        getUsers(false).forEach(this::assureConsistencyOfUserRole);

        STATUS.info("Checked device/user relation for {} devices: {}", devices.size(), foundInconsistencies ? "Inconsistencies found and removed" : "OK");
    }

    private void assureConsistencyOfUserRole(UserModule userModule) {
        if (!userModule.isSystem() && userModule.getAssociatedProfileId() != null && userModule.getUserRole() == null) {
            mutateUser(userModule.getId(), current -> {
                if (!current.isSystem() && current.getAssociatedProfileId() != null && current.getUserRole() == null) {
                    UserProfileModule profile = dataSource.get(UserProfileModule.class, current.getAssociatedProfileId());
                    if (profile != null) {
                        boolean hasRestrictions = profile.isControlmodeTime() || profile.isControlmodeUrls() || profile.isControlmodeMaxUsage();
                        current.setUserRole(hasRestrictions ? UserRole.CHILD : UserRole.PARENT);
                    }
                }
                return current;
            });
        }
    }

    private boolean assureConsistency(Device device, Set<Integer> defaultSystemUsers) {
        boolean foundInconsistencies = false;
        //
        // If current default system user is invalid, create a new one:
        //   - user does not exist
        //   - user is not a system user
        //   - user is already assigned to another device
        //
        if (cache.get(device.getDefaultSystemUser()) == null ||
                !cache.get(device.getDefaultSystemUser()).isSystem() ||
                defaultSystemUsers.contains(device.getDefaultSystemUser())) {
            UserModule defaultSystemUser = createAndCacheUser(createDefaultSystemUser(device.getId()));
            LOG.warn("Cannot find valid default system user {} for device {}, created new default system user {}",
                    device.getDefaultSystemUser(), device.getId(), defaultSystemUser.getId());
            device.setDefaultSystemUser(defaultSystemUser.getId());
            foundInconsistencies = true;
        }
        //
        // If current assigned user is invalid, assign default system user:
        //   - user does not exist
        //   - assigned user is system user, but is not the correct default system user
        //
        if (cache.get(device.getAssignedUser()) == null ||
                (cache.get(device.getAssignedUser()).isSystem() && device.getAssignedUser() != device.getDefaultSystemUser())) {
            LOG.warn("Cannot find valid assigned user {} of device {}, replacing with default system user {}",
                    device.getAssignedUser(), device.getId(), device.getDefaultSystemUser());
            device.setAssignedUser(device.getDefaultSystemUser());
            foundInconsistencies = true;
        }
        //
        // If current operating user is invalid, declare assigned user to operating user
        //   - user does not exist
        //   - assigned user is system user, but operating user is any other user (--> If assigned user is system, operating user cannot be changed!)
        //   - assigned user is not system user, but operating user is system user other than virtual "logged-out" user (--> Device cannot be taken by system user!)
        //
        if (cache.get(device.getOperatingUser()) == null ||
                (cache.get(device.getAssignedUser()).isSystem() && device.getOperatingUser() != device.getAssignedUser()) ||
                (!cache.get(device.getAssignedUser()).isSystem() && cache.get(device.getOperatingUser()).isSystem() && device.getOperatingUser() != DefaultEntities.PARENTAL_CONTROL_LIMBO_USER_ID)) {
            LOG.warn("Cannot find valid operating user {} of device {}, replacing with assigned user {}",
                    device.getOperatingUser(), device.getId(), device.getAssignedUser());
            device.setOperatingUser(device.getAssignedUser());
            foundInconsistencies = true;
        }
        if (foundInconsistencies) {
            deviceService.updateDevice(device);
        }
        defaultSystemUsers.add(device.getDefaultSystemUser());
        return foundInconsistencies;
    }

    public void addListener(UserChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(UserModule user) {
        listeners.forEach(listener -> listener.onChange(user));
    }

    private Object mutationLock(int id) {
        return userMutationLocks[id & (userMutationLocks.length - 1)];
    }

    private UserModule mutateUser(Integer id, UnaryOperator<UserModule> change) {
        if (id == null) throw new BadRequestException("Invalid user id");
        synchronized (mutationLock(id)) {
            UserModule current = findUpdatableUser(id);
            UserModule updated = change.apply(copyUser(current));
            commitUser(current, updated);
            return updated;
        }
    }

    // Caller holds the user mutation monitor. Compare before publishing; no cache object is mutated in place.
    private void commitUser(UserModule current, UserModule updated) {
        if (!dataSource.compareAndSetUser(current, updated)) throw new ConflictException("error.family.concurrentChange");
        cacheUser(updated);
    }

    private void cacheUser(UserModule user) {
        UserModule snapshot = copyUser(user);
        synchronized (cacheCommitLock) {
            cacheVersion++;
            cache.put(snapshot.getId(), snapshot);
        }
    }

    private void invalidateUser(int id) {
        synchronized (cacheCommitLock) {
            cacheVersion++;
            cache.remove(id);
        }
    }

    private UserModule createAndCacheUser(UserModule user) {
        synchronized (mutationLock(user.getId())) {
            UserModule savedUser = copyUser(user);
            commitUser(null, savedUser);
            return savedUser;
        }
    }

    private void deleteAndUncacheUser(int id) {
        synchronized (mutationLock(id)) {
            dataSource.delete(UserModule.class, id);
            invalidateUser(id);
        }
    }

    private static UserModule copyUser(UserModule user) {
        if (user == null) return null;
        return new UserModule(user.getId(), user.getAssociatedProfileId(), user.getName(), user.getNameKey(),
                user.getBirthday(), user.getUserRole(), user.isSystem(), user.getPin() == null ? null : user.getPin().clone(),
                user.getWhiteListConfigByDomains() == null ? new HashMap<>() : new HashMap<>(user.getWhiteListConfigByDomains()),
                copyDashboard(user.getDashboardColumnsView()), user.getCustomBlacklistId(), user.getCustomWhitelistId());
    }

    private static DashboardColumnsView copyDashboard(DashboardColumnsView view) {
        return view == null ? null : new DashboardColumnsView(
                view.getOneColumn() == null ? null : new ArrayList<>(view.getOneColumn()),
                view.getTwoColumn() == null ? null : new ArrayList<>(view.getTwoColumn()),
                view.getThreeColumn() == null ? null : new ArrayList<>(view.getThreeColumn()));
    }

    private void doReset(Device device) {
        restoreDefaultSystemUserAsUsers(device);
    }

    private boolean doDelete(int userId) {
        // make sure user is not assigned to any device or logged in to any device
        for (Device device : deviceService.getDevices(true)) {
            if (device.getAssignedUser() == userId
                    || device.getOperatingUser() == userId
                    || device.getDefaultSystemUser() == userId) {
                return false;
            }
        }
        deleteAndUncacheUser(userId);
        return true;
    }

    public UserModule createDefaultSystemUser(String name) {
        int userId = getNextUserId();
        return createDefaultSystemUser(name, userId);
    }

    private UserModule createDefaultSystemUser(String name, int userId) {
        return new UserModule(
                userId,
                DefaultEntities.PARENTAL_CONTROL_DEFAULT_PROFILE_ID,
                name,
                DefaultEntities.USER_SYSTEM_DEFAULT_NAME_KEY,
                null,
                null,
                true,
                null,
                new HashMap<>(),
                getDashboardForUser(null, null),
                null,
                null
        );
    }

    public UserModule restoreDefaultSystemUser(String name) {
        int userId = getNextUserId();
        return restoreDefaultSystemUser(name, userId);
    }

    public UserModule restoreDefaultSystemUser(String name, int userId) {
        UserModule restored;
        synchronized (roleChangeLock) {
            synchronized (mutationLock(userId)) {
                UserModule current = dataSource.get(UserModule.class, userId);
                if (current == null) {
                    restored = createDefaultSystemUser(name, userId);
                    restored.setDashboardColumnsView(getDashboardForUser(restored, restored.getUserRole()));
                    restored = createAndCacheUser(restored);
                } else {
                    restored = copyUser(current);
                    restored.setAssociatedProfileId(DefaultEntities.PARENTAL_CONTROL_DEFAULT_PROFILE_ID);
                    restored.setName(name);
                    restored.setNameKey(DefaultEntities.USER_SYSTEM_DEFAULT_NAME_KEY);
                    restored.setBirthday(null);
                    restored.setUserRole(null);
                    restored.setSystem(true);
                    restored.setDashboardColumnsView(getDashboardForUser(copyUser(current), current.getUserRole()));
                    commitUser(current, restored);
                }
            }
        }
        notifyListeners(restored);
        return restored;
    }

    public boolean isUniqueCustomerCreatedName(Integer id, String name) {
        Predicate<UserModule> isCustomerCreatedUserWithSameNameButDifferentId = m ->
                !m.isSystem()
                        && m.getName() != null
                        && m.getName().equals(name)
                        && !m.getId().equals(id);
        return dataSource.getAll(UserModule.class).stream().noneMatch(isCustomerCreatedUserWithSameNameButDifferentId);
    }

    public void setPin(Integer userId, String newPin) {
        if (userId == null) throw new BadRequestException("Invalid user id");
        synchronized (mutationLock(userId)) {
            UserModule user = dataSource.get(UserModule.class, userId);
            if (user == null) {
                throw new BadRequestException("Invalid user id");
            }
            if (user.isSystem()) {
                throw new BadRequestException("Cannot set PIN for user "
                        + user.getId() + ", because it is a built-in user.");
            }
            replacePin(user, newPin);
        }
    }

    public void changePin(Integer userId, String newPin, String oldPin) {
        if (userId == null) throw new BadRequestException("Invalid user id");
        synchronized (mutationLock(userId)) {
            UserModule oldUser = dataSource.get(UserModule.class, userId);
            // Does user whose PIN is to be changed exist?
            if (oldUser == null) {
                throw new BadRequestException("Invalid user id");
            }
            // Can user be changed?
            if (oldUser.isSystem()) {
                throw new BadRequestException("Cannot change PIN for user "
                        + oldUser.getId() + ", because it is a built-in user.");
            }
            // Is change request authorized by current PIN (oldPin)? If a PIN is currently set, of course
            if (oldUser.getPin() != null
                    && (oldPin == null
                    || !PasswordUtil.verifyPassword(oldPin, oldUser.getPin()))) {
                throw new BadRequestException("Cannot change PIN for user "
                        + oldUser.getId() + ", bacause PIN verification failed.");
            }
            replacePin(oldUser, newPin);
        }
    }

    private void replacePin(UserModule user, String newPin) {
        byte[] replacement = newPin == null || newPin.isEmpty() ? null : PasswordUtil.hashPassword(newPin);
        if (!dataSource.compareAndSetUserPin(user.getId(), user.getPin(), replacement)) {
            throw new ConflictException("error.family.concurrentChange");
        }
        invalidateUser(user.getId());
    }

    /** Verify against current persistence and upgrade old PIN hashes without saving a stale user object. */
    public boolean verifyPin(int userId, String pin) {
        synchronized (mutationLock(userId)) {
            UserModule user = dataSource.get(UserModule.class, userId);
            if (user == null || user.getPin() == null || pin == null || !PasswordUtil.verifyPassword(pin, user.getPin())) {
                return false;
            }
            if (PasswordUtil.needsRehash(user.getPin())) {
                try {
                    byte[] upgraded = PasswordUtil.hashPassword(pin);
                    if (dataSource.compareAndSetUserPin(userId, user.getPin(), upgraded)) {
                        invalidateUser(userId);
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Could not upgrade the verified user PIN; retaining the existing credential");
                }
            }
            // A PIN changed during verification must not authenticate the old value.
            // A concurrent role/profile change or another successful rehash remains valid.
            UserModule latest = dataSource.get(UserModule.class, userId);
            return latest != null && latest.getPin() != null
                    && (MessageDigest.isEqual(user.getPin(), latest.getPin()) || PasswordUtil.verifyPassword(pin, latest.getPin()));
        }
    }

    public interface UserChangeListener {
        void onChange(UserModule user);
    }

    private UserModule findUpdatableUser(Integer id) {
        // Load user
        UserModule user = dataSource.get(UserModule.class, id);

        // User must exist
        if (user == null) {
            throw new BadRequestException("User " + id + " does not exist and cannot be updated");
        }

        return user;
    }

    private void updateDashboardOfAllParents() {
        getAllParentUsers().forEach(user -> mutateUser(user.getId(), current -> {
            // A previously listed parent may have changed role before this fresh read.
            current.setDashboardColumnsView(getDashboardForUser(current, current.getUserRole()));
            return current;
        }));
    }

    private List<UserModule> getAllParentUsers() {
        return dataSource.getAll(UserModule.class).stream().
                filter(user -> user.getUserRole() != null && user.getUserRole().equals(UserRole.PARENT)).
                collect(Collectors.toList());
    }

    /**
     * Creates the DashboardColumnView for the user based on the user role.
     * Each userRole may get a different set of dashboard cards:
     * Parents can see parental-control-cards. Children can see child-cards (fragFinn, BlindeKuh).
     *
     * @return dashboardColumnView based on user role
     */
    private DashboardColumnsView getDashboardForUser(UserModule user, UserRole userRole) {
        // make sure that userRoles defaults to OTHER. This prevents a user from missing dashboard cards
        UserRole tmpUserRole = userRole == null ? UserRole.OTHER : userRole;
        DashboardColumnsView columns;
        List<AccessRight> accessRights = user == null ? Collections.emptyList() : getAccessRulesForUser(user);

        if (user == null || user.getDashboardColumnsView() == null) {
            columns = dashboardCardService.getNewDashboardCardColumns(tmpUserRole);
        } else {
            columns = copyDashboard(user.getDashboardColumnsView());
        }

        return dashboardCardService.getUpdatedColumnsView(columns, tmpUserRole, accessRights);
    }

    /**
     * when a user has access restrictions, like a frag-finn-user, we explicitly define the cards
     * this user can see. All other cards will be hidden.
     *
     * @param user
     * @return
     */
    private List<AccessRight> getAccessRulesForUser(UserModule user) {
        List<AccessRight> accessRights = new ArrayList<>();

        UserProfileModule profile = dataSource.get(UserProfileModule.class, user.getAssociatedProfileId());

        if (profile == null) {
            return Collections.emptyList();
        }

        Set<Integer> accessible = profile.getAccessibleSitesPackages();

        if (accessible != null && accessible.stream().anyMatch(id -> id.equals(DefaultEntities.PARENTAL_CONTROL_FILTER_LIST_FRAG_FINN))) {
            accessRights.add(AccessRight.FRAG_FINN);
            accessRights.add(AccessRight.USER);
            accessRights.add(AccessRight.ONLINE_TIME);
        } else if (user.getId().equals(DefaultEntities.PARENTAL_CONTROL_LIMBO_USER_ID)) {
            accessRights.add(AccessRight.USER);
        }

        return accessRights;
    }

    /**
     * The action create user, update user or delete user, may cause
     * - a parental control card to be created or removed
     * 1) created if:
     * a) a CHILD-user is created
     * b) a PARENT/OTHER user is updated CHILD
     * 2) removed if:
     * a) a CHILD-user is deleted
     * b) a CHILD-user is updated to PARENT / OTHER
     * - the parental control card to be added or removed from the dashboardColumnsView of each user
     * - easiest way: just re-create the dashboardColumnsView based on all dashboard cards (including the
     * new parental control card) and the user role of each user (including the created / updated user)
     * New/removed child user results in new/removed dashboard card for that user
     * Caused by: create user, remove user, update user (change of user type to/from child)
     *
     * @param id          user id. Used for child-user to set the referencingUserId on the parental control card
     * @param updatedRole the role of the new user or new role of an user being updated
     * @param oldRole     the old role (only set when user is updated)
     */
    private void checkAndUpdateParentalControlData(int id, UserRole updatedRole, UserRole oldRole) {
        if (updatedRole != null && updatedRole.equals(UserRole.CHILD) &&
                (oldRole == null || !oldRole.equals(UserRole.CHILD))) {
            // newRole is CHILD and
            // either: oldRole is null (create)
            // or:     oldRole is not CHILD (update PARENT/OTHER to CHILD)
            dashboardCardService.createParentalControlCard(id, "PARENTAL_CONTROL", "FAM");
            updateDashboardOfAllParents();
        } else if ((updatedRole == null && oldRole == null) ||
                (updatedRole != null && !updatedRole.equals(UserRole.CHILD) &&
                        oldRole != null && oldRole.equals(UserRole.CHILD))) {
            // either: both null (remove user)
            // or:     changed from CHILD to non CHILD (OTHER/PARENT)
            dashboardCardService.removeParentalControlCard(id);
            updateDashboardOfAllParents();
        }
    }

    public void restoreDefaultSystemUserAsUsers(Device device) {
        UserModule defaultSystemUser = restoreDefaultSystemUser(device.getId());
        device.setDefaultSystemUser(defaultSystemUser.getId());
        device.setAssignedUser(defaultSystemUser.getId());
        device.setOperatingUser(defaultSystemUser.getId());
    }

    /** Merge only the requested fields into a fresh record; never replace a concurrently changed PIN. */
    public UserModule patchSettings(int id, FamilySettingsPatch patch) {
        UserModule updated;
        synchronized (roleChangeLock) {
            UserModule current;
            synchronized (mutationLock(id)) {
                current = dataSource.get(UserModule.class, id);
                if (current == null) throw new org.restexpress.exception.NotFoundException("error.family.userNotFound");
                if (current.isSystem()) throw new org.restexpress.exception.ForbiddenException("error.family.systemUser");
                updated = patch.applyUser(copyUser(current));
                Integer profileId = updated.getAssociatedProfileId();
                if (profileId == null || profileId == DefaultEntities.PARENTAL_CONTROL_LIMBO_PROFILE_ID
                        || dataSource.get(UserProfileModule.class, profileId) == null) {
                    throw new BadRequestException("error.family.invalidProfile");
                }
                if (!isUniqueCustomerCreatedName(id, updated.getName())) throw new ConflictException("Name of user must be unique");
                updated.setDashboardColumnsView(getDashboardForUser(updated, updated.getUserRole()));
                commitUser(current, updated);
            }
            // Dependent dashboard updates are never triggered for a rejected CAS.
            checkAndUpdateParentalControlData(id, updated.getUserRole(), current.getUserRole());
        }
        notifyListeners(updated);
        return updated;
    }
}
