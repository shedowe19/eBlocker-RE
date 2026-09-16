// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import type { Locale } from '../../i18n';
import { familyUsersSchema, familyProfilesSchema, familyFiltersSchema } from './contracts';
import type {
    FamilyData,
    FamilyUser,
    FamilyProfile,
    FamilyDevice,
    FamilyFilter,
} from './contracts';
import { checkUnchanged } from './api';
import { EditorFrame, FormValidation } from './EditorFrame';
import type { Mutate } from './EditorFrame';
import { UserEditor, PinEditor, AssignmentEditor } from './UserEditor';
import { ProfileEditor } from './ProfileEditor';
import { FilterEditor } from './FilterEditor';
import { editMessages } from './editMessages';
import { userName, profileName, filterName } from './presentation';

export type FamilyAction =
    | { kind: 'user'; user?: FamilyUser }
    | { kind: 'profile'; profile?: FamilyProfile }
    | { kind: 'filter'; filter?: FamilyFilter }
    | { kind: 'pin'; user: FamilyUser }
    | { kind: 'assign'; device: FamilyDevice }
    | { kind: 'deleteUser'; user: FamilyUser }
    | { kind: 'deleteProfile'; profile: FamilyProfile }
    | { kind: 'deleteFilter'; filter: FamilyFilter };
export function FamilyEditor({
    action,
    ...props
}: {
    action: FamilyAction;
    client: ConsoleClient;
    data: FamilyData;
    locale: Locale;
    mutate: Mutate;
    onClose: () => void;
}) {
    switch (action.kind) {
        case 'user':
            return <UserEditor {...props} user={action.user} />;
        case 'profile':
            return <ProfileEditor {...props} profile={action.profile} />;
        case 'filter':
            return <FilterEditor {...props} filter={action.filter} />;
        case 'pin':
            return <PinEditor {...props} user={action.user} />;
        case 'assign':
            return <AssignmentEditor {...props} device={action.device} />;
        default:
            return <DeleteEditor {...props} action={action} />;
    }
}
function DeleteEditor({
    action,
    client,
    data,
    locale,
    mutate,
    onClose,
}: {
    action: Extract<FamilyAction, { kind: 'deleteUser' | 'deleteProfile' | 'deleteFilter' }>;
    client: ConsoleClient;
    data: FamilyData;
    locale: Locale;
    mutate: Mutate;
    onClose: () => void;
}) {
    const t = editMessages[locale];
    const [confirmed, setConfirmed] = useState(false);
    const name =
        action.kind === 'deleteUser'
            ? userName(action.user, locale)
            : action.kind === 'deleteProfile'
              ? profileName(action.profile, locale)
              : filterName(action.filter, locale);
    const inUse =
        action.kind === 'deleteProfile'
            ? data.users.some((user) => user.associatedProfileId === action.profile.id)
            : action.kind === 'deleteFilter' &&
              data.profiles.some(
                  (profile) =>
                      profile.controlmodeUrls &&
                      [
                          ...profile.accessibleSitesPackages,
                          ...profile.inaccessibleSitesPackages,
                      ].includes(action.filter.id),
              );
    return (
        <EditorFrame
            title={`${t[action.kind]}: ${name}`}
            locale={locale}
            onClose={onClose}
            submitLabel={t[action.kind]}
            onSave={async () => {
                if (!confirmed || inUse)
                    throw new FormValidation(inUse ? t.inUse : t.removeConfirm);
                return mutate(async () => {
                    if (action.kind === 'deleteUser') {
                        const current = (await client.get('/users', familyUsersSchema)).find(
                            (user) => user.id === action.user.id,
                        );
                        checkUnchanged(action.user, current, [
                            'name',
                            'associatedProfileId',
                            'userRole',
                            'birthday',
                            'system',
                        ]);
                        if (current?.system) throw new ApiError('conflict');
                        await client.deleteVoid(`/users/${action.user.id}`);
                        if (
                            (await client.get('/users', familyUsersSchema)).some(
                                (user) => user.id === action.user.id,
                            )
                        )
                            throw new ApiError('conflict');
                    } else if (action.kind === 'deleteProfile') {
                        const profiles = await client.get('/userprofiles', familyProfilesSchema);
                        checkUnchanged(
                            action.profile,
                            profiles.find((profile) => profile.id === action.profile.id),
                            Object.keys(action.profile) as (keyof FamilyProfile)[],
                        );
                        const users = await client.get('/users', familyUsersSchema);
                        if (users.some((user) => user.associatedProfileId === action.profile.id))
                            throw new ApiError('conflict');
                        await client.deleteVoid(`/userprofiles/${action.profile.id}`);
                        if (
                            (await client.get('/userprofiles', familyProfilesSchema)).some(
                                (profile) => profile.id === action.profile.id,
                            )
                        )
                            throw new ApiError('conflict');
                    } else {
                        const current = (
                            await client.get('/filterlists', familyFiltersSchema)
                        ).find((filter) => filter.id === action.filter.id);
                        checkUnchanged(action.filter, current, [
                            'customerCreatedName',
                            'disabled',
                            'builtin',
                            'filterType',
                        ]);
                        await client.deleteVoid(`/filterlists/${action.filter.id}`);
                    }
                });
            }}
        >
            <p>{t[`${action.kind}Help`]}</p>
            {inUse && <p className="family-warning">{t.inUse}</p>}
            <label className="family-check">
                <input
                    type="checkbox"
                    checked={confirmed}
                    disabled={Boolean(inUse)}
                    onChange={(event) => setConfirmed(event.target.checked)}
                />
                {t.removeConfirm}
            </label>
        </EditorFrame>
    );
}
