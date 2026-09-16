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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eblocker.server.common.data.openvpn.ExternalAddressType;
import org.eblocker.server.common.data.openvpn.PortForwardingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists gateway, DNS, DHCP, IPv6 and tunnel configuration in the existing Redis schema.
 * Defaults and nullable updates intentionally follow the behavior of older installations.
 */
final class JedisNetworkConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(JedisNetworkConfiguration.class);

    private static final String KEY_GATEWAY = "gateway";
    private static final String KEY_NETWORK_STATE = "networkState";
    private static final String KEY_DHCP_RANGE = "dhcpRange";
    private static final String KEY_IP_ADDRESS_FIRST = "first";
    private static final String KEY_IP_ADDRESS_LAST = "last";
    private static final String VALUE_FALSE = "false";
    private static final String VALUE_TRUE = "true";
    private static final String KEY_RESOLVED_DNS_GATEWAY = "resolved_dns_gateway";
    private static final String KEY_ROUTER_ADVERTISEMENTS_ENABLED = "router_advertisements_enabled";
    private static final String KEY_PRIVACY_EXTENSIONS_ENABLED = "privacy_extensions_enabled";
    private static final String KEY_TOR_CURRENT_EXIT_NODES = "torCurrentExitNodes";
    private static final String KEY_NETWORK_IS_EXPERT_MODE = "network_is_expert_mode";
    private static final String KEY_OPENVPN_SERVER_ENABLED = "OpenVpnServerEnabled";
    private static final String KEY_OPENVPN_FIRST_RUN = "OpenVpnFirstRun";
    private static final String KEY_OPENVPN_SERVER_HOST = "OpenVpnHost";
    private static final String KEY_OPENVPN_MAPPED_PORT = "OpenVpnMappedPort";
    private static final String KEY_OPENVPN_PORT_FORWARDING_MODE = "OpenVpnPortForwardingMode";
    private static final String KEY_OPENVPN_EXTERNAL_ADDRESS_TYPE = "OpenVpnExternalAddressType";
    private static final String KEY_DHCP_LEASE_TIME = "dhcpLeaseTime";

    private final JedisPool pool;
    private final ObjectMapper objectMapper;

    JedisNetworkConfiguration(JedisPool pool, ObjectMapper objectMapper) {
        this.pool = pool;
        this.objectMapper = objectMapper;
    }

    String getGateway() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(KEY_GATEWAY);
        }
    }

    void setGateway(String gateway) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_GATEWAY, gateway);
        }
    }

    boolean isExpertMode() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_NETWORK_IS_EXPERT_MODE);
            return value == null ? false : value.equals(VALUE_TRUE);
        }
    }

    void setIsExpertMode(boolean expert) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_NETWORK_IS_EXPERT_MODE, (expert ? VALUE_TRUE : VALUE_FALSE));
        }
    }

    void setCurrentNetworkState(NetworkStateId networkState) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_NETWORK_STATE, networkState.name());
        }
    }

    NetworkStateId getCurrentNetworkState() {
        try (Jedis jedis = pool.getResource()) {
            String networkStateName = jedis.get(KEY_NETWORK_STATE);
            if (networkStateName == null) {
                return NetworkStateId.PLUG_AND_PLAY; // the default mode
            }
            return NetworkStateId.valueOf(networkStateName);
        }
    }

    void setDhcpRange(DhcpRange range) {
        try (Jedis jedis = pool.getResource()) {
            if (range.getFirstIpAddress() != null) {
                jedis.hset(KEY_DHCP_RANGE, KEY_IP_ADDRESS_FIRST, range.getFirstIpAddress());
            }
            if (range.getLastIpAddress() != null) {
                jedis.hset(KEY_DHCP_RANGE, KEY_IP_ADDRESS_LAST, range.getLastIpAddress());
            }
        }
    }

    DhcpRange getDhcpRange() {
        try (Jedis jedis = pool.getResource()) {
            String firstIpAddress = jedis.hget(KEY_DHCP_RANGE, KEY_IP_ADDRESS_FIRST);
            String lastIpAddress = jedis.hget(KEY_DHCP_RANGE, KEY_IP_ADDRESS_LAST);
            return new DhcpRange(firstIpAddress, lastIpAddress);
        }
    }

    void clearDhcpRange() {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(KEY_DHCP_RANGE);
        }
    }

    void setOpenVpnServerState(boolean state) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_OPENVPN_SERVER_ENABLED, state ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    void setOpenVpnServerHost(String host) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_OPENVPN_SERVER_HOST, host);
        }
    }

    String getOpenVpnServerHost() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(KEY_OPENVPN_SERVER_HOST);
        }
    }

    Integer getOpenVpnMappedPort() {
        try (Jedis jedis = pool.getResource()) {
            String num = jedis.get(KEY_OPENVPN_MAPPED_PORT);
            if (num != null) {
                return Integer.valueOf(num);
            }
            return null;
        }
    }

    void setOpenVpnMappedPort(Integer port) {
        if (port != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.set(KEY_OPENVPN_MAPPED_PORT, port.toString());
            }
        }
    }

    PortForwardingMode getOpenVpnPortForwardingMode() {
        try (Jedis jedis = pool.getResource()) {
            String mode = jedis.get(KEY_OPENVPN_PORT_FORWARDING_MODE);
            if (mode != null) {
                return PortForwardingMode.valueOf(mode);
            }
            return PortForwardingMode.getDefault();
        }
    }

    void setOpenVpnPortForwardingMode(PortForwardingMode mode) {
        if (mode != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.set(KEY_OPENVPN_PORT_FORWARDING_MODE, mode.toString());
            }
        }
    }

    ExternalAddressType getOpenVpnExternalAddressType() {
        try (Jedis jedis = pool.getResource()) {
            String type = jedis.get(KEY_OPENVPN_EXTERNAL_ADDRESS_TYPE);
            if (type != null) {
                return ExternalAddressType.valueOf(ExternalAddressType.class, jedis.get(KEY_OPENVPN_EXTERNAL_ADDRESS_TYPE));
            }
            return null;

        }
    }

    void setOpenVpnExternalAddressType(ExternalAddressType type) {
        if (type != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.set(KEY_OPENVPN_EXTERNAL_ADDRESS_TYPE, type.toString());
            }
        }
    }

    boolean getOpenVpnServerState() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_OPENVPN_SERVER_ENABLED);

            // default if not set is OFF:
            if (value == null) {
                return false;
            }

            return value.equals(VALUE_TRUE);
        }
    }

    void setOpenVpnServerFirstRun(boolean state) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_OPENVPN_FIRST_RUN, state ? VALUE_TRUE : VALUE_FALSE);
        }
    }

    boolean getOpenVpnServerFirstRun() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_OPENVPN_FIRST_RUN);

            // default if not set is ON:
            if (value == null) {
                return true;
            }

            return value.equals(VALUE_TRUE);
        }
    }

    void saveCurrentTorExitNodes(Set<String> selectedCountries) {
        try (Jedis jedis = pool.getResource()) {
            //Build JSON from countries
            try {
                //create JSON from countries set
                String countriesJSON = objectMapper.writeValueAsString(selectedCountries);
                //save JSON
                jedis.set(KEY_TOR_CURRENT_EXIT_NODES, countriesJSON);
            } catch (JsonProcessingException e) {
                LOG.error("Error while saving current tor exit nodes", e);
            }

        }
    }

    Set<String> getCurrentTorExitNodes() {
        try (Jedis jedis = pool.getResource()) {
            //load JSON
            String countriesJSON = jedis.get(KEY_TOR_CURRENT_EXIT_NODES);
            if (countriesJSON == null)
                return null;
            //create Set<String> from JSON
            try {
                HashSet<String> countries = objectMapper.readValue(countriesJSON, HashSet.class);
                return countries;
            } catch (IOException e) {
                LOG.error("Error'while getting current to exit nodes", e);
            }
        }
        return null;
    }

    String getResolvedDnsGateway() {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(KEY_RESOLVED_DNS_GATEWAY);
        }
    }

    void setResolvedDnsGateway(String gateway) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_RESOLVED_DNS_GATEWAY, gateway);
        }
    }

    Integer getDhcpLeaseTime() {
        try (Jedis jedis = pool.getResource()) {
            String leaseTime = jedis.get(KEY_DHCP_LEASE_TIME);
            if (leaseTime != null) {
                return Integer.valueOf(leaseTime);
            }
            return null;
        }
    }

    void setDhcpLeaseTime(Integer leaseTime) {
        if (leaseTime != null) {
            try (Jedis jedis = pool.getResource()) {
                jedis.set(KEY_DHCP_LEASE_TIME, leaseTime.toString());
            }
        }
    }

    boolean areRouterAdvertisementsEnabled() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_ROUTER_ADVERTISEMENTS_ENABLED);
            if (value == null) {
                return true; // router advertisements are enabled by default
            }
            return Boolean.parseBoolean(value);
        }
    }

    void setRouterAdvertisementsEnabled(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_ROUTER_ADVERTISEMENTS_ENABLED, Boolean.toString(enabled));
        }
    }

    boolean arePrivacyExtensionsEnabled() {
        try (Jedis jedis = pool.getResource()) {
            String value = jedis.get(KEY_PRIVACY_EXTENSIONS_ENABLED);
            if (value == null) {
                return false; // privacy extensions are disabled by default
            }
            return Boolean.parseBoolean(value);
        }
    }

    void setPrivacyExtensionsEnabled(boolean enabled) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(KEY_PRIVACY_EXTENSIONS_ENABLED, Boolean.toString(enabled));
        }
    }
}
