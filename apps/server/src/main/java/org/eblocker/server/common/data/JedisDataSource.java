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
package org.eblocker.server.common.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.apache.commons.codec.binary.Base64;
import org.eblocker.server.common.data.openvpn.ExternalAddressType;
import org.eblocker.server.common.data.openvpn.PortForwardingMode;
import org.eblocker.server.common.data.systemstatus.SubSystem;
import org.eblocker.server.common.startup.SubSystemService;
import org.eblocker.server.common.transaction.Decision;
import org.eblocker.server.common.update.AutomaticUpdaterConfiguration;
import org.eblocker.server.http.ssl.AppWhitelistModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisDataException;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;

@Singleton
@SubSystemService(value = SubSystem.DATABASE_CLIENT, allowUninitializedCalls = false)
public class JedisDataSource implements DataSource {

    private static final Logger LOG = LoggerFactory.getLogger(JedisDataSource.class);

    private static final String KEY_NAME = "name";
    private static final String KEY_VERSION = "version";
    private static final String KEY_USERS = "users";
    private static final String KEY_AUTOUPDATE = "autoupdate";
    private static final String KEY_AUTOUPDATE_ACTIVE = "autoupdate_active";
    private static final String KEY_LASTUPDATE = "lastUpdate";
    private static final String KEY_MALWARE_FILTER_ENABLED = "malware_filter_enabled";
    private static final String KEY_SSL_ENABLED = "ssl_enabled";
    private static final String KEY_SSL_RECORD_ERRORS = "ssl_record_errors";
    private static final String KEY_WEBRTC_BLOCKING_STATE = "webrtc_block_enabled";
    private static final String KEY_HTTP_REFERER_REMOVE_STATE = "http_referer_remove_enabled";
    private static final String KEY_GOOGLE_CAPTIVE_PORTAL_CHECK_RESPONDER_STATE = "google_CPC_responder_enabled";
    private static final String KEY_DNT_HEADER_STATE = "dnt_header_enabled";
    private static final String KEY_DO_NOT_SHOW_REMINDER = "do_not_show_reminder";
    private static final String KEY_SHOW_SPLASH_SCREEN = "showSplashScreen";
    private static final String KEY_AUTO_ENABLE_NEW_DEVICES = "autoEnableNewDevices";
    private static final String KEY_COMPRESSION_MODE = "compression_mode";
    private static final String VALUE_FALSE = "false";
    private static final String VALUE_TRUE = "true";

    private static final int MAX_DATABASES = 16;
    private static final String KEY_LANGUAGE = "frontend_language";
    private static final String KEY_TIMEZONE = "timezone";

    private static final String KEY_CLEAN_SHUTDOWN = "clean_shutdown";

    private static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled";

    static final String KEY_DEVICE_SCANNING_INTERVAL = JedisDeviceRepository.KEY_DEVICE_SCANNING_INTERVAL;
    static final String KEY_DEVICE_LAST_SEEN = JedisDeviceRepository.KEY_DEVICE_LAST_SEEN;

    private final JedisPool pool;
    private final JedisNetworkConfiguration networkConfiguration;
    private final JedisEntityRepository entities;
    private final JedisDeviceRepository devices;
    private final ObjectMapper objectMapper;

    @Inject
    public JedisDataSource(JedisPool pool, ObjectMapper objectMapper) {
        this.pool = pool;
        this.networkConfiguration = new JedisNetworkConfiguration(pool, objectMapper);
        this.entities = new JedisEntityRepository(pool, objectMapper);
        this.devices = new JedisDeviceRepository(pool, this::getGateway);
        this.objectMapper = objectMapper;
    }

    /* Generic DAO methods */
    protected String getKey(Class<?> entityClass, int id) {
        return entities.getKey(entityClass, id);
    }

    protected String getKey(Class<?> entityClass) {
        return entities.getKey(entityClass);
    }

