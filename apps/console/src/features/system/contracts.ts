// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';

// SystemStatusDetails / SubSystemDetails, returned by SystemStatusController.get.
// Unknown state names remain visible when connecting to a newer appliance.
export const systemStatusSchema = z.object({
    executionState: z.string().min(1),
    projectVersion: z.string().nullish(),
    warnings: z.array(z.string().nullable()).nullish(),
    subSystemDetails: z
        .array(
            z.object({
                name: z.string().min(1),
                status: z.string().min(1),
                order: z.number().int().optional(),
                msgContext: z.record(z.string(), z.unknown()).nullish(),
            }),
        )
        .nullish(),
    updatingStatus: z
        .object({
            listsPacketVersion: z.string().nullish(),
            updatesAvailable: z.boolean().optional(),
            automaticUpdatesActivated: z.boolean().optional(),
            lastUpdateAttemptFailed: z.boolean().optional(),
        })
        .nullish(),
});

// NetworkControllerImpl.getConfiguration returns NetworkConfiguration directly.
export const networkConfigurationSchema = z.object({
    automatic: z.boolean(),
    expertMode: z.boolean().optional(),
    dhcp: z.boolean(),
    dnsServer: z.boolean(),
    globalIp6AddressAvailable: z.boolean().optional(),
    ipAddress: z.string().nullish(),
    vpnIpAddress: z.string().nullish(),
    networkMask: z.string().nullish(),
    gateway: z.string().nullish(),
    nameServerPrimary: z.string().nullish(),
    nameServerSecondary: z.string().nullish(),
    advisedNameServer: z.string().nullish(),
    dhcpRangeFirst: z.string().nullish(),
    dhcpRangeLast: z.string().nullish(),
    dhcpLeaseTime: z.number().int().nonnegative().optional(),
    ipFixedByDefault: z.boolean().optional(),
    rebootNecessary: z.boolean().optional(),
});

// New compare-and-set API reports active addresses separately from durable pending settings.
export const networkSchema = networkConfigurationSchema.extend({
    revision: z.string().min(1).optional(),
    pendingReboot: z.boolean().optional(),
    pendingConfiguration: networkConfigurationSchema.nullish(),
});

// IpAddressModule serializes NetworkIp6Configuration's Ip6Address values as strings.
export const ipv6Schema = z.object({
    routerAdvertisementsEnabled: z.boolean(),
    privacyExtensionsEnabled: z.boolean(),
    localAddresses: z.array(z.string()).nullish(),
    globalAddresses: z.array(z.string()).nullish(),
});

// DnsControllerImpl.getStatus returns a JSON boolean, not { enabled: ... }.
export const dnsStatusSchema = z.boolean();
export const dnsResolversSchema = z.object({
    defaultResolver: z.string().min(1),
    customResolverMode: z.string().nullish(),
    dhcpNameServers: z.array(z.string()).nullish(),
    customNameServers: z.array(z.string()).nullish(),
});
