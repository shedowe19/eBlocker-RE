// SPDX-License-Identifier: EUPL-1.2
import type { ConsoleClient } from '../../api/client';
import type { Device } from '../../api/contracts';
import type { Locale } from '../../i18n';
import { Field, reported, ResourcePanel } from '../system/presentation';
import { useResource } from '../system/resource';
import { legacyDevice, legacyProfile, vpnStatusSchema } from './contracts';
import type { Profile } from './contracts';
import { messages } from './messages';

export function profileName(profile: Profile, locale: Locale) {
    return profile.name?.trim() || `${messages[locale].unnamed} #${profile.id}`;
}
export function ProfileCard({
    client,
    profile,
    devices,
    locale,
    revision,
    refresh,
}: {
    client: ConsoleClient;
    profile: Profile;
    devices?: Device[];
    locale: Locale;
    revision: number;
    refresh: () => void;
}) {
    const t = messages[locale];
    const status = useResource(
        client,
        `/vpn/profile/${profile.id}/status`,
        vpnStatusSchema,
        revision,
    );
    return (
        <article className="vpn-profile">
            <h3>{profileName(profile, locale)}</h3>
            {profile.description && <p>{profile.description}</p>}
            <p>
                {profile.enabled ? t.enabled : t.disabled}
                {profile.temporary && ` · ${t.draft}`}
            </p>
            <p>
                {t.dns}: {profile.nameServersEnabled ? t.yes : t.no}
            </p>
            <ResourcePanel title={t.state} resource={status} locale={locale} retry={refresh}>
                {(data) => (
                    <>
                        <p>
                            {data.profileId !== profile.id || (data.up && !data.active)
                                ? t.inconsistent
                                : data.up
                                  ? t.connected
                                  : data.active
                                    ? t.starting
                                    : t.stopped}
                        </p>
                        <dl className="system-fields">
                            <Field label={t.exit}>{reported(data.exitStatus, locale)}</Field>
                            <Field label={t.messages}>{reported(data.errors, locale)}</Field>
                        </dl>
                        <h4>{t.assigned}</h4>
                        {!data.devices.length ? (
                            <p>{t.noAssigned}</p>
                        ) : (
                            <ul>
                                {data.devices.map((id) => {
                                    const device = devices?.find((entry) => entry.id === id);
                                    return (
                                        <li key={id}>
                                            <a href={legacyDevice(id)}>
                                                {device?.name?.trim() || id}
                                            </a>
                                            {devices && !device && ` · ${t.unavailableDevice}`}
                                        </li>
                                    );
                                })}
                            </ul>
                        )}
                    </>
                )}
            </ResourcePanel>
            <a href={legacyProfile(profile.id)}>{t.profileDetails}</a>
        </article>
    );
}
