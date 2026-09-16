// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';
const strings = z
    .array(z.string())
    .nullable()
    .transform((value) => value ?? []);
export const agentStatusSchema = z.object({
    schemaVersion: z.literal(1),
    data: z.object({
        readOnly: z.literal(true),
        capabilities: z.object({
            readOnly: z.literal(true),
            wireguard: z.object({
                kernelFamilyRegistered: z.boolean(),
                state: z.string(),
                management: z.literal(false),
            }),
        }),
        interfaces: z
            .array(
                z.object({
                    index: z.number().int(),
                    name: z.string(),
                    mtu: z.number().int(),
                    up: z.boolean(),
                    running: z.boolean(),
                    loopback: z.boolean(),
                    addresses: z.array(
                        z.object({ prefix: z.string(), family: z.string(), scope: z.string() }),
                    ),
                }),
            )
            .nullable()
            .transform((value) => value ?? []),
        routes: z
            .array(
                z.object({
                    family: z.string(),
                    destination: z.string(),
                    gateway: z.string().optional(),
                    table: z.number(),
                }),
            )
            .nullable()
            .transform((value) => value ?? []),
    }),
});
export const wireGuardPlanSchema = z.object({
    schemaVersion: z.literal(1),
    data: z.object({
        applied: z.literal(false),
        killSwitchActive: z.literal(false),
        interfaceAddresses: strings,
        dns: strings,
        defaultRouteIPv4: z.boolean(),
        defaultRouteIPv6: z.boolean(),
        endpointExclusions: strings,
        leakRisks: strings,
        warnings: strings,
        peers: z.array(
            z.object({
                publicKey: z.string(),
                allowedIPs: strings,
                endpoint: z.string().optional(),
                hasPresharedKey: z.boolean(),
            }),
        ),
    }),
});
export type AgentStatus = z.infer<typeof agentStatusSchema>['data'];
export type WireGuardPlan = z.infer<typeof wireGuardPlanSchema>['data'];
