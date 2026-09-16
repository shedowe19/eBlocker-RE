// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';

// See docs/development/console-migration.md. These names match Jackson's field visibility.
export const tokenSchema = z.object({
    token: z.string().min(1),
    appContext: z.literal('ADMINCONSOLE'),
    expiresOn: z.number().int().positive(),
    passwordRequired: z.boolean(),
});
export type Token = z.infer<typeof tokenSchema>;
export const cookieSessionSchema = z.object({
    appContext: z.literal('ADMINCONSOLE'),
    authenticated: z.boolean(),
    passwordRequired: z.boolean(),
    expiresOn: z.number().int().positive(),
    // ConsoleSessionService.secret(): 32 random bytes, unpadded Base64 URL encoding.
    csrfToken: z.string().regex(/^[A-Za-z0-9_-]{43}$/),
});
export type CookieSession = z.infer<typeof cookieSessionSchema>;
export const deviceSchema = z.object({
    id: z.string().min(1),
    name: z.string().optional(),
    vendor: z.string().optional(),
    hardwareAddress: z.string().optional(),
    ipAddresses: z.array(z.string()),
    lastSeen: z.number().finite().optional(),
    isOnline: z.boolean(),
    isCurrentDevice: z.boolean(),
    isGateway: z.boolean(),
    isEblocker: z.boolean(),
    isVpnClient: z.boolean(),
    enabled: z.boolean(),
    paused: z.boolean(),
    filterAdsEnabled: z.boolean(),
    filterTrackersEnabled: z.boolean(),
    malwareFilterEnabled: z.boolean(),
    sslEnabled: z.boolean(),
    filterMode: z.string().optional(),
});
export const devicesSchema = z.array(deviceSchema);
export type Device = z.infer<typeof deviceSchema>;

export const deviceSettingsPatchSchema = z
    .object({
        name: z.string().trim().min(1).max(50).optional(),
        enabled: z.boolean().optional(),
        filterAdsEnabled: z.boolean().optional(),
        filterTrackersEnabled: z.boolean().optional(),
        malwareFilterEnabled: z.boolean().optional(),
        sslEnabled: z.boolean().optional(),
    })
    .strict()
    .refine((value) => Object.values(value).some((item) => item !== undefined));
export type DeviceSettingsPatch = z.infer<typeof deviceSettingsPatchSchema>;
