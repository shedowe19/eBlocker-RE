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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Stores devices using the existing Redis hash schema.
 *
 * Missing fields retain the defaults required by older installations. Device settings are
 * updated in place so fields owned by discovery, especially lastSeen, are not overwritten.
 * JedisDataSource remains the public database facade; this repository owns device persistence.
 */
final class JedisDeviceRepository {

    private static final Logger LOG = LoggerFactory.getLogger(JedisDeviceRepository.class);

    private static final String KEY_NAME = "name";
    private static final String KEY_IP_ADDRESS = "ipAddress";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PAUSED = "paused";
    private static final String KEY_MALWARE_FILTER_ENABLED = "malware_filter_enabled";
    private static final String KEY_SSL_ENABLED = "ssl_enabled";
    private static final String KEY_SSL_RECORD_ERRORS = "ssl_record_errors";
    private static final String KEY_ROOT_CA_INSTALLED = "root_ca_installed";
    private static final String KEY_DOMAIN_RECORDING_ENABLED = "domain_recording_enabled";
    private static final String VALUE_FALSE = "false";
    private static final String VALUE_TRUE = "true";
    private static final String KEY_MESSAGE_SHOW_INFO = "messageShowInfo";
    private static final String KEY_MESSAGE_SHOW_ALERT = "messageShowAlert";
    private static final String KEY_SHOW_PAUSE_DIALOG = "showPauseDialog";
    private static final String KEY_PAUSE_DIALOG_DO_NOT_SHOW_AGAIN = "showPauseDialogDoNotShowAgain";
    private static final String KEY_SHOW_DNS_FILTER_UPDATE_INFO = "showDnsFilterInfoDialog";
    private static final String KEY_SHOW_BOOKMARK_DIALOG = "showBookmarkDialog";
    private static final String KEY_SHOW_WELCOME_PAGE = "showWelcomePage";
    private static final String KEY_IS_CONTROLBAR_AUTO_MODE = "isControlBarAutoMode";
    private static final String KEY_IS_MOBILE_ENABLED = "isMobileEnabled";
    private static final String KEY_MOBILE_PRIVATE_NETWORK_ACCESS = "mobilePrivateNetworkAccess";
    private static final String KEY_USE_ANONYMIZATION_SERVICE = "useAnonymizationService";
    private static final String KEY_USE_TOR = "useTor";
    private static final String KEY_SHOW_WARNINGS = "showWarnings";
    private static final String KEY_ICON_MODE = "display_icon_mode";
    private static final String KEY_ICON_POSITION = "display_icon_position";
    private static final String KEY_VPNPROFILE_ID = "vpn_profile_id";
    private static final String KEY_DHCP_FIXED_IP = "dhcp_fixed_ip";
    private static final String KEY_DHCP_IP_FIXED_BY_DEFAULT = "dhcp_ip_fixed_by_default";
    private static final String KEY_DHCP_STATIC_IP = "dhcp_static_ip";
    private static final String KEY_DHCP_STATIC_IPV6 = "dhcp_static_ipv6";
    private static final String KEY_PARENTAL_CONTROL_USER_ID = "parentalControlUserId";
    private static final String KEY_PARENTAL_CONTROL_OPERATING_USER_ID = "parentalControlOperatingUserId";
    private static final String KEY_DEFAULT_SYSTEM_USER_ID = "defaultSystemUserId";
    private static final String KEY_IS_OPENVPN_CLIENT = "IsOpenVpnClient";
    private static final String KEY_FILTER_MODE = "filter_mode";
    private static final String KEY_FILTER_PLUG_AND_PLAY_ADS_ENABLED = "filter_plug_and_play_ads_enabled";
    private static final String KEY_FILTER_PLUG_AND_PLAY_TRACKERS_ENABLED = "filter_plug_and_play_trackers_enabled";
    static final String KEY_DEVICE_SCANNING_INTERVAL = "deviceScanningInterval";
    static final String KEY_DEVICE_LAST_SEEN = "lastSeen";

