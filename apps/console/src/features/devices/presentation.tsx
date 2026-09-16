// SPDX-License-Identifier: EUPL-1.2
import { deviceMessages } from './messages';
import type { Device, DeviceLocale } from './types';

export function deviceName(device: Device, locale: DeviceLocale): string {
    return device.name?.trim() || deviceMessages[locale].unnamed;
}

export function ProtectionStatus({ device, locale }: { device: Device; locale: DeviceLocale }) {
    const t = deviceMessages[locale];
    if (device.isEblocker) {
        return <span className="device-badge device-badge--disabled">{t.notApplicable}</span>;
    }
    const state = device.paused ? 'paused' : device.enabled ? 'enabled' : 'disabled';
    return <span className={`device-badge device-badge--${state}`}>{t[state]}</span>;
}

export function ConnectionStatus({ online, locale }: { online: boolean; locale: DeviceLocale }) {
    return (
        <span className={`device-connection${online ? ' device-connection--online' : ''}`}>
            <span className="device-connection__dot" aria-hidden="true" />
            {deviceMessages[locale][online ? 'online' : 'offline']}
        </span>
    );
}

export function DeviceRoles({ device, locale }: { device: Device; locale: DeviceLocale }) {
    const t = deviceMessages[locale];
    const labels = [
        device.isCurrentDevice && t.current,
        device.isGateway && t.gateway,
        device.isEblocker && t.eblocker,
        device.isVpnClient && t.vpnClient,
    ].filter((label): label is string => Boolean(label));

    return labels.length ? (
        <span className="device-roles">
            {labels.map((label) => (
                <span key={label} className="device-role">
                    {label}
                </span>
            ))}
        </span>
    ) : null;
}

export function LastSeen({ seconds, locale }: { seconds?: number; locale: DeviceLocale }) {
    const date =
        seconds === undefined || !Number.isFinite(seconds) ? null : new Date(seconds * 1000);
    if (!date || !Number.isFinite(date.getTime())) {
        return <span>{deviceMessages[locale].unknown}</span>;
    }
    return (
        <time dateTime={date.toISOString()}>
            {new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
                date,
            )}
        </time>
    );
}

export function filterModeName(mode: string | undefined, locale: DeviceLocale): string {
    const t = deviceMessages[locale];
    switch (mode) {
        case 'AUTOMATIC':
            return t.modeAutomatic;
        case 'PLUG_AND_PLAY':
            return t.modePlugAndPlay;
        case 'ADVANCED':
            return t.modeAdvanced;
        case 'NONE':
            return t.modeNone;
        default:
            return mode?.trim() || t.unknown;
    }
}
