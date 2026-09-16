// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useId, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { useLoad } from '../protection/useLoad';
import { Field, PageHeading, reported, ResourcePanel } from '../system/presentation';
import { useResource } from '../system/resource';
import {
    legacyMobile,
    legacyTor,
    legacyVpn,
    mobileStatusSchema,
    profilesSchema,
    torCountriesSchema,
} from './contracts';
import { DeviceRouting } from './DeviceRouting';
import { messages } from './messages';
import { ProfileCard } from './ProfileCard';

export function VpnPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const selectionId = useId();
    const [revision, setRevision] = useState(0);
    const [selectedDevice, setSelectedDevice] = useState('');
    const [busy, setBusy] = useState(false);
    const profiles = useResource(client, '/vpn/profiles', profilesSchema, revision);
    const loadDevices = useCallback((signal: AbortSignal) => client.devices(signal), [client]);
    const devices = useLoad(loadDevices, revision);
    const torCountries = useResource(
        client,
        '/tor/countries/selected',
        torCountriesSchema,
        revision,
    );
    const mobile = useResource(client, '/openvpn/status', mobileStatusSchema, revision);
    const refresh = () => setRevision((previous) => previous + 1);
    const activeProfiles = profiles.data?.filter((profile) => !profile.deleted);
    const eligibleDevices = devices.data?.filter(
        (device) => !device.isEblocker && !device.isGateway,
    );
    const selected = eligibleDevices?.find((device) => device.id === selectedDevice);
    const loading = [profiles, devices, torCountries, mobile].some((resource) => resource.loading);
    return (
        <>
            <PageHeading
                title={t.title}
                description={t.intro}
                loading={loading || busy}
                refresh={refresh}
                locale={locale}
            />
            <div className="vpn-legacy-links">
                <a href={legacyVpn}>{t.manageProfiles}</a>
                <a href={legacyTor}>{t.torSettings}</a>
                <a href={legacyMobile}>{t.mobileSettings}</a>
            </div>
            <p className="feature-note">{t.preserved}</p>
            <div className="vpn-sections">
                <ResourcePanel
                    title={t.deviceSection}
                    resource={devices}
                    locale={locale}
                    retry={refresh}
                >
                    {() => (
                        <>
                            {!eligibleDevices?.length ? (
                                <p>{t.noDevices}</p>
                            ) : (
                                <label className="vpn-device-choice" htmlFor={selectionId}>
                                    {t.chooseDevice}
                                    <select
                                        id={selectionId}
                                        value={selected ? selectedDevice : ''}
                                        disabled={busy}
                                        onChange={(event) => setSelectedDevice(event.target.value)}
                                    >
                                        <option value="">{t.choose}</option>
                                        {eligibleDevices.map((device) => (
                                            <option key={device.id} value={device.id}>
                                                {device.name?.trim() || device.id}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                            )}
                            {selected && (
                                <DeviceRouting
                                    key={selected.id}
                                    device={selected}
                                    profiles={activeProfiles ?? []}
                                    client={client}
                                    locale={locale}
                                    revision={revision}
                                    stale={
                                        !!devices.error ||
                                        devices.loading ||
                                        !!profiles.error ||
                                        profiles.loading ||
                                        !activeProfiles
                                    }
                                    refresh={refresh}
                                    onBusy={setBusy}
                                />
                            )}
                        </>
                    )}
                </ResourcePanel>
                <ResourcePanel
                    title={t.profiles}
                    resource={profiles}
                    locale={locale}
                    retry={refresh}
                >
                    {() =>
                        !activeProfiles?.length ? (
                            <p>{t.emptyProfiles}</p>
                        ) : (
                            <div className="vpn-profiles">
                                {activeProfiles.map((profile) => (
                                    <ProfileCard
                                        key={profile.id}
                                        client={client}
                                        profile={profile}
                                        devices={devices.error ? undefined : devices.data}
                                        locale={locale}
                                        revision={revision}
                                        refresh={refresh}
                                    />
                                ))}
                            </div>
                        )
                    }
                </ResourcePanel>
                <div className="system-grid">
                    <ResourcePanel
                        title={t.torSection}
                        resource={torCountries}
                        locale={locale}
                        retry={refresh}
                    >
                        {(countries) =>
                            countries.length ? (
                                <ul className="vpn-countries">
                                    {countries.map((country, index) => (
                                        <li key={`${country}-${index}`}>{country.toUpperCase()}</li>
                                    ))}
                                </ul>
                            ) : (
                                <p>{t.unrestricted}</p>
                            )
                        }
                    </ResourcePanel>
                    <ResourcePanel
                        title={t.mobile}
                        resource={mobile}
                        locale={locale}
                        retry={refresh}
                    >
                        {(data) => (
                            <>
                                <p>{data.isRunning ? t.running : t.notRunning}</p>
                                {data.isFirstStart && <p>{t.firstStart}</p>}
                                <dl className="system-fields">
                                    <Field label={t.host}>{reported(data.host, locale)}</Field>
                                    <Field label={t.port}>
                                        {reported(data.mappedPort, locale)}
                                    </Field>
                                </dl>
                            </>
                        )}
                    </ResourcePanel>
                </div>
            </div>
        </>
    );
}