    private final JedisPool pool;
    private final Supplier<String> gatewayProvider;

    JedisDeviceRepository(JedisPool pool, Supplier<String> gatewayProvider) {
        this.pool = pool;
        this.gatewayProvider = gatewayProvider;
    }

    SortedSet<String> getDeviceIds() {
        try (Jedis jedis = pool.getResource()) {
            return new TreeSet<>(jedis.keys(Device.ID_PREFIX + "*"));
        }
    }

    Set<Device> getDevices() {
        SortedSet<String> deviceIds = getDeviceIds();
        Set<Device> devices = new HashSet<>();

        for (String deviceId : deviceIds) {
            Device device = getDevice(deviceId);
            if (device != null) {
                devices.add(device);
            }
        }
        return devices;
    }

    Long getDeviceScanningInterval() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_DEVICE_SCANNING_INTERVAL);
            if (value == null) {
                return null;
            } else {
                return Long.valueOf(value);
            }
        }
    }

    void setDeviceScanningInterval(Long seconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_DEVICE_SCANNING_INTERVAL, seconds.toString());
        }
    }

    Device getDevice(String deviceId) {
        Map<String, String> map;
        try (Jedis jedis = pool.getResource()) {
            map = jedis.hgetAll(deviceId);
        }

        if (map.isEmpty()) {
            return null;
        }

        Device device = new Device();
        device.setId(deviceId);
        device.setName(map.get(KEY_NAME));

        if (map.get(KEY_IP_ADDRESS) != null) {
            List<IpAddress> ipAddresses = new ArrayList<>();
            for (String ip : map.get(KEY_IP_ADDRESS).split(",")) {
                ipAddresses.add(IpAddress.parse(ip));
            }
            device.setIpAddresses(ipAddresses);
        } else {
            device.setIpAddresses(Collections.emptyList());
        }

        String enabled = map.get(KEY_ENABLED);
        device.setEnabled((enabled == null || enabled.equals(VALUE_TRUE)));
        String paused = map.get(KEY_PAUSED);
        device.setPaused((paused != null && paused.equals(VALUE_TRUE)));

        String showMessageInfo = map.get(KEY_MESSAGE_SHOW_INFO);
        device.setMessageShowInfo((showMessageInfo == null || showMessageInfo.equals(VALUE_TRUE)));
        String showMessageAlert = map.get(KEY_MESSAGE_SHOW_ALERT);
        device.setMessageShowAlert((showMessageAlert == null || showMessageAlert.equals(VALUE_TRUE)));

        String showPauseDialog = map.get(KEY_SHOW_PAUSE_DIALOG);
        device.setShowPauseDialog((showPauseDialog == null || showPauseDialog.equals(VALUE_TRUE)));

        String showDnsFilterInfoDialog = map.get(KEY_SHOW_DNS_FILTER_UPDATE_INFO);
        device.setShowDnsFilterInfoDialog((showDnsFilterInfoDialog == null || showDnsFilterInfoDialog.equals(VALUE_TRUE)));

        String showBookmarkDialog = map.get(KEY_SHOW_BOOKMARK_DIALOG);
        device.setShowBookmarkDialog((showBookmarkDialog == null || showBookmarkDialog.equals(VALUE_TRUE)));

        String showWelcomePage = map.get(KEY_SHOW_WELCOME_PAGE);
        device.setShowWelcomePage((showWelcomePage == null || showWelcomePage.equals(VALUE_TRUE)));

        String controlBarAutoMode = map.get(KEY_IS_CONTROLBAR_AUTO_MODE);
        // defaults to false, unless explicitly set to true
        device.setControlBarAutoMode((controlBarAutoMode != null && controlBarAutoMode.equals(VALUE_TRUE)));

        String isMobileEnabled = map.get(KEY_IS_MOBILE_ENABLED);
        // Missing values retain mobile support for older devices.
        device.setMobileState(isMobileEnabled == null || isMobileEnabled.equals(VALUE_TRUE));

        device.setMobilePrivateNetworkAccess(Boolean.parseBoolean(map.get(KEY_MOBILE_PRIVATE_NETWORK_ACCESS)));

        String pauseDialogDoNotShow = map.get(KEY_PAUSE_DIALOG_DO_NOT_SHOW_AGAIN);
        device.setShowPauseDialogDoNotShowAgain((pauseDialogDoNotShow == null || pauseDialogDoNotShow.equals(VALUE_TRUE)));

        device.setRouteThroughTor(VALUE_TRUE.equals(map.get(KEY_USE_TOR)));

        String vpnProfileIDString = map.get(KEY_VPNPROFILE_ID);
        if (vpnProfileIDString != null) {
            device.setUseVPNProfileID(Integer.parseInt(vpnProfileIDString));
        }

        String useAnonymizationService = map.get(KEY_USE_ANONYMIZATION_SERVICE);
        if (useAnonymizationService != null) {
            device.setUseAnonymizationService(VALUE_TRUE.equals(useAnonymizationService));
        } else {
            // deduce value if not present (backward compatibility)
            device.setUseAnonymizationService(device.isRoutedThroughTor() || device.getUseVPNProfileID() != null);
        }

        String parentalControlUserId = map.get(KEY_PARENTAL_CONTROL_USER_ID);
        if (parentalControlUserId != null) {
            device.setAssignedUser(Integer.parseInt(parentalControlUserId));
        }
        String parentalControlOperatingUserId = map.get(KEY_PARENTAL_CONTROL_OPERATING_USER_ID);
        if (parentalControlOperatingUserId != null) {
            device.setOperatingUser(Integer.parseInt(parentalControlOperatingUserId));
        }
        String defaultSystemUserId = map.get(KEY_DEFAULT_SYSTEM_USER_ID);
        if (defaultSystemUserId != null) {
            device.setDefaultSystemUser(Integer.parseInt(defaultSystemUserId));
        }

        String showWarnings = map.get(KEY_SHOW_WARNINGS);
        device.setAreDeviceMessagesSettingsDefault((showWarnings == null || showWarnings.equals(VALUE_TRUE)));

        String gateway = gatewayProvider.get();
        device.setIsGateway(gateway != null && device.getIpAddresses().contains(IpAddress.parse(gateway)));

        String malwareEnabled = map.get(KEY_MALWARE_FILTER_ENABLED);
        device.setMalwareFilterEnabled(malwareEnabled == null || Boolean.parseBoolean(malwareEnabled));

        String sslEnabled = map.get(KEY_SSL_ENABLED);
        if (sslEnabled != null) {
            device.setSslEnabled((sslEnabled.equals(VALUE_TRUE)));
        }

        String sslRecordErrors = map.get(KEY_SSL_RECORD_ERRORS);
        if (sslRecordErrors != null) {
            device.setSslRecordErrorsEnabled(Boolean.parseBoolean(sslRecordErrors));
        }

        String domainRecordingEnabled = map.get(KEY_DOMAIN_RECORDING_ENABLED);
        if (domainRecordingEnabled != null) {
            device.setDomainRecordingEnabled(Boolean.parseBoolean(domainRecordingEnabled));
        }

        String hasRootCAInstalled = map.get(KEY_ROOT_CA_INSTALLED);
        if (hasRootCAInstalled != null) {
            device.setHasRootCAInstalled((hasRootCAInstalled.equals(VALUE_TRUE)));
        }

        String iconModeString = map.get(KEY_ICON_MODE);
        if (iconModeString != null) {
            DisplayIconMode iconMode = DisplayIconMode.valueOf(iconModeString);
            device.setIconMode(iconMode);
        }

        String iconPositionString = map.get(KEY_ICON_POSITION);
        if (iconPositionString != null) {
            Device.DisplayIconPosition iconPosition = Device.DisplayIconPosition.valueOf(iconPositionString);
            device.setIconPosition(iconPosition);
        }

        String fixedString = map.get(KEY_DHCP_FIXED_IP);
        boolean fixed;
        if (fixedString == null) {
            String fixedByDefaultString = map.get(KEY_DHCP_IP_FIXED_BY_DEFAULT);
            if (fixedByDefaultString == null) {
                fixed = true;
            } else {
                fixed = Boolean.valueOf(fixedByDefaultString);
            }
        } else {
            fixed = Boolean.valueOf(fixedString);
        }
        device.setIpAddressFixed(fixed);

        String staticIp = map.get(KEY_DHCP_STATIC_IP);
        if (staticIp != null && !staticIp.isEmpty()) {
            try {
                device.setStaticIpAddress(Ip4Address.parse(staticIp));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring invalid stored static IPv4 address '{}' for device {}", staticIp, device.getId());
            }
        }
        String staticIpV6 = map.get(KEY_DHCP_STATIC_IPV6);
        if (staticIpV6 != null && !staticIpV6.isEmpty()) {
            try {
                device.setStaticIpV6Address(Ip6Address.parse(staticIpV6));
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring invalid stored static IPv6 address '{}' for device {}", staticIpV6, device.getId());
            }
        }

        String isVpnClient = map.get(KEY_IS_OPENVPN_CLIENT);
        if (isVpnClient != null) {
            device.setIsVpnClient(isVpnClient.equals(VALUE_TRUE));
        }

        String filterModeName = map.get(KEY_FILTER_MODE);
        if (filterModeName != null) {
            device.setFilterMode(FilterMode.valueOf(filterModeName));
            device.setFilterAdsEnabled(Boolean.parseBoolean(map.get(KEY_FILTER_PLUG_AND_PLAY_ADS_ENABLED)));
            device.setFilterTrackersEnabled(Boolean.parseBoolean(map.get(KEY_FILTER_PLUG_AND_PLAY_TRACKERS_ENABLED)));
        }

        String lastSeen = map.get(KEY_DEVICE_LAST_SEEN);
        if (lastSeen != null) {
            device.setLastSeen(Instant.ofEpochMilli(Long.valueOf(lastSeen)));
        }

        return device;
    }

    void save(Device device) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> map = new HashMap<>();
            if (device.getName() != null) {
                map.put(KEY_NAME, device.getName());
            }

            if (!device.getIpAddresses().isEmpty()) {
                map.put(KEY_IP_ADDRESS, device.getIpAddresses().stream()
                        .map(IpAddress::toString)
                        .collect(Collectors.joining(",")));
            } else {
                jedis.hdel(device.getId(), KEY_IP_ADDRESS);
            }

            map.put(KEY_MESSAGE_SHOW_INFO, device.isMessageShowInfo() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_MESSAGE_SHOW_ALERT, device.isMessageShowAlert() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_SHOW_PAUSE_DIALOG, device.isShowPauseDialog() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_PAUSE_DIALOG_DO_NOT_SHOW_AGAIN, device.isShowPauseDialogDoNotShowAgain() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_SHOW_DNS_FILTER_UPDATE_INFO, device.isShowDnsFilterInfoDialog() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_SHOW_WELCOME_PAGE, device.isShowWelcomePage() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_SHOW_BOOKMARK_DIALOG, device.isShowBookmarkDialog() ? VALUE_TRUE : VALUE_FALSE);

            map.put(KEY_IS_CONTROLBAR_AUTO_MODE, device.isControlBarAutoMode() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_IS_MOBILE_ENABLED, device.isEblockerMobileEnabled() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_MOBILE_PRIVATE_NETWORK_ACCESS, Boolean.toString(device.isMobilePrivateNetworkAccess()));
            map.put(KEY_USE_ANONYMIZATION_SERVICE, device.isUseAnonymizationService() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_USE_TOR, device.isRoutedThroughTor() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_MALWARE_FILTER_ENABLED, Boolean.toString(device.isMalwareFilterEnabled()));
            map.put(KEY_SSL_ENABLED, Boolean.toString(device.isSslEnabled()));
            map.put(KEY_SSL_RECORD_ERRORS, Boolean.toString(device.isSslRecordErrorsEnabled()));
            map.put(KEY_DOMAIN_RECORDING_ENABLED, Boolean.toString(device.isDomainRecordingEnabled()));
            map.put(KEY_ROOT_CA_INSTALLED, device.hasRootCAInstalled() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_ICON_MODE, device.getIconMode().name());
            map.put(KEY_ICON_POSITION, device.getIconPosition().name());
            map.put(KEY_ENABLED, device.isEnabled() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_PAUSED, device.isPaused() ? VALUE_TRUE : VALUE_FALSE);
            if (device.getUseVPNProfileID() != null) {
                map.put(KEY_VPNPROFILE_ID, device.getUseVPNProfileID().toString());
            } else {
                jedis.hdel(device.getId(), KEY_VPNPROFILE_ID);
            }
            map.put(KEY_SHOW_WARNINGS, device.getAreDeviceMessagesSettingsDefault() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_DHCP_FIXED_IP, device.isIpAddressFixed() ? VALUE_TRUE : VALUE_FALSE);
            if (device.getStaticIpAddress() != null) {
                map.put(KEY_DHCP_STATIC_IP, device.getStaticIpAddress().toString());
            } else {
                jedis.hdel(device.getId(), KEY_DHCP_STATIC_IP);
            }
            if (device.getStaticIpV6Address() != null) {
                map.put(KEY_DHCP_STATIC_IPV6, device.getStaticIpV6Address().toString());
            } else {
                jedis.hdel(device.getId(), KEY_DHCP_STATIC_IPV6);
            }
            map.put(KEY_PARENTAL_CONTROL_USER_ID, String.valueOf(device.getAssignedUser()));
            map.put(KEY_PARENTAL_CONTROL_OPERATING_USER_ID, String.valueOf(device.getOperatingUser()));
            map.put(KEY_DEFAULT_SYSTEM_USER_ID, String.valueOf(device.getDefaultSystemUser()));
            map.put(KEY_IS_OPENVPN_CLIENT, device.isVpnClient() ? VALUE_TRUE : VALUE_FALSE);
            map.put(KEY_FILTER_MODE, device.getFilterMode().name());
            map.put(KEY_FILTER_PLUG_AND_PLAY_ADS_ENABLED, Boolean.toString(device.isFilterAdsEnabled()));
            map.put(KEY_FILTER_PLUG_AND_PLAY_TRACKERS_ENABLED, Boolean.toString(device.isFilterTrackersEnabled()));
            jedis.hmset(device.getId(), map);
        }
    }

    void updateLastSeen(Device device) {
        Instant lastSeen = device.getLastSeen();
        if (lastSeen != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.hset(device.getId(), KEY_DEVICE_LAST_SEEN, String.valueOf(lastSeen.toEpochMilli()));
            }
        }
    }

    void setIpAddressesFixed(boolean fixed) {
        try (Jedis jedis = pool.getResource()) {
            for (String deviceId : getDeviceIds()) {
                Map<String, String> map = jedis.hgetAll(deviceId);
                map.put(KEY_DHCP_FIXED_IP, (fixed ? VALUE_TRUE : VALUE_FALSE));
                jedis.hmset(deviceId, map);
            }
        }
    }

    boolean isIpFixedByDefault() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_DHCP_IP_FIXED_BY_DEFAULT);
            if (value == null) {
                return false;
            }
            return value.equals(VALUE_TRUE);
        }
    }

    void setIpFixedByDefault(boolean ipFixedByDefault) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_DHCP_IP_FIXED_BY_DEFAULT, ipFixedByDefault ? VALUE_TRUE
                    : VALUE_FALSE);
        }
    }

    void delete(Device device) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(device.getId());
        }
    }
}
