// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { weekDays } from './contracts';
import type { FamilyData, FamilyProfile } from './contracts';
import { saveProfile } from './api';
import type { ProfileSettings } from './api';
import { EditorFrame, FormValidation } from './EditorFrame';
import type { Mutate } from './EditorFrame';
import { editMessages } from './editMessages';
import { familyMessages } from './messages';
import { clockMinutes, filterName, profileName } from './presentation';

const initialSettings = (profile?: FamilyProfile): ProfileSettings => ({
    name: profile?.name ?? '',
    description: profile?.description ?? '',
    forSingleUser: profile?.forSingleUser ?? true,
    controlmodeUrls: profile?.controlmodeUrls ?? false,
    controlmodeTime: profile?.controlmodeTime ?? false,
    controlmodeMaxUsage: profile?.controlmodeMaxUsage ?? false,
    parentalControlSettingValidated: profile?.parentalControlSettingValidated ?? false,
    internetAccessRestrictionMode: profile?.internetAccessRestrictionMode ?? 1,
    accessibleSitesPackages: [...(profile?.accessibleSitesPackages ?? [])],
    inaccessibleSitesPackages: [...(profile?.inaccessibleSitesPackages ?? [])],
    maxUsageTimeByDay: { ...(profile?.maxUsageTimeByDay ?? {}) },
    internetAccessContingents: (profile?.internetAccessContingents ?? []).map((window) => ({
        ...window,
    })),
});
type WindowDraft = { onDay: number; from: string; till: string; totalMinutes?: number | null };
function windowsOf(profile: ProfileSettings): WindowDraft[] {
    return profile.internetAccessContingents.map((window) => ({
        onDay: window.onDay,
        from: clockMinutes(window.fromMinutes),
        till: clockMinutes(window.tillMinutes),
        ...(window.totalMinutes === undefined ? {} : { totalMinutes: window.totalMinutes }),
    }));
}
function minutes(value: string) {
    return /^([01]\d|2[0-3]):[0-5]\d$/.test(value)
        ? Number(value.slice(0, 2)) * 60 + Number(value.slice(3))
        : value === '24:00'
          ? 1440
          : NaN;
}

