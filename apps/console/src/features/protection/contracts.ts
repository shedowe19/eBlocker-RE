// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';

// BlockerControllerImpl.getBlockers -> List<Blocker>. Text fields are locale maps.
const localizedText = z.record(z.string(), z.string()).nullish();
export const blockersSchema = z.array(
    z.object({
        id: z.number().int(),
        name: localizedText,
        description: localizedText,
        enabled: z.boolean(),
        type: z.string(),
        category: z.string(),
        providedByEblocker: z.boolean(),
        updateStatus: z.string().nullish(),
        error: z.string().nullish(),
        filterType: z.string().nullish(),
    }),
);
export type Blocker = z.infer<typeof blockersSchema>[number];

// AppWhitelistModuleDisplay includes inherited FIELD-visible booleans (no is-prefix).
export const trustedAppSchema = z.object({
    id: z.number().int(),
    name: z.string(),
    description: localizedText,
    enabled: z.boolean(),
    builtin: z.boolean(),
    hidden: z.boolean(),
    whitelistedDomainsIps: z.array(z.string()),
});
export const trustedAppsSchema = z.array(trustedAppSchema);
export type TrustedApp = z.infer<typeof trustedAppSchema>;

// Certificate uses java.util.Date; EblockerHttpsServer enables timestamps (milliseconds).
// BigInteger serialNumber is intentionally not displayed via lossy JavaScript number parsing.
const timestamp = z
    .number()
    .int()
    .refine((value) => Number.isFinite(new Date(value).getTime()));
export const certificateSchema = z
    .object({
        distinguishedName: z
            .object({ commonName: z.string().nullish(), organization: z.string().nullish() })
            .nullish(),
        fingerprintSha256: z.string().nullish(),
        notBefore: timestamp,
        notAfter: timestamp,
    })
    .nullable();
export const sslStatusSchema = z.boolean();
