// SPDX-License-Identifier: EUPL-1.2
import type { Device } from '../api/contracts';
export const device: Device = {
    id: 'device:aabbccddeeff',
    name: 'Arbeitszimmer',
    vendor: 'Example',
    hardwareAddress: 'aa:bb:cc:dd:ee:ff',
    ipAddresses: ['10.0.17.20', '2001:db8::20'],
    lastSeen: 1_789_545_600.125,
    isOnline: true,
    isCurrentDevice: true,
    isGateway: false,
    isEblocker: false,
    isVpnClient: false,
    enabled: true,
    paused: false,
    filterAdsEnabled: true,
    filterTrackersEnabled: true,
    malwareFilterEnabled: true,
    sslEnabled: false,
    filterMode: 'AUTOMATIC',
};
export const appliance: Device = {
    ...device,
    id: 'device:001122334455',
    name: 'eBlocker',
    isEblocker: true,
    isCurrentDevice: false,
};
export function token(
    passwordRequired = false,
    value = 'test-token',
    expiresOn = Math.floor(Date.now() / 1000) + 3600,
) {
    return { token: value, appContext: 'ADMINCONSOLE', expiresOn, passwordRequired };
}
export function json(value: unknown, status = 200) {
    return new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}
