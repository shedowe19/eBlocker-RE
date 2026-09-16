// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';
import { deviceSchema } from '../../api/contracts';

// These schemas mirror UserModuleTransport, UserProfileModule and InternetAccessContingent.
export const weekDays = [
    'MONDAY',
    'TUESDAY',
    'WEDNESDAY',
    'THURSDAY',
    'FRIDAY',
    'SATURDAY',
    'SUNDAY',
] as const;
const optionalText = z.string().nullish();
const id = z.number().int();
const localDate = z.tuple([
    z.number().int(),
    z.number().int().min(1).max(12),
    z.number().int().min(1).max(31),
]);
export const familyUserSchema = z.object({
    id,
    name: optionalText,
    nameKey: optionalText,
    associatedProfileId: id.nullish(),
    birthday: localDate.nullish(),
    userRole: z.enum(['PARENT', 'CHILD', 'OTHER']).nullish(),
    system: z.boolean(),
    containsPin: z.boolean().optional(),
});
export const familyProfileSchema = z.object({
    id,
    name: optionalText,
    nameKey: optionalText,
    description: optionalText,
    builtin: z.boolean(),
    standard: z.boolean(),
    hidden: z.boolean(),
    forSingleUser: z.boolean(),
    controlmodeUrls: z.boolean(),
    controlmodeTime: z.boolean(),
    controlmodeMaxUsage: z.boolean(),
    parentalControlSettingValidated: z.boolean(),
    internetBlocked: z.boolean().nullish(),
    internetAccessRestrictionMode: z.union([z.literal(0), z.literal(1), z.literal(2)]).nullish(),
    accessibleSitesPackages: z.array(id),
    inaccessibleSitesPackages: z.array(id),
    maxUsageTimeByDay: z.partialRecord(z.enum(weekDays), z.number().int().nonnegative()),
    internetAccessContingents: z.array(
        z.object({
            onDay: z.number().int().min(1).max(9),
            fromMinutes: z.number().int().min(0).max(1440),
            tillMinutes: z.number().int().min(0).max(1440),
            totalMinutes: z.number().int().nonnegative().nullish(),
        }),
    ),
    bonusTimeUsage: z
        .object({
            dateTime: z.array(z.number().int()).min(3).max(7),
            bonusMinutes: z.number().int().nonnegative(),
        })
        .nullish(),
});
export const familyDeviceSchema = deviceSchema.extend({
    assignedUser: id.nullish(),
    operatingUser: id.nullish(),
    defaultSystemUser: id,
});
export const familyFilterSchema = z
    .object({
        id,
        name: z.record(z.string(), z.string()).nullish(),
        description: z.record(z.string(), z.string()).nullish(),
        customerCreatedName: optionalText,
        customerCreatedDescription: optionalText,
        filterType: z.string(),
        builtin: z.boolean(),
        disabled: z.boolean(),
    })
    .passthrough();
export const familyUsersSchema = z.array(familyUserSchema);
export const familyProfilesSchema = z.array(familyProfileSchema);
export const familyDevicesSchema = z.array(familyDeviceSchema);
export const familyFiltersSchema = z.array(familyFilterSchema);
export type FamilyUser = z.infer<typeof familyUserSchema>;
export type FamilyProfile = z.infer<typeof familyProfileSchema>;
export type FamilyDevice = z.infer<typeof familyDeviceSchema>;
export type FamilyFilter = z.infer<typeof familyFilterSchema>;
export interface FamilyData {
    users: FamilyUser[];
    profiles: FamilyProfile[];
    devices: FamilyDevice[];
    filters: FamilyFilter[];
}
