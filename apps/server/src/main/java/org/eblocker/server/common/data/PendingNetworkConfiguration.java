// SPDX-License-Identifier: EUPL-1.2
package org.eblocker.server.common.data;

/** Durable desired state; an application restart is not an operating-system reboot. */
public record PendingNetworkConfiguration(String bootId, NetworkConfiguration configuration) {
    public PendingNetworkConfiguration {
        if (configuration == null) throw new IllegalArgumentException("Missing pending network configuration");
        configuration = new NetworkConfiguration(configuration);
    }
    @Override public NetworkConfiguration configuration() { return new NetworkConfiguration(configuration); }
}
