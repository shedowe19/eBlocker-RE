// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import {
    familyUsersSchema,
    familyProfilesSchema,
    familyDevicesSchema,
    familyFiltersSchema,
} from './contracts';
import type { FamilyData } from './contracts';
import { familyMessages } from './messages';
import {
    assignedDevices,
    assignmentName,
    calendarDate,
    filterLink,
    filterName,
    profileName,
    userLink,
    userName,
    usersLink,
    visibleUsers,
} from './presentation';
import { ProfileCard } from './ProfileCard';
import type { BonusResult } from './ProfileCard';
import { FamilyEditor } from './FamilyEditor';
import type { FamilyAction } from './FamilyEditor';
import type { MutationResult } from './EditorFrame';
import { editMessages } from './editMessages';

export function FamilyPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = familyMessages[locale];
    const edit = editMessages[locale];
    const [action, setAction] = useState<FamilyAction>();
    const [data, setData] = useState<FamilyData>();
    const opener = useRef<HTMLElement | undefined>(undefined);
    const pageHeading = useRef<HTMLHeadingElement>(null);
    function openEditor(next: FamilyAction) {
        opener.current =
            document.activeElement instanceof HTMLElement ? document.activeElement : undefined;
        setAction(next);
    }
    useEffect(() => {
        if (!action && opener.current) {
            const previous = opener.current;
            opener.current = undefined;
            if (previous.isConnected) previous.focus();
            else pageHeading.current?.focus();
        }
    }, [action]);
    const [loading, setLoading] = useState(true);
    const [saving, setSaving] = useState(false);
    const [failure, setFailure] = useState<ErrorCode>();
    const controller = useRef<AbortController | undefined>(undefined);
    const mounted = useRef(true);
    const writing = useRef(false);
    const load = useCallback(async (): Promise<boolean> => {
        controller.current?.abort();
        const request = new AbortController();
        controller.current = request;
        setLoading(true);
        try {
            const [users, profiles, devices, filters] = await Promise.all([
                client.get('/users', familyUsersSchema, request.signal),
                client.get('/userprofiles', familyProfilesSchema, request.signal),
                client.get('/devices', familyDevicesSchema, request.signal),
                client.get('/filterlists', familyFiltersSchema, request.signal),
            ]);
            if (request.signal.aborted || !mounted.current) return false;
            setData({ users, profiles, devices, filters });
            setFailure(undefined);
            return true;
        } catch (error) {
            if (!request.signal.aborted && mounted.current) setFailure(errorCode(error));
            return false;
        } finally {
            if (!request.signal.aborted && mounted.current) setLoading(false);
        }
    }, [client]);
    useEffect(() => {
        mounted.current = true;
        void load();
        return () => {
            mounted.current = false;
            controller.current?.abort();
        };
    }, [load]);

    async function addBonus(profileId: number, minutes: number): Promise<BonusResult> {
        if (writing.current) return 'bonusUncertain';
        writing.current = true;
        setSaving(true);
        controller.current?.abort();
        let written = false;
        try {
            await client.postVoid(`/userprofile/bonustime/${profileId}`, minutes);
            written = true;
        } catch {
            /* A failed response can follow a successful write; always read the actual state. */
        }
        const refreshed = mounted.current && (await load());
        writing.current = false;
        if (mounted.current) setSaving(false);
        return written ? (refreshed ? 'bonusSaved' : 'bonusReloadFailed') : 'bonusUncertain';
    }

    async function mutate(operation: () => Promise<unknown>): Promise<MutationResult> {
        if (writing.current) return 'uncertain';
        writing.current = true;
        setSaving(true);
        controller.current?.abort();
        let result: MutationResult = 'saved';
        try {
            await operation();
        } catch (error) {
            const code = errorCode(error);
            result =
                code === 'conflict' ? 'conflict' : code === 'validation' ? 'invalid' : 'uncertain';
        }
        const refreshed = mounted.current && (await load());
        writing.current = false;
        if (mounted.current) setSaving(false);
        return result === 'saved' && !refreshed ? 'savedReload' : result;
    }
    const editingDisabled = loading || saving || Boolean(action);

    const users = data ? visibleUsers(data.users) : [];
    const devices = data?.devices.filter((device) => !device.isEblocker && !device.isGateway) ?? [];
    return (
        <section className="family-page">
            <header className="family-heading">
                <div>
                    <span className="eyebrow">eBlocker</span>
                    <h1 ref={pageHeading} tabIndex={-1}>
                        {t.title}
                    </h1>
                    <p>{t.intro}</p>
                </div>
                <button
                    type="button"
                    className="family-secondary"
                    disabled={loading || saving}
                    onClick={() => void load()}
                >
                    {t.refresh}
                </button>
            </header>
            <nav className="family-actions" aria-label={t.title}>
                <button
                    className="family-primary"
                    disabled={!data || editingDisabled}
                    onClick={() => openEditor({ kind: 'user' })}
                >
                    {edit.addUser}
                </button>
                <button
                    className="family-secondary"
                    disabled={!data || editingDisabled}
                    onClick={() => openEditor({ kind: 'profile' })}
                >
                    {edit.addProfile}
                </button>
                <button
                    className="family-secondary"
                    disabled={!data || editingDisabled}
                    onClick={() => openEditor({ kind: 'filter' })}
                >
                    {edit.addFilter}
                </button>
                <a href={usersLink}>{t.manageUsers}</a>
                <a href="/settings/#!/parentalcontrol/blacklists/">{t.manageLists}</a>
            </nav>
            {action && data && (
                <FamilyEditor
                    action={action}
                    client={client}
                    data={data}
                    locale={locale}
                    mutate={mutate}
                    onClose={() => setAction(undefined)}
                />
            )}
            {failure && (
                <div className="family-error" role="alert">
                    <p>
                        {data && `${t.stale} `}
                        {errors[locale][failure]}
                    </p>
                    <button
                        type="button"
                        className="family-secondary"
                        disabled={loading || saving}
                        onClick={() => void load()}
                    >
                        {t.retry}
                    </button>
                </div>
            )}
            {loading && !data && (
                <p className="family-empty" role="status">
                    {t.loading}
                </p>
            )}
            {data && (
                <>
                    <section aria-labelledby="family-users">
                        <h2 id="family-users">{t.users}</h2>
                        {users.length ? (
                            <div className="family-user-grid">
                                {users.map((user) => {
                                    const profile = data.profiles.find(
                                        (item) => item.id === user.associatedProfileId,
                                    );
                                    const owned = assignedDevices(user, devices);
                                    return (
                                        <article className="family-card" key={user.id}>
                                            <h3>{userName(user, locale)}</h3>
                                            <p className="family-muted">
                                                {user.userRole === 'CHILD'
                                                    ? t.child
                                                    : user.userRole === 'PARENT'
                                                      ? t.parent
                                                      : user.userRole === 'OTHER'
                                                        ? t.other
                                                        : t.unknown}
                                            </p>
                                            <dl className="family-fields">
                                                <div>
                                                    <dt>{t.assignedProfile}</dt>
                                                    <dd>
                                                        {profile ? (
                                                            <button
                                                                type="button"
                                                                className="family-link-button"
                                                                onClick={() => {
                                                                    const target =
                                                                        document.getElementById(
                                                                            `family-profile-${profile.id}`,
                                                                        );
                                                                    target?.focus({
                                                                        preventScroll: true,
                                                                    });
                                                                    target?.scrollIntoView?.({
                                                                        block: 'start',
                                                                    });
                                                                }}
                                                            >
                                                                {profileName(profile, locale)}
                                                            </button>
                                                        ) : user.associatedProfileId == null ? (
                                                            t.noProfile
                                                        ) : (
                                                            t.unknown
                                                        )}
                                                    </dd>
                                                </div>
                                                {user.birthday && (
                                                    <div>
                                                        <dt>{t.birthday}</dt>
                                                        <dd>
                                                            <time
                                                                dateTime={calendarDate(
                                                                    user.birthday,
                                                                )}
                                                            >
                                                                {calendarDate(user.birthday)}
                                                            </time>
                                                        </dd>
                                                    </div>
                                                )}
                                            </dl>
                                            <h4>{t.assignments}</h4>
                                            {owned.length ? (
                                                <ul className="family-inline-list">
                                                    {owned.map((device) => (
                                                        <li key={device.id}>
                                                            {device.name?.trim() ||
                                                                device.ipAddresses[0] ||
                                                                t.unnamedDevice}
                                                        </li>
                                                    ))}
                                                </ul>
                                            ) : (
                                                <p className="family-muted">
                                                    {t.noAssignedDevices}
                                                </p>
                                            )}
                                            <a href={userLink(user.id)}>{t.manageUser}</a>
                                            {!user.system && (
                                                <div className="family-actions">
                                                    <button
                                                        className="family-secondary"
                                                        disabled={editingDisabled}
                                                        onClick={() =>
                                                            openEditor({ kind: 'user', user })
                                                        }
                                                    >
                                                        {edit.editUser}
                                                    </button>
                                                    <button
                                                        className="family-secondary"
                                                        disabled={editingDisabled}
                                                        onClick={() =>
                                                            openEditor({ kind: 'pin', user })
                                                        }
                                                    >
                                                        {edit.pin}
                                                    </button>
                                                    <button
                                                        className="family-secondary"
                                                        disabled={editingDisabled}
                                                        onClick={() =>
                                                            openEditor({ kind: 'deleteUser', user })
                                                        }
                                                    >
                                                        {edit.deleteUser}
                                                    </button>
                                                </div>
                                            )}
                                        </article>
                                    );
                                })}
                            </div>
                        ) : (
                            <p className="family-empty">{t.noUsers}</p>
                        )}
                    </section>
                    <section aria-labelledby="family-assignments">
                        <h2 id="family-assignments">{t.assignments}</h2>
                        {devices.length ? (
                            <div className="family-table-scroll family-card">
                                <table>
                                    <caption className="family-visually-hidden">
                                        {t.assignments}
                                    </caption>
                                    <thead>
                                        <tr>
                                            <th scope="col">{t.device}</th>
                                            <th scope="col">{t.assigned}</th>
                                            <th scope="col">{t.operating}</th>
                                            <th scope="col">
                                                <span className="family-visually-hidden">
                                                    {edit.assign}
                                                </span>
                                            </th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {devices.map((device) => (
                                            <tr key={device.id}>
                                                <th scope="row">
                                                    {device.name?.trim() ||
                                                        device.ipAddresses[0] ||
                                                        t.unnamedDevice}
                                                </th>
                                                <td>
                                                    {assignmentName(device, data.users, locale)}
                                                </td>
                                                <td>
                                                    {assignmentName(
                                                        device,
                                                        data.users,
                                                        locale,
                                                        true,
                                                    )}
                                                </td>
                                                <td>
                                                    <button
                                                        className="family-secondary"
                                                        disabled={editingDisabled}
                                                        onClick={() =>
                                                            openEditor({ kind: 'assign', device })
                                                        }
                                                    >
                                                        {edit.assign}
                                                    </button>
                                                </td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        ) : (
                            <p className="family-empty">{t.noDevices}</p>
                        )}
                    </section>
                    <section aria-labelledby="family-profiles">
                        <h2 id="family-profiles">{t.profiles}</h2>
                        {data.profiles.length ? (
                            <div className="family-profiles">
                                {data.profiles.map((profile) => (
                                    <div
                                        key={profile.id}
                                        id={`family-profile-${profile.id}`}
                                        tabIndex={-1}
                                    >
                                        <ProfileCard
                                            profile={profile}
                                            data={data}
                                            locale={locale}
                                            saving={saving || Boolean(action)}
                                            onBonus={addBonus}
                                            onEdit={
                                                !profile.builtin || profile.standard
                                                    ? () => openEditor({ kind: 'profile', profile })
                                                    : undefined
                                            }
                                            onDelete={
                                                !profile.builtin && !profile.standard
                                                    ? () =>
                                                          openEditor({
                                                              kind: 'deleteProfile',
                                                              profile,
                                                          })
                                                    : undefined
                                            }
                                            editingDisabled={editingDisabled}
                                        />
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p className="family-empty">{t.noProfiles}</p>
                        )}
                    </section>
                    <section aria-labelledby="family-filters">
                        <h2 id="family-filters">{t.filters}</h2>
                        {data.filters.length ? (
                            <ul className="family-filter-grid">
                                {data.filters.map((filter) => {
                                    const href = filterLink(filter);
                                    return (
                                        <li className="family-card" key={filter.id}>
                                            <h3>
                                                {href ? (
                                                    <a href={href}>{filterName(filter, locale)}</a>
                                                ) : (
                                                    filterName(filter, locale)
                                                )}
                                            </h3>
                                            <p>
                                                {filter.filterType === 'blacklist'
                                                    ? t.blockLists
                                                    : filter.filterType === 'whitelist'
                                                      ? t.allowLists
                                                      : filter.filterType}{' '}
                                                · {filter.disabled ? t.inactive : t.active}
                                            </p>
                                            {(filter.description?.[locale] ||
                                                filter.customerCreatedDescription) && (
                                                <p className="family-muted">
                                                    {filter.builtin
                                                        ? filter.description?.[locale]
                                                        : filter.customerCreatedDescription}
                                                </p>
                                            )}
                                            {(filter.filterType === 'blacklist' ||
                                                filter.filterType === 'whitelist') && (
                                                <div className="family-actions">
                                                    <button
                                                        className="family-secondary"
                                                        disabled={editingDisabled}
                                                        onClick={() =>
                                                            openEditor({ kind: 'filter', filter })
                                                        }
                                                    >
                                                        {edit.editFilter}
                                                    </button>
                                                    {!filter.builtin && (
                                                        <button
                                                            className="family-secondary"
                                                            disabled={editingDisabled}
                                                            onClick={() =>
                                                                openEditor({
                                                                    kind: 'deleteFilter',
                                                                    filter,
                                                                })
                                                            }
                                                        >
                                                            {edit.deleteFilter}
                                                        </button>
                                                    )}
                                                </div>
                                            )}
                                        </li>
                                    );
                                })}
                            </ul>
                        ) : (
                            <p className="family-empty">{t.noFilters}</p>
                        )}
                    </section>
                </>
            )}
        </section>
    );
}
