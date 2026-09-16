// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';
import { wireGuardPlanSchema } from './contracts';

export const profileIdSchema = z.string().regex(/^[a-z][a-z0-9-]{0,31}$/);
const plan = wireGuardPlanSchema.shape.data;
const integer = z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER);
// Preserve runtime availability when JSON uint64 counters exceed JavaScript's exact range.
const byteCounter = z
    .number()
    .nonnegative()
    .max(2 ** 64)
    .refine(Number.isInteger);
const owner = z.object({ interfaceName: z.string(), ownershipId: z.string() });
const attestation = z.object({
    owner,
    digest: z.string(),
    routingVerified: z.boolean(),
    firewallVerified: z.boolean(),
    markVerified: z.boolean(),
    endpointsVerified: z.boolean(),
    ipv4PoliciesVerified: z.boolean(),
    ipv6PoliciesVerified: z.boolean(),
    killSwitchActive: z.boolean(),
});
const policy = z.object({
    version: z.literal(1),
    owner,
    digest: z.string(),
    routeTable: integer,
    allowedIPs: z
        .array(z.string())
        .nullable()
        .transform((value) => value ?? []),
    ipv4Default: z.boolean(),
    ipv6Default: z.boolean(),
});
export const runtimeSchema = z.object({
    schemaVersion: z.literal(1),
    target: owner.extend({ profileId: profileIdSchema }),
    phase: z.string(),
    plan,
    observation: z.object({
        exists: z.boolean(),
        owned: z.boolean(),
        up: z.boolean(),
        peers: z
            .array(
                z.object({
                    publicKey: z.string(),
                    lastHandshakeUnix: integer.max(8_640_000_000_000),
                    receiveBytes: byteCounter,
                    transmitBytes: byteCounter,
                }),
            )
            .nullable()
            .transform((value) => value ?? []),
        policy: attestation.optional(),
    }),
    errorCode: z.string().optional(),
    policy: policy.optional(),
    killSwitchActive: z.boolean(),
});
export const profileSummarySchema = z.object({
    profileId: profileIdSchema,
    phase: z.string(),
    plan,
});
export const profileStatusSchema = profileSummarySchema
    .extend({ runtime: runtimeSchema.nullable() })
    .refine(
        (value) => value.runtime === null || value.runtime.target.profileId === value.profileId,
    );
export const profilesResponseSchema = z.object({
    schemaVersion: z.literal(1),
    data: z.object({ profiles: z.array(profileSummarySchema) }),
});
export const profileResponseSchema = z.object({
    schemaVersion: z.literal(1),
    data: profileSummarySchema,
});
export const profileStatusResponseSchema = z.object({
    schemaVersion: z.literal(1),
    data: profileStatusSchema,
});
export const profileDeletedSchema = z.object({
    schemaVersion: z.literal(1),
    data: z.object({ profileId: profileIdSchema, deleted: z.literal(true) }),
});
export const cancellationSchema = z.object({
    schemaVersion: z.literal(1),
    data: z.object({ profileId: profileIdSchema, cancellationRequested: z.boolean() }),
});
export type ProfileSummary = z.infer<typeof profileSummarySchema>;
export type ProfileStatus = z.infer<typeof profileStatusSchema>;
export type WireGuardRuntime = z.infer<typeof runtimeSchema>;

export function tunnelObserved(runtime: WireGuardRuntime | null): boolean {
    if (
        !runtime ||
        runtime.phase !== 'active' ||
        !runtime.observation.exists ||
        !runtime.observation.owned ||
        !runtime.observation.up
    )
        return false;
    const peers = runtime.observation.peers.map((peer) => peer.publicKey);
    return (
        runtime.plan.peers.length > 0 &&
        runtime.plan.peers.every((peer) => peers.includes(peer.publicKey))
    );
}
export function protectionObserved(runtime: WireGuardRuntime | null): boolean {
    if (
        !tunnelObserved(runtime) ||
        !runtime?.killSwitchActive ||
        !runtime.policy ||
        !runtime.observation.policy
    )
        return false;
    const observed = runtime.observation.policy;
    return (
        observed.killSwitchActive &&
        observed.routingVerified &&
        observed.firewallVerified &&
        observed.markVerified &&
        observed.endpointsVerified &&
        observed.ipv4PoliciesVerified &&
        observed.ipv6PoliciesVerified &&
        observed.digest === runtime.policy.digest &&
        observed.owner.ownershipId === runtime.policy.owner.ownershipId &&
        observed.owner.interfaceName === runtime.policy.owner.interfaceName &&
        observed.owner.ownershipId === runtime.target.ownershipId &&
        observed.owner.interfaceName === runtime.target.interfaceName
    );
}
