// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';
import { networkConfigurationSchema } from './contracts';

// Editing requires fields that the overview may legitimately report as unavailable.
export const editableSettingsSchema = networkConfigurationSchema.extend({
    expertMode: z.boolean(),
    ipFixedByDefault: z.boolean(),
    dhcpLeaseTime: z.number().int().min(600),
    ipAddress: z.string().nullish(),
    networkMask: z.string().nullish(),
    gateway: z.string().nullish(),
    nameServerPrimary: z.string().nullish(),
    nameServerSecondary: z.string().nullish(),
    dhcpRangeFirst: z.string().nullish(),
    dhcpRangeLast: z.string().nullish(),
});
export const editableNetworkSchema = editableSettingsSchema
    .extend({
        revision: z.string().min(1),
        pendingReboot: z.boolean(),
        pendingConfiguration: editableSettingsSchema.nullish(),
    })
    .refine((value) => !value.pendingReboot || value.pendingConfiguration != null);
export type NetworkSettings = z.infer<typeof editableSettingsSchema>;
export type NetworkConfiguration = z.infer<typeof editableNetworkSchema>;
export const dhcpServersSchema = z.array(z.ipv4());
export const leaseTimes = [600, 1800, 3600, 21600, 86400, 432000, 864000];
export function ipv4(value: string | null | undefined): boolean {
    return z.ipv4().safeParse(value).success;
}
export function ip(value: string | null | undefined): boolean {
    return z.union([z.ipv4(), z.ipv6()]).safeParse(value).success;
}
function number(value: string): number {
    return value.split('.').reduce((result, part) => result * 256 + Number(part), 0);
}
export function networkValidation(
    config: NetworkSettings,
): 'address' | 'mask' | 'subnet' | 'range' | undefined {
    if (config.automatic) return;
    if (
        !ipv4(config.ipAddress) ||
        !ipv4(config.gateway) ||
        [config.nameServerPrimary, config.nameServerSecondary].some(
            (value) => value != null && value !== '' && !ip(value),
        )
    )
        return 'address';
    if (!ipv4(config.networkMask)) return 'mask';
    const bits = number(config.networkMask!).toString(2).padStart(32, '0');
    if (!/^1*0*$/.test(bits)) return 'mask';
    const mask = number(config.networkMask!);
    const subnet = (value: string) => number(value) & mask;
    if (subnet(config.ipAddress!) !== subnet(config.gateway!)) return 'subnet';
    if (config.dhcp) {
        if (
            !ipv4(config.dhcpRangeFirst) ||
            !ipv4(config.dhcpRangeLast) ||
            number(config.dhcpRangeFirst!) >= number(config.dhcpRangeLast!)
        )
            return 'range';
        if (
            subnet(config.ipAddress!) !== subnet(config.dhcpRangeFirst!) ||
            subnet(config.ipAddress!) !== subnet(config.dhcpRangeLast!)
        )
            return 'subnet';
    }
}
export function networkSettings(config: NetworkSettings) {
    const {
        automatic,
        expertMode,
        dhcp,
        dnsServer,
        ipAddress,
        networkMask,
        gateway,
        nameServerPrimary,
        nameServerSecondary,
        dhcpRangeFirst,
        dhcpRangeLast,
        ipFixedByDefault,
        dhcpLeaseTime,
    } = config;
    return {
        automatic,
        expertMode,
        dhcp,
        dnsServer,
        ipAddress: ipAddress ?? null,
        networkMask: networkMask ?? null,
        gateway: gateway ?? null,
        nameServerPrimary: nameServerPrimary ?? null,
        nameServerSecondary: nameServerSecondary ?? null,
        dhcpRangeFirst: dhcpRangeFirst ?? null,
        dhcpRangeLast: dhcpRangeLast ?? null,
        ipFixedByDefault,
        dhcpLeaseTime,
    };
}
export function sameNetwork(a: NetworkSettings, b: NetworkSettings): boolean {
    return JSON.stringify(networkSettings(a)) === JSON.stringify(networkSettings(b));
}