    protected String getIdSequenceKey(Class<?> entityClass) {
        return entities.getIdSequenceKey(entityClass);
    }

    //TODO: Should be renamed to getKeys(), as it returns the complete keys, not only the numerical ids.
    @Override
    public SortedSet<String> getIds(Class<?> entityClass) {
        return entities.getIds(entityClass);
    }

    @Override
    public <T> T get(Class<T> entityClass, int id) {
        return entities.get(entityClass, id);
    }

    @Override
    public <T> T get(Class<T> entityClass) {
        return entities.get(entityClass);
    }

    protected <T> T get(Class<T> entityClass, String id) {
        return entities.get(entityClass, id);
    }

    @Override
    public <T> List<T> getAll(Class<T> entityClass) {
        return entities.getAll(entityClass);
    }

    @Override
    public <T> T save(T entity, int id) {
        return entities.save(entity, id);
    }

    @Override
    public boolean compareAndSetUserPin(int userId, byte[] expectedPin, byte[] replacementPin) {
        return entities.compareAndSetUserPin(userId, expectedPin, replacementPin);
    }

    @Override
    public boolean compareAndSetUser(UserModule expected, UserModule replacement) {
        return entities.compareAndSetUser(expected, replacement);
    }

    @Override
    public <T> T save(T entity) {
        return entities.save(entity);
    }

    @Override
    public void delete(Class<?> entityClass, int id) {
        entities.delete(entityClass, id);
    }

    @Override
    public void delete(Class<?> entityClass) {
        entities.delete(entityClass);
    }

    @Override
    public Set<String> keys(String globPattern) {
        return entities.keys(globPattern);
    }

    @Override
    public void delete(String key) {
        entities.delete(key);
    }

    @Override
    public void deleteAll(Class<?> entityClass) {
        entities.deleteAll(entityClass);
    }

    @Override
    public int nextId(Class<?> entityClass) {
        return entities.nextId(entityClass);
    }

    @Override
    public void setIdSequence(Class<?> entityClass, int value) {
        entities.setIdSequence(entityClass, value);
    }

    /* specific database access methods */

    @Override
    public String getGateway() {
        return networkConfiguration.getGateway();
    }

    @Override
    public void setGateway(String gateway) {
        networkConfiguration.setGateway(gateway);
    }

    @Override
    public SortedSet<String> getDeviceIds() {
        return devices.getDeviceIds();
    }

    @Override
    public Set<Device> getDevices() {
        return devices.getDevices();
    }

    @Override
    public Long getDeviceScanningInterval() {
        return devices.getDeviceScanningInterval();
    }

    @Override
    public void setDeviceScanningInterval(Long seconds) {
        devices.setDeviceScanningInterval(seconds);
    }

    @Override
    public Device getDevice(String deviceId) {
        return devices.getDevice(deviceId);
    }

    @Override
    public void save(Device device) {
        devices.save(device);
    }

    @Override
    public void updateLastSeen(Device device) {
        devices.updateLastSeen(device);
    }

    @Override
    public void setIpAddressesFixed(boolean fixed) {
        devices.setIpAddressesFixed(fixed);
    }

    @Override
    public boolean isIpFixedByDefault() {
        return devices.isIpFixedByDefault();
    }

    @Override
    public boolean isExpertMode() {
        return networkConfiguration.isExpertMode();
    }

    @Override
    public void setIsExpertMode(boolean expert) {
        networkConfiguration.setIsExpertMode(expert);
    }

    @Override
    public void setIpFixedByDefault(boolean ipFixedByDefault) {
        devices.setIpFixedByDefault(ipFixedByDefault);
    }

