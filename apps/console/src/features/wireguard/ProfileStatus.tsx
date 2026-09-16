// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';
import { protectionObserved, tunnelObserved } from './managementContracts';
import type { ProfileStatus as Status } from './managementContracts';
import { managementMessages, phases } from './managementMessages';

export function ProfileStatus({
    profile,
    locale,
    checkedAt,
}: {
    profile: Status;
    locale: Locale;
    checkedAt: number;
}) {
    const t = managementMessages[locale];
    const runtime = profile.runtime;
    const protectedNow = protectionObserved(runtime);
    const date = (value: number) =>
        new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium' }).format(value);
    return (
        <section className="wireguard-runtime" aria-label={`${t.observed}: ${profile.profileId}`}>
            <h3>{t.observed}</h3>
            <p>
                {t.checked}:{' '}
                <time dateTime={new Date(checkedAt).toISOString()}>{date(checkedAt)}</time>
            </p>
            <dl className="wireguard-fields">
                <div>
                    <dt>{t.plannedAddresses}</dt>
                    <dd>{profile.plan.interfaceAddresses.join(', ') || t.none}</dd>
                </div>
                <div>
                    <dt>{t.plannedEndpoints}</dt>
                    <dd>{profile.plan.peers.map((peer) => peer.endpoint || t.none).join(', ')}</dd>
                </div>
                {!runtime && (
                    <div>
                        <dt>{t.expectedRoutes}</dt>
                        <dd>
                            {profile.plan.peers.flatMap((peer) => peer.allowedIPs).join(', ') ||
                                t.none}
                        </dd>
                    </div>
                )}
            </dl>
            {!runtime ? (
                <p>{t.noRuntime}</p>
            ) : (
                <>
                    <p className="wireguard-state">
                        {runtime.phase === 'active' && !tunnelObserved(runtime)
                            ? t.notConfirmed
                            : (phases[locale][runtime.phase] ?? t.phaseUnknown)}
                    </p>
                    <dl className="wireguard-fields">
                        <div>
                            <dt>{t.interface}</dt>
                            <dd>
                                {runtime.target.interfaceName} ·{' '}
                                {tunnelObserved(runtime) ? t.tunnelActive : t.notConfirmed}
                            </dd>
                        </div>
                        <div>
                            <dt>{t.killSwitch}</dt>
                            <dd>{protectedNow ? t.verified : t.notVerified}</dd>
                        </div>
                        <div>
                            <dt>{t.dns}</dt>
                            <dd>{t.dnsUnknown}</dd>
                        </div>
                        <div>
                            <dt>{t.routing}</dt>
                            <dd>{protectedNow ? t.verified : t.notVerified}</dd>
                        </div>
                        <div>
                            <dt>{t.ipv4Policy}</dt>
                            <dd>
                                {protectedNow && runtime.observation.policy?.ipv4PoliciesVerified
                                    ? t.verified
                                    : t.notVerified}
                            </dd>
                        </div>
                        <div>
                            <dt>{t.ipv6Policy}</dt>
                            <dd>
                                {protectedNow && runtime.observation.policy?.ipv6PoliciesVerified
                                    ? t.verified
                                    : t.notVerified}
                            </dd>
                        </div>
                        <div>
                            <dt>{protectedNow ? t.observedRoutes : t.expectedRoutes}</dt>
                            <dd>
                                {(protectedNow
                                    ? runtime.policy!.allowedIPs
                                    : runtime.plan.peers.flatMap((peer) => peer.allowedIPs)
                                ).join(', ') || t.none}
                            </dd>
                        </div>
                    </dl>
                    {runtime.observation.peers.map((peer) => (
                        <dl className="wireguard-peer" key={peer.publicKey}>
                            <dt>{t.peer}</dt>
                            <dd>{peer.publicKey}</dd>
                            <dt>{t.handshake}</dt>
                            <dd>
                                {peer.lastHandshakeUnix === 0 ? (
                                    t.never
                                ) : (
                                    <time
                                        dateTime={new Date(
                                            peer.lastHandshakeUnix * 1000,
                                        ).toISOString()}
                                    >
                                        {date(peer.lastHandshakeUnix * 1000)}
                                    </time>
                                )}
                            </dd>
                            <dt>{t.receive}</dt>
                            <dd>
                                {Number.isSafeInteger(peer.receiveBytes)
                                    ? `${new Intl.NumberFormat(locale).format(peer.receiveBytes)} B`
                                    : t.counterUnavailable}
                            </dd>
                            <dt>{t.transmit}</dt>
                            <dd>
                                {Number.isSafeInteger(peer.transmitBytes)
                                    ? `${new Intl.NumberFormat(locale).format(peer.transmitBytes)} B`
                                    : t.counterUnavailable}
                            </dd>
                        </dl>
                    ))}
                </>
            )}
        </section>
    );
}
