// SPDX-License-Identifier: EUPL-1.2
import { useId, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { DeviceSettingsPatch } from '../../api/contracts';
import { errorCode } from '../../api/http';
import { deviceMessages } from './messages';
import type { Device, DeviceLocale } from './types';

export interface DeviceEditorProps {
    device: Device;
    locale: DeviceLocale;
    onSave: (patch: DeviceSettingsPatch) => Promise<Device>;
    onSaved: (device: Device) => void;
    onCancel: () => void;
}

const booleanFields = [
    'enabled',
    'filterAdsEnabled',
    'filterTrackersEnabled',
    'malwareFilterEnabled',
    'sslEnabled',
] as const;
type BooleanField = (typeof booleanFields)[number];
type Draft = Pick<Device, BooleanField> & { name: string };

export function DeviceEditor({ device, locale, onSave, onSaved, onCancel }: DeviceEditorProps) {
    const t = deviceMessages[locale];
    const id = useId();
    // Keep the edit baseline stable if discovery refreshes the surrounding list.
    const [original] = useState(device);
    const [draft, setDraft] = useState<Draft>(() => ({
        name: device.name?.trim() || '',
        enabled: device.enabled,
        filterAdsEnabled: device.filterAdsEnabled,
        filterTrackersEnabled: device.filterTrackersEnabled,
        malwareFilterEnabled: device.malwareFilterEnabled,
        sslEnabled: device.sslEnabled,
    }));
    const [saving, setSaving] = useState(false);
    const [failure, setFailure] = useState<'saveError' | 'saveConflict' | 'saveValidation'>();
    const pending = useRef(false);
    const patch: DeviceSettingsPatch = {};
    if (draft.name.trim() !== (original.name?.trim() || '')) patch.name = draft.name.trim();
    for (const field of booleanFields) {
        if (draft[field] !== original[field]) patch[field] = draft[field];
    }
    const hasChanges = Object.keys(patch).length > 0;
    const invalidName =
        patch.name !== undefined && (patch.name.length < 1 || patch.name.length > 50);
    const filterFields: { field: BooleanField; label: string }[] = [
        { field: 'filterAdsEnabled', label: t.ads },
        { field: 'filterTrackersEnabled', label: t.trackers },
        { field: 'malwareFilterEnabled', label: t.malware },
        { field: 'sslEnabled', label: t.https },
    ];

    async function save(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (pending.current || !hasChanges || invalidName || device.isEblocker || device.isGateway)
            return;
        pending.current = true;
        setSaving(true);
        setFailure(undefined);
        try {
            const updated = await onSave(patch);
            onSaved(updated);
        } catch (error) {
            // Server errors can contain sensitive device identifiers or upstream responses.
            const code = errorCode(error);
            setFailure(
                code === 'conflict'
                    ? 'saveConflict'
                    : code === 'validation'
                      ? 'saveValidation'
                      : 'saveError',
            );
        } finally {
            pending.current = false;
            setSaving(false);
        }
    }

    function updateBoolean(field: BooleanField, checked: boolean) {
        setDraft((current) => ({ ...current, [field]: checked }));
        setFailure(undefined);
    }

    if (device.isEblocker || device.isGateway) return null;

    return (
        <form
            className="device-editor"
            onSubmit={save}
            aria-labelledby={`${id}-title`}
            aria-busy={saving}
        >
            <h3 id={`${id}-title`}>{t.editSettings}</h3>
            <fieldset disabled={saving} className="device-editor__fields">
                <legend className="devices__visually-hidden">{t.editSettings}</legend>
                <div className="devices__field">
                    <label htmlFor={`${id}-name`}>{t.deviceName}</label>
                    <input
                        id={`${id}-name`}
                        type="text"
                        value={draft.name}
                        maxLength={50}
                        autoComplete="off"
                        autoFocus
                        aria-invalid={invalidName || undefined}
                        aria-describedby={`${id}-name-help`}
                        onChange={(event) => {
                            setDraft((current) => ({ ...current, name: event.target.value }));
                            setFailure(undefined);
                        }}
                    />
                    <p
                        id={`${id}-name-help`}
                        className={invalidName ? 'device-editor__validation' : 'devices__muted'}
                    >
                        {invalidName ? t.invalidName : t.nameHelp}
                    </p>
                </div>
                <label className="device-editor__checkbox device-editor__protection">
                    <input
                        type="checkbox"
                        checked={draft.enabled}
                        disabled={device.paused}
                        onChange={(event) => updateBoolean('enabled', event.target.checked)}
                    />
                    <span>{t.deviceProtection}</span>
                </label>
                {device.paused && (
                    <p className="device-editor__pause-note">
                        {t.pauseEditNote}{' '}
                        <a
                            href={saving ? undefined : '/settings/'}
                            aria-disabled={saving || undefined}
                        >
                            {t.allSettings}
                        </a>
                    </p>
                )}
                <fieldset className="device-editor__filter-fields">
                    <legend>{t.filterSettings}</legend>
                    <p className="devices__muted device-details__description">
                        {t.filterDescription}
                    </p>
                    {filterFields.map(({ field, label }) => (
                        <label key={field} className="device-editor__checkbox">
                            <input
                                type="checkbox"
                                checked={draft[field]}
                                onChange={(event) => updateBoolean(field, event.target.checked)}
                            />
                            <span>{label}</span>
                        </label>
                    ))}
                </fieldset>
            </fieldset>
            {failure && (
                <p className="device-editor__error" role="alert">
                    {t[failure]}
                </p>
            )}
            <div className="device-editor__actions">
                <button
                    type="submit"
                    className="devices__primary-button"
                    disabled={saving || !hasChanges || invalidName}
                >
                    {saving ? t.saving : t.save}
                </button>
                <button
                    type="button"
                    className="devices__secondary-button"
                    disabled={saving}
                    onClick={onCancel}
                >
                    {t.cancel}
                </button>
            </div>
            {saving && (
                <p className="device-editor__progress" role="status">
                    {t.saving}
                </p>
            )}
        </form>
    );
}
