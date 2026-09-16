// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';

// OpenVpnControllerImpl returns OpenVpnProfile, including masked credentials.
// Explicit projection discards credentials/config content before values enter React state.
export const profileSchema = z.object({
    id: z.number().int(),
    name: z.string().nullish(),
    description: z.string().nullish(),
    enabled: z.boolean(),
    nameServersEnabled: z.boolean(),
    temporary: z.boolean(),
    deleted: z.boolean(),
});
export const profilesSchema = z.array(profileSchema);
export type Profile = z.infer<typeof profileSchema>;
export const vpnStatusSchema = z.object({
    profileId: z.number().int(),
    active: z.boolean(),
    up: z.boolean(),
    devices: z.array(z.string()),
    exitStatus: z.number().int().nullish(),
    // Runtime log lines can contain provider details; keep only their count here.
    errors: z
        .array(z.string())
        .nullish()
        .transform((entries) => entries?.length),
});
export const torConfigSchema = z.object({ sessionUseTor: z.boolean() });
export const torCountriesSchema = z.array(z.string());
// VpnServerStatus uses FIELD-visible names, including the literal is-prefix.
export const mobileStatusSchema = z.object({
    isRunning: z.boolean(),
    isFirstStart: z.boolean(),
    host: z.string().nullish(),
    mappedPort: z.number().int().nullish(),
});

// URLs are composed from settings/app/routes/vpn.states.js and devices.states.js.
// AngularJS 1.8's default hash prefix is '!'.
export const legacyVpn = '/settings/#!/anonymization/vpn/';
export const legacyTor = '/settings/#!/anonymization/tor';
export const legacyMobile = '/settings/#!/mobile';
export const legacyProfile = (id: number) => `${legacyVpn}${id}`;
export const legacyDevice = (id: string) => `/settings/#!/devices/list/${encodeURIComponent(id)}`;