export function ProfileEditor({
    client,
    data,
    locale,
    mutate,
    onClose,
    profile,
}: {
    client: ConsoleClient;
    data: FamilyData;
    locale: Locale;
    mutate: Mutate;
    onClose: () => void;
    profile?: FamilyProfile;
}) {
    const t = editMessages[locale];
    const f = familyMessages[locale];
    const [value, setValue] = useState(() => initialSettings(profile));
    const [quotas, setQuotas] = useState(() =>
        weekDays.map((day) =>
            profile ? (profile.maxUsageTimeByDay[day]?.toString() ?? '') : '60',
        ),
    );
    const [windows, setWindows] = useState(() => windowsOf(initialSettings(profile)));
    const [templateId, setTemplateId] = useState('');
    const templates = data.profiles.filter((item) => item.builtin && !item.hidden);
    function update<K extends keyof ProfileSettings>(key: K, item: ProfileSettings[K]) {
        setValue((current) => ({ ...current, [key]: item }));
    }
    function updateWindow(index: number, patch: Partial<WindowDraft>) {
        setWindows((current) =>
            current.map((window, i) => (i === index ? { ...window, ...patch } : window)),
        );
    }
    function filterPicker(
        key: 'accessibleSitesPackages' | 'inaccessibleSitesPackages',
        type: string,
    ) {
        const options = data.filters.filter((filter) => filter.filterType === type);
        const missing = value[key].filter((id) => !options.some((option) => option.id === id));
        return (
            <fieldset className="family-filter-picker">
                <legend>{key === 'accessibleSitesPackages' ? f.allowLists : f.blockLists}</legend>
                {options.map((filter) => (
                    <label key={filter.id} className="family-check">
                        <input
                            type="checkbox"
                            checked={value[key].includes(filter.id)}
                            onChange={(event) =>
                                update(
                                    key,
                                    event.target.checked
                                        ? [...value[key], filter.id]
                                        : value[key].filter((id) => id !== filter.id),
                                )
                            }
                        />
                        {filterName(filter, locale)}
                        {filter.disabled ? ` (${f.inactive})` : ''}
                    </label>
                ))}
                {missing.map((id) => (
                    <label key={id} className="family-check">
                        <input
                            type="checkbox"
                            checked
                            onChange={() =>
                                update(
                                    key,
                                    value[key].filter((item) => item !== id),
                                )
                            }
                        />
                        {t.unavailableFilter} {id}
                    </label>
                ))}
                {!options.length && !missing.length && <p>{f.noFilters}</p>}
            </fieldset>
        );
    }
    return (
        <EditorFrame
            title={profile ? t.editProfile : t.addProfile}
            locale={locale}
            onClose={onClose}
            onSave={async () => {
                if (!profile?.standard && (!value.name?.trim() || value.name.trim().length > 128))
                    throw new FormValidation(t.profileNameInvalid);
                if ((value.description?.length ?? 0) > 4096)
                    throw new FormValidation(t.descriptionInvalid);
                if (
                    quotas.some((quota, index) =>
                        quota === ''
                            ? profile?.maxUsageTimeByDay[weekDays[index]] !== undefined
                            : !/^\d+$/.test(quota) || Number(quota) > 1440,
                    ) ||
                    (value.controlmodeMaxUsage &&
                        !profile?.controlmodeMaxUsage &&
                        quotas.some((quota) => quota === ''))
                )
                    throw new FormValidation(t.quotaInvalid);
                const parsed = windows.map((window) => ({
                    onDay: window.onDay,
                    fromMinutes: minutes(window.from),
                    tillMinutes: minutes(window.till),
                    ...(window.totalMinutes === undefined
                        ? {}
                        : { totalMinutes: window.totalMinutes }),
                }));
                if (
                    parsed.some(
                        (window) =>
                            !Number.isInteger(window.onDay) ||
                            window.onDay < 1 ||
                            window.onDay > 9 ||
                            !Number.isFinite(window.fromMinutes) ||
                            !Number.isFinite(window.tillMinutes) ||
                            window.fromMinutes >= window.tillMinutes,
                    )
                )
                    throw new FormValidation(t.windowInvalid);
                const maxUsageTimeByDay = Object.fromEntries(
                    weekDays.flatMap((day, index) =>
                        quotas[index] === '' ? [] : [[day, Number(quotas[index])]],
                    ),
                );
                return mutate(() =>
                    saveProfile(client, profile, {
                        ...value,
                        name: value.name!.trim(),
                        maxUsageTimeByDay,
                        internetAccessContingents: parsed,
                    }),
                );
            }}
        >
            {!profile?.standard && (
                <>
                    <label>
                        {t.name}
                        <input
                            value={value.name ?? ''}
                            maxLength={128}
                            onChange={(event) => update('name', event.target.value)}
                            required
                        />
                    </label>
                    <label>
                        {t.description}
                        <textarea
                            value={value.description ?? ''}
                            maxLength={4096}
                            onChange={(event) => update('description', event.target.value)}
                        />
                    </label>
                </>
            )}
            <label>
                {t.template}
                <select
                    value={templateId}
                    onChange={(event) => {
                        setTemplateId(event.target.value);
                        const template = templates.find(
                            (item) => item.id === Number(event.target.value),
                        );
                        if (!template) return;
                        const rules = initialSettings(template);
                        setValue((current) => ({
                            ...rules,
                            name: current.name,
                            description: current.description,
                            forSingleUser: current.forSingleUser,
                            parentalControlSettingValidated: false,
                        }));
                        setQuotas(
                            weekDays.map((day) => rules.maxUsageTimeByDay[day]?.toString() ?? ''),
                        );
                        setWindows(windowsOf(rules));
                    }}
                >
                    <option value="">{t.noTemplate}</option>
                    {templates.map((template) => (
                        <option key={template.id} value={template.id}>
                            {profileName(template, locale)}
                        </option>
                    ))}
                </select>
            </label>
            <p className="family-muted">{t.templateHelp}</p>
            <fieldset>
                <legend>{f.content}</legend>
                <label className="family-check">
                    <input
                        type="checkbox"
                        checked={value.controlmodeUrls}
                        onChange={(event) => update('controlmodeUrls', event.target.checked)}
                    />
                    {f.content}
                </label>
                <label>
                    {f.mode}
                    <select
                        value={value.internetAccessRestrictionMode ?? 1}
                        onChange={(event) =>
                            update(
                                'internetAccessRestrictionMode',
                                Number(event.target.value) as 0 | 1 | 2,
                            )
                        }
                    >
                        <option value={0}>{f.modeNone}</option>
                        <option value={1}>{f.modeBlock}</option>
                        <option value={2}>{f.modeAllow}</option>
                    </select>
                </label>
                {filterPicker('inaccessibleSitesPackages', 'blacklist')}
                {filterPicker('accessibleSitesPackages', 'whitelist')}
            </fieldset>
            <fieldset>
                <legend>{f.limits}</legend>
                <label className="family-check">
                    <input
                        type="checkbox"
                        checked={value.controlmodeMaxUsage}
                        onChange={(event) => update('controlmodeMaxUsage', event.target.checked)}
                    />
                    {f.limits}
                </label>
                <p className="family-muted">{t.quotaHelp}</p>
                <div className="family-quota-grid">
                    {weekDays.map((day, index) => (
                        <label key={day}>
                            {f.weekdays[index]}
                            <input
                                type="number"
                                min={0}
                                max={1440}
                                step={1}
                                value={quotas[index]}
                                onChange={(event) =>
                                    setQuotas((current) =>
                                        current.map((quota, i) =>
                                            i === index ? event.target.value : quota,
                                        ),
                                    )
                                }
                            />
                        </label>
                    ))}
                </div>
            </fieldset>
            <fieldset>
                <legend>{f.windows}</legend>
                <label className="family-check">
                    <input
                        type="checkbox"
                        checked={value.controlmodeTime}
                        onChange={(event) => update('controlmodeTime', event.target.checked)}
                    />
                    {f.windows}
                </label>
                <p className="family-muted">{t.windowHelp}</p>
                {windows.map((window, index) => (
                    <fieldset className="family-window" key={index}>
                        <legend>
                            {f.windows} {index + 1}
                        </legend>
                        <label>
                            {f.day}
                            <select
                                value={window.onDay}
                                onChange={(event) =>
                                    updateWindow(index, { onDay: Number(event.target.value) })
                                }
                            >
                                {f.weekdays.map((day, i) => (
                                    <option value={i + 1} key={i}>
                                        {day}
                                    </option>
                                ))}
                            </select>
                        </label>
                        <label>
                            {f.start}
                            <input
                                type="time"
                                value={window.from}
                                onChange={(event) =>
                                    updateWindow(index, { from: event.target.value })
                                }
                            />
                        </label>
                        <label>
                            {f.end}
                            <input
                                type="time"
                                disabled={window.till === '24:00'}
                                value={window.till === '24:00' ? '' : window.till}
                                onChange={(event) =>
                                    updateWindow(index, { till: event.target.value })
                                }
                            />
                        </label>
                        <label className="family-check">
                            <input
                                type="checkbox"
                                checked={window.till === '24:00'}
                                onChange={(event) =>
                                    updateWindow(index, {
                                        till: event.target.checked ? '24:00' : '23:59',
                                    })
                                }
                            />
                            {t.endOfDay}
                        </label>
                        <button
                            type="button"
                            className="family-secondary"
                            onClick={() =>
                                setWindows((current) => current.filter((_, i) => i !== index))
                            }
                        >
                            {t.removeWindow}
                        </button>
                    </fieldset>
                ))}
                <button
                    type="button"
                    className="family-secondary"
                    onClick={() =>
                        setWindows((current) => [
                            ...current,
                            { onDay: 8, from: '08:00', till: '20:00' },
                        ])
                    }
                >
                    {t.addWindow}
                </button>
            </fieldset>
            <label className="family-check">
                <input
                    type="checkbox"
                    checked={value.parentalControlSettingValidated}
                    onChange={(event) =>
                        update('parentalControlSettingValidated', event.target.checked)
                    }
                />
                {t.confirmRules}
            </label>
        </EditorFrame>
    );
}
