// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';
import type { FamilyDevice, FamilyFilter, FamilyProfile, FamilyUser } from './contracts';
import { familyMessages } from './messages';
export const standardUserKey = 'SHARED.USER.NAME.STANDARD_USER';
export const usersLink = '/settings/#!/parentalcontrol/users/';
export const userLink = (id: number) => `${usersLink}${id}`;
export function visibleUsers(users: FamilyUser[]) {
    return users.filter((user) => !user.system || user.nameKey === standardUserKey);
}
export function userName(user: FamilyUser, locale: Locale) {
    return user.nameKey === standardUserKey
        ? familyMessages[locale].standardUser
        : user.name?.trim() || familyMessages[locale].unnamedUser;
}
export function profileName(profile: FamilyProfile, locale: Locale) {
    const t = familyMessages[locale];
    const names: Record<string, string> = {
        PARENTAL_CONTROL_DEFAULT_PROFILE_NAME: t.standardProfile,
        PARENTAL_CONTROL_FRAG_FINN_PROFILE_NAME: t.fragFinn,
        PARENTAL_CONTROL_FULL_2_PROFILE_NAME: t.childrenTime,
        PARENTAL_CONTROL_FULL_PROFILE_NAME: t.children,
        PARENTAL_CONTROL_MED_2_PROFILE_NAME: t.teensTime,
        PARENTAL_CONTROL_MED_PROFILE_NAME: t.teens,
        PARENTAL_CONTROL_USERPROFILE_LIMBO_NAME: t.noInternet,
    };
    return (profile.nameKey && names[profile.nameKey]) || profile.name?.trim() || t.unnamedProfile;
}
export function filterName(filter: FamilyFilter, locale: Locale) {
    return (
        (!filter.builtin && filter.customerCreatedName?.trim()) ||
        filter.name?.[locale] ||
        filter.name?.en ||
        filter.name?.de ||
        filter.customerCreatedName ||
        familyMessages[locale].filterFallback(filter.id)
    );
}
export function filterLink(filter: FamilyFilter): string | undefined {
    return filter.filterType === 'blacklist' || filter.filterType === 'whitelist'
        ? `/settings/#!/parentalcontrol/${filter.filterType}s/${filter.id}`
        : undefined;
}
export function assignedUserId(device: FamilyDevice) {
    return device.assignedUser ?? device.defaultSystemUser;
}
export function assignedDevices(user: FamilyUser, devices: FamilyDevice[]) {
    return devices.filter(
        (device) =>
            !device.isEblocker &&
            !device.isGateway &&
            (user.nameKey === standardUserKey
                ? assignedUserId(device) === device.defaultSystemUser
                : assignedUserId(device) === user.id),
    );
}
export function assignmentName(
    device: FamilyDevice,
    users: FamilyUser[],
    locale: Locale,
    operating = false,
) {
    const assignedId = operating
        ? (device.operatingUser ?? device.defaultSystemUser)
        : assignedUserId(device);
    if (assignedId === device.defaultSystemUser) return familyMessages[locale].standardUser;
    const user = users.find((entry) => entry.id === assignedId);
    return user ? userName(user, locale) : familyMessages[locale].userFallback(assignedId);
}
export function clockMinutes(minutes: number) {
    return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
}
export function calendarDate(parts: number[]) {
    return `${String(parts[0]).padStart(4, '0')}-${String(parts[1]).padStart(2, '0')}-${String(parts[2]).padStart(2, '0')}`;
}