    @Override
    public Set<String> getUserIds() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.smembers(KEY_USERS);
        }
    }

    @Override
    @Deprecated
    public void save(User user) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> map = new HashMap<String, String>();
            map.put(KEY_NAME, user.getName());

            jedis.hmset(user.getId(), map);
        }
    }

    @Override
    @Deprecated
    public User addUser(User user) {
        Set<String> userIds = getUserIds();
        for (int i = 1; i <= MAX_DATABASES; i++) {
            String newId = User.ID_PREFIX + i;
            if (!userIds.contains(newId)) {
                user.setId(newId);
                try (Jedis jedis = pool.getResource()) {
                    jedis.sadd(KEY_USERS, newId);
                }
                save(user);
                return user;
            }
        }
        return null;
    }

    @Override
    @Deprecated
    public void delete(User user) {
        try (Jedis jedis = pool.getResource()) {
            jedis.srem(KEY_USERS, user.getId());
            jedis.del(user.getId());
        }
    }

    @Override
    public void delete(Device device) {
        devices.delete(device);
    }

    @Override
    public String getVersion() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(KEY_VERSION);
        }
    }

    @Override
    public void setVersion(String version) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_VERSION, version);
        }
    }

    @Override
    public void setCurrentNetworkState(NetworkStateId networkState) {
        networkConfiguration.setCurrentNetworkState(networkState);
    }

    @Override
    public NetworkStateId getCurrentNetworkState() {
        return networkConfiguration.getCurrentNetworkState();
    }

    @Override
    public void setDhcpRange(DhcpRange range) {
        networkConfiguration.setDhcpRange(range);
    }

    @Override
    public DhcpRange getDhcpRange() {
        return networkConfiguration.getDhcpRange();
    }

    @Override
    public void clearDhcpRange() {
        networkConfiguration.clearDhcpRange();
    }

    @Override
    public void createSnapshot() {
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.bgsave();
            LOG.debug("BGSave returned: {}", result);

        } catch (JedisDataException e) {
            LOG.error("Cannot save DB: {}", e.getMessage());
            //FIXME handle exception
            //is appearing when there is already a save instance running
        }
    }

    @Override
    public void saveSynchronously() {
        try (Jedis jedis = pool.getResource()) {
            String result = jedis.save();
            LOG.debug("Save returned: {}", result);

        } catch (JedisDataException e) {
            LOG.error("Cannot save DB synchronously: {}", e.getMessage());
            //FIXME handle exception
            //is appearing when there is already a save instance running
        }
    }

    @Override
    public void saveLastUpdateTime(LocalDateTime lastUpdate) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_LASTUPDATE, lastUpdate.toString());
        }
    }

    @Override
    public LocalDateTime getLastUpdateTime() {
        try (Jedis jedis = pool.getResource()) {
            String lastUpdateString = jedis.get(KEY_LASTUPDATE);
            if (lastUpdateString != null) {
                try {
                    LocalDateTime lastUpdate = LocalDateTime.parse(lastUpdateString);
                    return lastUpdate;
                } catch (DateTimeParseException e) {
                    return null;
                }
            }
            return null;
        }
    }

    @Override
    public AutomaticUpdaterConfiguration getAutomaticUpdateConfig() {
        try (Jedis jedis = pool.getResource()) {
            String configJSON = jedis.get(KEY_AUTOUPDATE);
            if (configJSON == null)
                return null;
            AutomaticUpdaterConfiguration config = null;
            try {
                config = objectMapper.readValue(configJSON, AutomaticUpdaterConfiguration.class);

            } catch (IOException e) {
                LOG.error("Error in automatic update config", e);
            }
            return config;
        }
    }

    @Override
    public void save(AutomaticUpdaterConfiguration configuration) {
        if (configuration != null) {
            try (Jedis jedis = pool.getResource()) {
                String configJSON = configuration.toJSONString();
                jedis.set(KEY_AUTOUPDATE, configJSON);
            }
        }
    }

    @Override
    public void setAutomaticUpdatesActivated(boolean activated) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_AUTOUPDATE_ACTIVE, activated ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public String getAutomaticUpdatesActivated() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(KEY_AUTOUPDATE_ACTIVE);
        }
    }

    @Override
    public boolean getSSLEnabledState() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_SSL_ENABLED);

            // default if not set is ON:
            if (value == null) {
                return false;
            }

            return value.equals(VALUE_TRUE);
        }

    }

    @Override
    public void setSSLEnabledState(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_SSL_ENABLED, enabled ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public void setOpenVpnServerState(boolean state) {
        networkConfiguration.setOpenVpnServerState(state);
    }

    @Override
    public void setOpenVpnServerHost(String host) {
        networkConfiguration.setOpenVpnServerHost(host);
    }

    @Override
    public String getOpenVpnServerHost() {
        return networkConfiguration.getOpenVpnServerHost();
    }

    @Override
    public Integer getOpenVpnMappedPort() {
        return networkConfiguration.getOpenVpnMappedPort();
    }

    @Override
    public void setOpenVpnMappedPort(Integer port) {
        networkConfiguration.setOpenVpnMappedPort(port);
    }

    @Override
    public PortForwardingMode getOpenVpnPortForwardingMode() {
        return networkConfiguration.getOpenVpnPortForwardingMode();
    }

    @Override
    public void setOpenVpnPortForwardingMode(PortForwardingMode mode) {
        networkConfiguration.setOpenVpnPortForwardingMode(mode);
    }

    @Override
    public ExternalAddressType getOpenVpnExternalAddressType() {
        return networkConfiguration.getOpenVpnExternalAddressType();
    }

    @Override
    public void setOpenVpnExternalAddressType(ExternalAddressType type) {
        networkConfiguration.setOpenVpnExternalAddressType(type);
    }

    @Override
    public boolean getOpenVpnServerState() {
        return networkConfiguration.getOpenVpnServerState();
    }

    @Override
    public void setOpenVpnServerFirstRun(boolean state) {
        networkConfiguration.setOpenVpnServerFirstRun(state);
    }

    @Override
    public boolean getOpenVpnServerFirstRun() {
        return networkConfiguration.getOpenVpnServerFirstRun();
    }

    @Override
    public void saveCurrentTorExitNodes(Set<String> selectedCountries) {
        networkConfiguration.saveCurrentTorExitNodes(selectedCountries);
    }

    @Override
    public Set<String> getCurrentTorExitNodes() {
        return networkConfiguration.getCurrentTorExitNodes();
    }

    @Override
    public void setWebRTCBlockingState(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_WEBRTC_BLOCKING_STATE, enabled ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public boolean getWebRTCBlockingState() {
        try (Jedis jedis = pool.getResource()) {
            String state = jedis.get(KEY_WEBRTC_BLOCKING_STATE);
            if (state != null) {
                return state.equals(VALUE_TRUE);
            }
        }
        //default
        return false;
    }

    @Override
    public void setHTTPRefererRemovingState(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_HTTP_REFERER_REMOVE_STATE, enabled ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public boolean getHTTPRefererRemovingState() {
        try (Jedis jedis = pool.getResource()) {
            String state = jedis.get(KEY_HTTP_REFERER_REMOVE_STATE);
            if (state != null) {
                return state.equals(VALUE_TRUE);
            }
        }
        //default
        return false;
    }

    @Override
    public void setGoogleCaptivePortalRedirectorState(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_GOOGLE_CAPTIVE_PORTAL_CHECK_RESPONDER_STATE, enabled ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    /**
     * Returns true if eBlocker should respond to Google captive portal check requests.
     * <p>
     * If the option is not set in Redis, true is returned.
     */
    @Override
    public boolean getGoogleCaptivePortalRedirectorState() {
        try (Jedis jedis = pool.getResource()) {
            String state = jedis.get(KEY_GOOGLE_CAPTIVE_PORTAL_CHECK_RESPONDER_STATE);
            if (state != null) {
                return state.equals(VALUE_TRUE);
            }
        }
        //default
        return true;
    }

    @Override
    public void setDntHeaderState(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_DNT_HEADER_STATE, enabled ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    /**
     * Returns true the the eBlocker should add the DNT Header
     * <p>
     * If the option is not set in Redis, false is returned.
     */
    @Override
    public boolean getDntHeaderState() {
        try (Jedis jedis = pool.getResource()) {
            String state = jedis.get(KEY_DNT_HEADER_STATE);
            if (state != null) {
                return state.equals(VALUE_TRUE);
            }
        }
        // default
        return false;
    }

    @Override
    public void setDoNotShowReminder(boolean show) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_DO_NOT_SHOW_REMINDER, show ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    /**
     * Returns true if the eBlocker should not display the license expiration reminder to the user
     * <p>
     * If the option is not set in Redis, false is returned.
     */
    @Override
    public boolean isDoNotShowReminder() {
        try (Jedis jedis = pool.getResource()) {
            String show = jedis.get(KEY_DO_NOT_SHOW_REMINDER);
            if (show != null) {
                return show.equals(VALUE_TRUE);
            }
        } // default
        return false;
    }

    @Override
    public void setShowSplashScreen(boolean show) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_SHOW_SPLASH_SCREEN, show ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public boolean isShowSplashScreen() {
        try (Jedis jedis = pool.getResource()) {
            String show = jedis.get(KEY_SHOW_SPLASH_SCREEN);
            if (show != null) {
                return show.equals(VALUE_TRUE);
            }
        } // default
        return true;
    }

    @Override
    public void setAutoEnableNewDevices(boolean autoEnableNewDevices) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_AUTO_ENABLE_NEW_DEVICES, autoEnableNewDevices ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public boolean isAutoEnableNewDevices() {
        try (Jedis jedis = pool.getResource()) {
            String autoEnableNewDevices = jedis.get(KEY_AUTO_ENABLE_NEW_DEVICES);
            if (autoEnableNewDevices != null) {
                return autoEnableNewDevices.equals(VALUE_TRUE);
            }
        } // default
        return true;
    }

    @Override
    public CompressionMode getCompressionMode() {
        try (Jedis jedis = pool.getResource()) {
            return CompressionMode.failSafeValueOf(jedis.get(KEY_COMPRESSION_MODE));
        }
    }

    @Override
    public void setCompressionMode(CompressionMode compressionMode) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_COMPRESSION_MODE, compressionMode.name());
        }
    }

    @Override
    public boolean getSslRecordErrors() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_SSL_RECORD_ERRORS);
            return value != null && Boolean.parseBoolean(value);
        }
    }

    @Override
    public void setSslRecordErrors(boolean recordSslErrors) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_SSL_RECORD_ERRORS, Boolean.toString(recordSslErrors));
        }
    }

    @Override
    public Language getCurrentLanguage() {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> map = jedis.hgetAll(KEY_LANGUAGE);
            String langID = map.get("ID");
            String langName = map.get("name");
            if (langID != null && !"".equals(langID) && langName != null) {
                return new Language(langID, langName);
            }
        }
        return new Language("en", "English");
    }

    @Override
    public void setCurrentLanguage(Language language) {
        if (language != null) {
            try (Jedis jedis = pool.getResource()) {
                Map<String, String> map = new HashMap<>();

                map.put("ID", language.getId());
                map.put("name", language.getName());

                jedis.hmset(KEY_LANGUAGE, map);
            }
        }
    }

    @Override
    public void setTimezone(String posixString) {
        if (posixString != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.set(KEY_TIMEZONE, posixString);
            }
        }
    }

    @Override
    public String getTimezone() {
        String timezone = null;
        try (Jedis jedis = pool.getResource()) {
            timezone = jedis.get(KEY_TIMEZONE);
        }
        return timezone;
    }

    public AppWhitelistModule.State getAppModuleState(int moduleID) {
        if (moduleID >= 0) {//only positive IDs allowed
            try (Jedis jedis = pool.getResource()) {
                String state = jedis.get("appmodule:" + moduleID);
                if (state == null) {
                    return AppWhitelistModule.State.DEFAULT;
                } else if (state.equals(VALUE_TRUE)) {
                    return AppWhitelistModule.State.ENABLED;
                }
                return AppWhitelistModule.State.DISABLED;
            }
        }
        return AppWhitelistModule.State.MODULE_NOT_FOUND;
    }

    @Override
    public void setAppWhitelistModuleStatus(int moduleID, boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set("appmodule:" + moduleID, enabled ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    public Decision getRedirectDecision(String sessionId, String domain) {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.hget("session:" + sessionId, "redirectDecision::" + domain);
            for (Decision decision : Decision.values()) {
                if (decision.toString().equals(value)) {
                    return decision;
                }
            }
            return Decision.NO_DECISION;
        }
    }

    @Override
    public void setRedirectDecision(String sessionId, String domain, Decision decision) {
        try (Jedis jedis = pool.getResource()) {
            jedis.hset("session:" + sessionId, "redirectDecision::" + domain, decision.toString());
        }
    }

    @Override
    public void setPasswordHash(byte[] passwordHash) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set("consolePassword", Base64.encodeBase64String(passwordHash));
        }
    }

    @Override
    public byte[] getPasswordHash() {
        try (Jedis jedis = pool.getResource()) {
            return Base64.decodeBase64(jedis.get("consolePassword"));
        }
    }

    @Override
    public void deletePasswordHash() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del("consolePassword");
        }
    }

    @Override
    public String getListsPackageVersion() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get("lists-version");
        }
    }

    @Override
    public boolean getCleanShutdownFlag() {
        try (Jedis jedis = pool.getResource()) {
            String clean = jedis.get(KEY_CLEAN_SHUTDOWN);
            if (clean == null) {
                return true; // default if this feature is used for the first time: assume the last shutdown was clean
            }
            return clean.equals(VALUE_TRUE);
        }
    }

    @Override
    public void setCleanShutdownFlag(boolean cleanShutdown) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_CLEAN_SHUTDOWN, cleanShutdown ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    @Override
    public String getResolvedDnsGateway() {
        return networkConfiguration.getResolvedDnsGateway();
    }

    @Override
    public void setResolvedDnsGateway(String gateway) {
        networkConfiguration.setResolvedDnsGateway(gateway);
    }

    @Override
    public Integer getDhcpLeaseTime() {
        return networkConfiguration.getDhcpLeaseTime();
    }

    @Override
    public void setDhcpLeaseTime(Integer leaseTime) {
        networkConfiguration.setDhcpLeaseTime(leaseTime);
    }

    @Override
    public boolean isMalwareUrlFilterEnabled() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_MALWARE_FILTER_ENABLED);
            return value == null || Boolean.parseBoolean(value);
        }
    }

    @Override
    public void setMalwareUrlFilterEnabled(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_MALWARE_FILTER_ENABLED, Boolean.toString(enabled));
        }
    }

    @Override
    public boolean isContentFilterEnabled() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_CONTENT_FILTER_ENABLED);
            if (value == null) {
                return false; // content filter is disabled by default
            }
            return Boolean.parseBoolean(value);
        }
    }

    @Override
    public void setContentFilterEnabled(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_CONTENT_FILTER_ENABLED, Boolean.toString(enabled));
        }
    }

    @Override
    public boolean areRouterAdvertisementsEnabled() {
        return networkConfiguration.areRouterAdvertisementsEnabled();
    }

    @Override
    public void setRouterAdvertisementsEnabled(boolean enabled) {
        networkConfiguration.setRouterAdvertisementsEnabled(enabled);
    }

    @Override
    public boolean arePrivacyExtensionsEnabled() {
        return networkConfiguration.arePrivacyExtensionsEnabled();
    }

    @Override
    public void setPrivacyExtensionsEnabled(boolean enabled) {
        networkConfiguration.setPrivacyExtensionsEnabled(enabled);
    }
}
