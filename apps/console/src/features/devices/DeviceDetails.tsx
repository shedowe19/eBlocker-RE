// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useId, useRef, useState } from 'react';
import type { DeviceSettingsPatch } from '../../api/contracts';
import { DeviceEditor } from './DeviceEditor';
import { deviceMessages } from './messages';
import {
    ConnectionStatus,
    deviceName,
    DeviceRoles,
    filterModeName,
    LastSeen,
    ProtectionStatus,
} from './presentation';
import type { Device, DeviceLocale } from './types';

export interface DeviceDetailsProps {
    device: Device;
    locale: DeviceLocale;
    onBack: () => void;
    onSave?: (patch: DeviceSettingsPatch) => Promise<Device>;
}

export function DeviceDetails(props: DeviceDetailsProps) {
    return <DeviceDetailsContent key={props.device.id} {...props} />;
}

function DeviceDetailsContent({
    device: sourceDevice,
    locale,
    onBack,
    onSave,
}: DeviceDetailsProps) {
    const t = deviceMessages[locale];
    const id = useId();
    const [editing, setEditing] = useState(false);
    const [saved, setSaved] = useState(false);
    const editButtonRef = useRef<HTMLButtonElement>(null);
    const wasEditing = useRef(false);
    useEffect(() => {
        if (wasEditing.current && !editing) editButtonRef.current?.focus();
        wasEditing.current = editing;
    }, [editing]);
    const [confirmed, setConfirmed] = useState<{ source: Device; result: Device } | null>(null);
    const device = confirmed?.source === sourceDevice ? confirmed.result : sourceDevice;
    const canEdit = Boolean(onSave) && !device.isEblocker && !device.isGateway;
    const ipv4 = device.ipAddresses.filter((address) => !address.includes(':'));
    const ipv6 = device.ipAddresses.filter((address) => address.includes(':'));
    const filters = [
        { label: t.ads, enabled: device.filterAdsEnabled },
        { label: t.trackers, enabled: device.filterTrackersEnabled },
        { label: t.malware, enabled: device.malwareFilterEnabled },
        { label: t.https, enabled: device.sslEnabled },
    ];

    return (
        <section className="devices device-details" aria-labelledby={`${id}-title`}>
            <button className="devices__back" type="button" onClick={onBack} disabled={editing}>
                <span aria-hidden="true">←</span> {t.back}
            </button>
            <header className="device-details__header">
                <p className="device-details__eyebrow">{t.details}</p>
                <h2 id={`${id}-title`}>{deviceName(device, locale)}</h2>
                <DeviceRoles device={device} locale={locale} />
            </header>

            {saved && (
                <p className="device-editor__success" role="status">
                    {t.saved}
                </p>
            )}
            {canEdit && !editing && (
                <button
                    type="button"
                    className="devices__secondary-button device-details__edit"
                    ref={editButtonRef}
                    onClick={() => {
                        setSaved(false);
                        setEditing(true);
                    }}
                >
                    {t.edit}
                </button>
            )}
            {device.isGateway && onSave && (
                <p className="devices__muted device-details__description">{t.gatewaySettings}</p>
            )}

            {editing && canEdit && onSave ? (
                <DeviceEditor
                    device={device}
                    locale={locale}
                    onSave={onSave}
                    onCancel={() => setEditing(false)}
                    onSaved={(result) => {
                        setConfirmed({ source: sourceDevice, result });
                        setEditing(false);
                        setSaved(true);
                    }}
                />
            ) : (
                <>
                    <dl className="device-details__summary">
                        <div>
                            <dt>{t.protection}</dt>
                            <dd>
                                <ProtectionStatus device={device} locale={locale} />
                            </dd>
                        </div>
                        <div>
                            <dt>{t.connectionFilter}</dt>
                            <dd>
                                <ConnectionStatus online={device.isOnline} locale={locale} />
                            </dd>
                        </div>
                        <div>
                            <dt>{t.lastSeen}</dt>
                            <dd>
                                <LastSeen seconds={device.lastSeen} locale={locale} />
                            </dd>
                        </div>
                    </dl>

                    <div className="device-details__columns">
                        <section
                            className="device-details__panel"
                            aria-labelledby={`${id}-network`}
                        >
                            <h3 id={`${id}-network`}>{t.network}</h3>
                            <dl className="device-details__fields">
                                <div>
                                    <dt>{t.vendor}</dt>
                                    <dd>{device.vendor?.trim() || t.unknown}</dd>
                                </div>
                                <div>
                                    <dt>{t.mac}</dt>
                                    <dd className="device-details__address">
                                        {device.hardwareAddress?.trim() || t.unknown}
                                    </dd>
                                </div>
                                <div>
                                    <dt>IPv4</dt>
                                    <dd>
                                        <Addresses values={ipv4} fallback={t.noAddress} />
                                    </dd>
                                </div>
                                <div>
                                    <dt>IPv6</dt>
                                    <dd>
                                        <Addresses values={ipv6} fallback={t.noAddress} />
                                    </dd>
                                </div>
                            </dl>
                        </section>
                        <section
                            className="device-details__panel"
                            aria-labelledby={`${id}-filters`}
                        >
                            <h3 id={`${id}-filters`}>{t.filterSettings}</h3>
                            {device.isEblocker ? (
                                <p className="devices__muted device-details__description">
                                    {t.applianceFilters}
                                </p>
                            ) : (
                                <>
                                    <p className="devices__muted device-details__description">
                                        {t.filterDescription}
                                    </p>
                                    <dl className="device-details__fields device-details__fields--filters">
                                        {filters.map(({ label, enabled }) => (
                                            <div key={label}>
                                                <dt>{label}</dt>
                                                <dd>{enabled ? t.enabled : t.disabled}</dd>
                                            </div>
                                        ))}
                                        <div>
                                            <dt>{t.mode}</dt>
                                            <dd>{filterModeName(device.filterMode, locale)}</dd>
                                        </div>
                                    </dl>
                                </>
                            )}
                        </section>
                    </div>
                </>
            )}
        </section>
    );
}

function Addresses({ values, fallback }: { values: string[]; fallback: string }) {
    return values.length ? (
        <ul className="devices__addresses">
            {values.map((value, index) => (
                <li key={`${value}-${index}`}>{value}</li>
            ))}
        </ul>
    ) : (
        <span className="devices__muted">{fallback}</span>
    );
}
