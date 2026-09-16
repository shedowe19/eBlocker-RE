// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import {
    familyUsersSchema,
    familyUserSchema,
    familyProfilesSchema,
    familyProfileSchema,
    familyDevicesSchema,
    familyDeviceSchema,
    familyFiltersSchema,
    familyFilterSchema,
} from './contracts';
import type { FamilyUser, FamilyProfile, FamilyDevice, FamilyFilter } from './contracts';

export type UserSettings = Pick<
    FamilyUser,
    'name' | 'birthday' | 'userRole' | 'associatedProfileId'
>;
export type ProfileSettings = Pick<
    FamilyProfile,
    | 'name'
    | 'description'
    | 'forSingleUser'
    | 'controlmodeUrls'
    | 'controlmodeTime'
    | 'controlmodeMaxUsage'
    | 'parentalControlSettingValidated'
    | 'internetAccessRestrictionMode'
    | 'accessibleSitesPackages'
    | 'inaccessibleSitesPackages'
    | 'maxUsageTimeByDay'
    | 'internetAccessContingents'
>;
function canonical(value: unknown): string {
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
    if (value && typeof value === 'object')
        return JSON.stringify(
            Object.keys(value)
                .sort()
                .map((key) => [key, canonical((value as Record<string, unknown>)[key])]),
        );
    return JSON.stringify(value) ?? 'undefined';
}
export function changes<T extends object>(before: T, after: T): Partial<T> {
    return Object.fromEntries(
        Object.entries(after).filter(
            ([key, value]) => canonical(value) !== canonical(before[key as keyof T]),
        ),
    ) as Partial<T>;
}
export function checkUnchanged<T extends object>(
    before: T,
    current: T | undefined,
    keys: (keyof T)[],
) {
    if (!current || keys.some((key) => canonical(before[key]) !== canonical(current[key])))
        throw new ApiError('conflict');
}
export async function saveUser(
    client: ConsoleClient,
    original: FamilyUser | undefined,
    value: UserSettings,
) {
    if (!original) return client.post('/users', { ...value, containsPin: false }, familyUserSchema);
    const patch = changes<UserSettings>(
        { ...original, birthday: original.birthday ?? null },
        value,
    );
    if (!Object.keys(patch).length) return original;
    const current = (await client.get('/users', familyUsersSchema)).find(
        (user) => user.id === original.id,
    );
    checkUnchanged(original, current, Object.keys(patch) as (keyof FamilyUser)[]);
    return client.patch(`/users/${original.id}/settings`, patch, familyUserSchema);
}
export async function saveProfile(
    client: ConsoleClient,
    original: FamilyProfile | undefined,
    value: ProfileSettings,
) {
    const { forSingleUser: _ownership, ...settings } = value;
    if (!original) {
        const { maxUsageTimeByDay, ...rest } = settings;
        return client.post(
            '/userprofiles/managed',
            Object.keys(maxUsageTimeByDay).length ? settings : rest,
            familyProfileSchema,
        );
    }
    const patch = changes<Omit<ProfileSettings, 'forSingleUser'>>(
        { ...original, description: original.description ?? '' },
        settings,
    );
    if (original.standard) {
        delete patch.name;
        delete patch.description;
    }
    if (patch.maxUsageTimeByDay)
        patch.maxUsageTimeByDay = changes(original.maxUsageTimeByDay, patch.maxUsageTimeByDay);
    if (!Object.keys(patch).length) return original;
    const current = (await client.get('/userprofiles', familyProfilesSchema)).find(
        (profile) => profile.id === original.id,
    );
    checkUnchanged(
        original,
        current,
        Object.keys(patch).filter((key) => key !== 'maxUsageTimeByDay') as (keyof FamilyProfile)[],
    );
    if (patch.maxUsageTimeByDay)
        checkUnchanged(
            original.maxUsageTimeByDay,
            current?.maxUsageTimeByDay,
            Object.keys(patch.maxUsageTimeByDay) as (keyof FamilyProfile['maxUsageTimeByDay'])[],
        );
    return client.patch(`/userprofiles/${original.id}/settings`, patch, familyProfileSchema);
}
export async function assignDevice(
    client: ConsoleClient,
    original: FamilyDevice,
    userId: number | null,
) {
    const current = (await client.get('/devices', familyDevicesSchema)).find(
        (device) => device.id === original.id,
    );
    checkUnchanged(original, current, ['assignedUser', 'operatingUser', 'defaultSystemUser']);
    return client.patch(
        `/devices/${encodeURIComponent(original.id)}/assignment`,
        { userId },
        familyDeviceSchema,
    );
}
export const domainsSchema = z.array(z.string());
export type FilterSettings = {
    customerCreatedName: string;
    customerCreatedDescription: string;
    domains: string[];
    disabled: boolean;
    filterType: 'blacklist' | 'whitelist';
};
export async function saveFilter(
    client: ConsoleClient,
    original: FamilyFilter | undefined,
    originalDomains: string[],
    value: FilterSettings,
) {
    if (!original)
        return client.post(
            `/filterlists?filterType=${value.filterType}`,
            { ...value, builtin: false },
            familyFilterSchema,
        );
    const current = (await client.get('/filterlists', familyFiltersSchema)).find(
        (filter) => filter.id === original.id,
    );
    const keys = original.builtin
        ? ['disabled']
        : ['customerCreatedName', 'customerCreatedDescription', 'disabled', 'filterType'];
    checkUnchanged(original, current, keys);
    if (!current) throw new ApiError('conflict');
    if (!original.builtin) {
        const domains = await client.get(`/filterlists/${original.id}/domains`, domainsSchema);
        if (canonical(domains) !== canonical(originalDomains)) throw new ApiError('conflict');
    }
    return client.put(
        `/filterlists/${original.id}/update?filterType=${original.filterType}`,
        original.builtin ? { ...current, disabled: value.disabled } : { ...current, ...value },
        familyFilterSchema,
    );
}
