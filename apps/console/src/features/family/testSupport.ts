// SPDX-License-Identifier: EUPL-1.2
import { vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { device, json, token } from '../../test/fixtures';
import type { FamilyProfile } from './contracts';

export const childProfile: FamilyProfile = {
    id: 10,
    name: 'Child rules',
    builtin: false,
    standard: false,
    hidden: false,
    forSingleUser: true,
    controlmodeUrls: true,
    controlmodeTime: true,
    controlmodeMaxUsage: true,
    parentalControlSettingValidated: true,
    internetBlocked: false,
    internetAccessRestrictionMode: 1,
    accessibleSitesPackages: [200],
    inaccessibleSitesPackages: [100],
    maxUsageTimeByDay: { MONDAY: 60, FRIDAY: 120 },
    internetAccessContingents: [
        { onDay: 8, fromMinutes: 480, tillMinutes: 1440, totalMinutes: 90 },
    ],
    bonusTimeUsage: { dateTime: [2026, 9, 16, 12, 30], bonusMinutes: 15 },
};
export function fixture() {
    return {
        '/users': [
            {
                id: 1,
                name: 'Mika',
                system: false,
                userRole: 'CHILD',
                associatedProfileId: 10,
                birthday: [2015, 6, 21],
            },
            { id: 2, name: 'Alex', system: false, userRole: 'PARENT', associatedProfileId: 10 },
            {
                id: 0,
                nameKey: 'SHARED.USER.NAME.STANDARD_USER',
                system: true,
                userRole: 'OTHER',
                associatedProfileId: 10,
            },
            {
                id: 99,
                name: 'internal device user',
                system: true,
                userRole: 'OTHER',
                associatedProfileId: 10,
            },
        ],
        '/userprofiles': [structuredClone(childProfile)],
        '/devices': [
            {
                ...device,
                name: 'Child tablet',
                assignedUser: 1,
                operatingUser: 2,
                defaultSystemUser: 99,
            },
            {
                ...device,
                id: 'device:extra',
                name: 'Shared tablet',
                assignedUser: null,
                operatingUser: null,
                defaultSystemUser: 99,
            },
            {
                ...device,
                id: 'device:router',
                name: 'Router',
                isGateway: true,
                assignedUser: 1,
                operatingUser: 1,
                defaultSystemUser: 100,
            },
        ],
        '/filterlists': [
            {
                id: 100,
                name: { de: 'Spieleseiten', en: 'Games' },
                description: { de: 'Spielangebote', en: 'Gaming sites' },
                filterType: 'blacklist',
                builtin: true,
                disabled: false,
            },
            {
                id: 200,
                customerCreatedName: 'School',
                customerCreatedDescription: 'Homework sites',
                filterType: 'whitelist',
                builtin: false,
                disabled: false,
            },
        ],
    };
}
export async function readyClient(
    values: Record<string, unknown> = fixture(),
    custom?: (path: string, options?: RequestInit) => Response | Promise<Response> | undefined,
) {
    const transport = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
        const path = String(input).replace('/api/adminconsole', '');
        if (path.includes('/authentication/')) return json(token());
        const result = custom?.(path, options);
        return result ?? json(values[path]);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
