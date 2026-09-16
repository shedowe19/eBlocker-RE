// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';

// DnsResolvers, LocalDnsRecord and ResolverStats from DnsControllerImpl.
export const resolversSchema = z.object({
    defaultResolver: z.enum(['dhcp', 'custom', 'tor']),
    customResolverMode: z.enum(['default', 'random', 'round_robin']).nullish(),
    dhcpNameServers: z.array(z.string()),
    customNameServers: z.array(z.string()),
});
export type Resolvers = z.infer<typeof resolversSchema>;
export const recordSchema = z.object({
    name: z.string().min(1),
    builtin: z.boolean(),
    hidden: z.boolean(),
    ipAddress: z.string().nullish(),
    ip6Address: z.string().nullish(),
    vpnIpAddress: z.string().nullish(),
    vpnIp6Address: z.string().nullish(),
});
export const recordsSchema = z.array(recordSchema);
export type DnsRecord = z.infer<typeof recordSchema>;
export const statsSchema = z.object({
    nameServerStats: z.array(
        z.object({
            nameServer: z.string(),
            valid: z.number().int().nonnegative(),
            invalid: z.number().int().nonnegative(),
            error: z.number().int().nonnegative(),
            timeout: z.number().int().nonnegative(),
            responseTimeAverage: z.number(),
            responseTimeMedian: z.number(),
            responseTimeMin: z.number(),
            responseTimeMax: z.number(),
            rating: z.enum(['GOOD', 'MEDIUM', 'BAD']),
            reliabilityRating: z.enum(['LOW', 'MEDIUM', 'HIGH', 'UNAVAILABLE']),
            responseTimeRating: z.enum(['FAST', 'MEDIUM', 'SLOW', 'UNAVAILABLE']),
        }),
    ),
});

export function resolverSettings(value: Resolvers) {
    return {
        defaultResolver: value.defaultResolver,
        customResolverMode: value.customResolverMode,
        customNameServers: value.customNameServers,
    };
}
export function sameResolvers(a: Resolvers, b: Resolvers) {
    return JSON.stringify(resolverSettings(a)) === JSON.stringify(resolverSettings(b));
}
// NameServer.parse supports bare IPs or tcp/udp:IPv4:port and tcp/udp:[IPv6]:port.
export function validNameServer(value: string): boolean {
    if (z.union([z.ipv4(), z.ipv6()]).safeParse(value).success) return true;
    const match = /^(tcp|udp):(?:(\d+\.\d+\.\d+\.\d+)|\[([a-f0-9:]+)\]):(\d+)$/.exec(value);
    return (
        !!match &&
        z.union([z.ipv4(), z.ipv6()]).safeParse(match[2] ?? match[3]).success &&
        Number(match[4]) >= 1 &&
        Number(match[4]) <= 65535
    );
}
export function parseServers(value: string): string[] {
    return value
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean);
}
export function validServers(value: Resolvers): boolean {
    return (
        value.customNameServers.every(validNameServer) &&
        new Set(value.customNameServers).size === value.customNameServers.length &&
        value.customNameServers.length <= 256 &&
        (value.defaultResolver !== 'custom' || value.customNameServers.length > 0)
    );
}
function canonicalRecords(records: DnsRecord[]) {
    return records
        .map((record) => ({
            ...record,
            ipAddress: record.ipAddress ?? null,
            ip6Address: record.ip6Address ?? null,
            vpnIpAddress: record.vpnIpAddress ?? null,
            vpnIp6Address: record.vpnIp6Address ?? null,
        }))
        .sort((a, b) => a.name.localeCompare(b.name));
}
export function sameRecords(a: DnsRecord[], b: DnsRecord[]): boolean {
    return JSON.stringify(canonicalRecords(a)) === JSON.stringify(canonicalRecords(b));
}
export function validRecord(value: DnsRecord, records: DnsRecord[], original?: DnsRecord): boolean {
    return (
        value.name.trim().length > 0 &&
        value.name.length <= 50 &&
        !records.some(
            (item) =>
                item.name !== original?.name &&
                item.name.toLowerCase() === value.name.toLowerCase(),
        ) &&
        !!(value.ipAddress || value.ip6Address) &&
        (!value.ipAddress || z.ipv4().safeParse(value.ipAddress).success) &&
        (!value.ip6Address || z.ipv6().safeParse(value.ip6Address).success)
    );
}
