// SPDX-License-Identifier: EUPL-1.2
import { useId, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { Locale } from '../../i18n';
import { weekDays } from './contracts';
import type { FamilyData, FamilyProfile } from './contracts';
import { familyMessages } from './messages';
import { editMessages } from './editMessages';
import {
    calendarDate,
    clockMinutes,
    filterLink,
    filterName,
    profileName,
    userLink,
    userName,
    usersLink,
    visibleUsers,
} from './presentation';

export type BonusResult = 'bonusSaved' | 'bonusReloadFailed' | 'bonusUncertain';
export function ProfileCard({
    profile,
    data,
    locale,
    saving,
    onBonus,
    onEdit,
    onDelete,
    editingDisabled,
}: {
    profile: FamilyProfile;
    data: FamilyData;
    locale: Locale;
    saving: boolean;
    onBonus: (id: number, minutes: number) => Promise<BonusResult>;
    onEdit?: () => void;
    onDelete?: () => void;
    editingDisabled?: boolean;
}) {
    const t = familyMessages[locale];
    const id = useId();
    const users = visibleUsers(data.users).filter(
        (user) => user.associatedProfileId === profile.id,
    );
    const [bonusOpen, setBonusOpen] = useState(false);
    const mode = profile.internetAccessRestrictionMode;
    const eligibleForBonus =
        profile.controlmodeMaxUsage && !profile.builtin && !profile.standard && users.length > 0;
    return (
        <article className="family-card family-profile" aria-labelledby={`${id}-title`}>
            <header className="family-card-heading">
                <h3 id={`${id}-title`}>{profileName(profile, locale)}</h3>
                <span className="family-tags">
                    {profile.builtin && <span>{t.builtin}</span>}
                    {profile.hidden && <span>{t.system}</span>}
                </span>
            </header>
            {profile.description && <p className="family-muted">{profile.description}</p>}
            <p className={profile.internetBlocked ? 'family-warning' : 'family-muted'}>
                {profile.internetBlocked === undefined || profile.internetBlocked === null
                    ? t.unknown
                    : profile.internetBlocked
                      ? t.blocked
                      : t.notBlocked}
            </p>
            {users.length > 0 && (
                <ul className="family-inline-list">
                    {users.map((user) => (
                        <li key={user.id}>
                            <a href={userLink(user.id)}>{userName(user, locale)}</a>
                        </li>
                    ))}
                </ul>
            )}
            <dl className="family-profile-status">
                <div>
                    <dt>{t.content}</dt>
                    <dd>{profile.controlmodeUrls ? t.active : t.inactive}</dd>
                </div>
                <div>
                    <dt>{t.windows}</dt>
                    <dd>{profile.controlmodeTime ? t.active : t.inactive}</dd>
                </div>
                <div>
                    <dt>{t.limits}</dt>
                    <dd>{profile.controlmodeMaxUsage ? t.active : t.inactive}</dd>
                </div>
            </dl>
            <details className="family-rules">
                <summary>{t.savedRules}</summary>
                <p className="family-muted">{t.inactiveNote}</p>
                <dl className="family-fields">
                    <div>
                        <dt>{t.mode}</dt>
                        <dd>
                            {mode === 0
                                ? t.modeNone
                                : mode === 1
                                  ? t.modeBlock
                                  : mode === 2
                                    ? t.modeAllow
                                    : t.unknown}
                        </dd>
                    </div>
                </dl>
                <h4>{t.blockLists}</h4>
                <FilterSelection
                    ids={profile.inaccessibleSitesPackages}
                    data={data}
                    locale={locale}
                />
                <h4>{t.allowLists}</h4>
                <FilterSelection
                    ids={profile.accessibleSitesPackages}
                    data={data}
                    locale={locale}
                />
                <div className="family-rule-columns">
                    <div>
                        <h4>{t.limits}</h4>
                        <dl className="family-week">
                            {weekDays.map((day, index) => (
                                <div key={day}>
                                    <dt>{t.weekdays[index]}</dt>
                                    <dd>
                                        {profile.maxUsageTimeByDay[day] === undefined
                                            ? t.notSet
                                            : `${profile.maxUsageTimeByDay[day]} ${t.minutes}`}
                                    </dd>
                                </div>
                            ))}
                        </dl>
                    </div>
                    <div>
                        <h4>{t.windows}</h4>
                        {profile.internetAccessContingents.length ? (
                            <div className="family-table-scroll">
                                <table>
                                    <caption className="family-visually-hidden">
                                        {profileName(profile, locale)}: {t.windows}
                                    </caption>
                                    <thead>
                                        <tr>
                                            <th scope="col">{t.day}</th>
                                            <th scope="col">{t.start}</th>
                                            <th scope="col">{t.end}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {profile.internetAccessContingents.map((window, index) => (
                                            <tr key={index}>
                                                <th scope="row">{t.weekdays[window.onDay - 1]}</th>
                                                <td>{clockMinutes(window.fromMinutes)}</td>
                                                <td>{clockMinutes(window.tillMinutes)}</td>
                                            </tr>
                                        ))}
                                    </tbody>
                                </table>
                            </div>
                        ) : (
                            <p className="family-muted">{t.noWindows}</p>
                        )}
                    </div>
                </div>
            </details>
            {profile.bonusTimeUsage && (
                <dl className="family-fields family-bonus-stored">
                    <div>
                        <dt>{t.bonus}</dt>
                        <dd>
                            {profile.bonusTimeUsage.bonusMinutes} {t.minutes}
                        </dd>
                    </div>
                    <div>
                        <dt>{t.bonusDate}</dt>
                        <dd>
                            <time dateTime={calendarDate(profile.bonusTimeUsage.dateTime)}>
                                {calendarDate(profile.bonusTimeUsage.dateTime)}
                            </time>
                        </dd>
                    </div>
                </dl>
            )}
            <div className="family-actions">
                {onEdit && (
                    <button
                        className="family-secondary"
                        disabled={editingDisabled}
                        onClick={onEdit}
                    >
                        {editMessages[locale].editProfile}
                    </button>
                )}
                {onDelete && (
                    <button
                        className="family-secondary"
                        disabled={editingDisabled}
                        onClick={onDelete}
                    >
                        {editMessages[locale].deleteProfile}
                    </button>
                )}
                <a href={users.length ? userLink(users[0].id) : usersLink}>{t.manageRules}</a>
                {eligibleForBonus && !bonusOpen && (
                    <button
                        type="button"
                        className="family-secondary"
                        disabled={saving}
                        onClick={() => setBonusOpen(true)}
                    >
                        {t.addBonus}
                    </button>
                )}
            </div>
            {bonusOpen && eligibleForBonus && (
                <BonusForm
                    locale={locale}
                    saving={saving}
                    onCancel={() => setBonusOpen(false)}
                    onSave={(minutes) => onBonus(profile.id, minutes)}
                />
            )}
        </article>
    );
}

function FilterSelection({
    ids,
    data,
    locale,
}: {
    ids: number[];
    data: FamilyData;
    locale: Locale;
}) {
    const t = familyMessages[locale];
    return ids.length ? (
        <ul className="family-inline-list">
            {ids.map((filterId) => {
                const filter = data.filters.find((item) => item.id === filterId);
                const href = filter && filterLink(filter);
                const name = filter ? filterName(filter, locale) : t.filterFallback(filterId);
                return (
                    <li key={filterId}>
                        {href ? <a href={href}>{name}</a> : name}
                        {filter?.disabled && <span className="family-muted"> ({t.inactive})</span>}
                    </li>
                );
            })}
        </ul>
    ) : (
        <p className="family-muted">{t.noLists}</p>
    );
}

function BonusForm({
    locale,
    saving,
    onSave,
    onCancel,
}: {
    locale: Locale;
    saving: boolean;
    onSave: (minutes: number) => Promise<BonusResult>;
    onCancel: () => void;
}) {
    const t = familyMessages[locale];
    const id = useId();
    const [minutes, setMinutes] = useState('');
    const [invalid, setInvalid] = useState(false);
    const [result, setResult] = useState<BonusResult>();
    const pending = useRef(false);
    async function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (pending.current || saving || result) return;
        const value = Number(minutes);
        if (!Number.isInteger(value) || value < 1 || value > 1440) {
            setInvalid(true);
            return;
        }
        pending.current = true;
        try {
            setResult(await onSave(value));
        } finally {
            pending.current = false;
        }
    }
    return (
        <form
            className="family-bonus-form"
            aria-label={t.addBonus}
            onSubmit={submit}
            noValidate
            aria-busy={saving}
        >
            {!result && (
                <>
                    <label htmlFor={`${id}-minutes`}>{t.bonusMinutes}</label>
                    <input
                        id={`${id}-minutes`}
                        type="number"
                        inputMode="numeric"
                        min={1}
                        max={1440}
                        step={1}
                        value={minutes}
                        disabled={saving}
                        required
                        autoFocus
                        aria-invalid={invalid || undefined}
                        onChange={(event) => {
                            setMinutes(event.target.value);
                            setInvalid(false);
                        }}
                    />
                    <p className="family-muted">{t.bonusHelp}</p>
                    {invalid && (
                        <p className="family-error" role="alert">
                            {t.bonusInvalid}
                        </p>
                    )}
                </>
            )}
            {result && (
                <p
                    className={result === 'bonusSaved' ? 'family-success' : 'family-warning'}
                    role={result === 'bonusSaved' ? 'status' : 'alert'}
                >
                    {t[result]}
                </p>
            )}
            <div className="family-actions">
                {!result && (
                    <button type="submit" className="family-primary" disabled={saving}>
                        {saving ? t.bonusSaving : t.bonusSubmit}
                    </button>
                )}
                <button
                    type="button"
                    className="family-secondary"
                    disabled={saving}
                    onClick={onCancel}
                >
                    {result ? t.done : t.cancel}
                </button>
            </div>
        </form>
    );
}
