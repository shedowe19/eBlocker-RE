// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { agentStatusSchema, wireGuardPlanSchema } from './contracts';
import type { AgentStatus, WireGuardPlan } from './contracts';
import './wireguard.css';
import { WireGuardManager } from './WireGuardManager';

const de = {
    title: 'WireGuard',
    intro: 'Profile verwalten, Verbindungen steuern und Netzwerkpfade prüfen.',
    status: 'Netzwerkdienst',
    loading: 'Status wird geladen …',
    unavailable: 'Der Netzwerkdienst ist nicht verfügbar.',
    kernel: 'WireGuard im laufenden Kernel',
    present: 'Erkannt',
    absent: 'Nicht erkannt',
    retry: 'Erneut versuchen',
    interfaces: 'Netzwerkschnittstellen',
    noInterfaces: 'Keine Schnittstellen gemeldet.',
    up: 'Aktiv',
    down: 'Inaktiv',
    config: 'WireGuard-Konfiguration',
    hint: 'Füge den Inhalt einer WireGuard-Konfigurationsdatei ein (bis 64 KiB). Die Prüfung erfolgt lokal auf deinem eBlocker.',
    inspect: 'Konfiguration prüfen',
    busy: 'Wird geprüft …',
    clear: 'Eingabe löschen',
    invalid: 'Die Konfiguration ist leer oder zu groß.',
    result: 'Prüfergebnis',
    boundary:
        'Die Konfiguration wurde geprüft. Diese Prüfung hat keine Verbindung aufgebaut und keine Routen, DNS-Einstellungen oder Firewallregeln verändert.',
    reviewOnly:
        'Die Konfigurationsprüfung verändert keine Verbindungen. Verwende die Profilverwaltung zum Importieren und Verbinden.',
    addresses: 'Tunneladressen',
    dns: 'DNS-Server',
    ipv4: 'Standardroute IPv4',
    ipv6: 'Standardroute IPv6',
    full: 'Vollständig im Plan',
    split: 'Nicht vollständig im Plan',
    peers: 'Gegenstellen',
    key: 'Öffentlicher Schlüssel',
    endpoint: 'Endpunkt',
    routes: 'Zugelassene Netze',
    risks: 'Vor Aktivierung zu klären',
    exclusions: 'Erforderliche Endpunkt-Ausnahmen',
    none: 'Nicht angegeben',
    more: 'Weitere Hinweise',
    noEndpoint: 'Keine feste Gegenstelle konfiguriert',
};
type Texts = { [K in keyof typeof de]: string };
const en: Texts = {
    title: 'WireGuard',
    intro: 'Manage profiles, control connections and review network paths.',
    status: 'Network service',
    loading: 'Loading status …',
    unavailable: 'The network service is unavailable.',
    kernel: 'WireGuard in the running kernel',
    present: 'Detected',
    absent: 'Not detected',
    retry: 'Try again',
    interfaces: 'Network interfaces',
    noInterfaces: 'No interfaces reported.',
    up: 'Up',
    down: 'Down',
    config: 'WireGuard configuration',
    hint: 'Paste a WireGuard configuration file (up to 64 KiB). Validation runs locally on your eBlocker.',
    inspect: 'Validate configuration',
    busy: 'Validating …',
    clear: 'Clear input',
    invalid: 'The configuration is empty or too large.',
    result: 'Validation result',
    boundary:
        'The configuration has been validated. This check did not establish a connection or change routes, DNS settings or firewall rules.',
    reviewOnly:
        'Validation does not change connections. Use profile management to import and connect.',
    addresses: 'Tunnel addresses',
    dns: 'DNS servers',
    ipv4: 'IPv4 default route',
    ipv6: 'IPv6 default route',
    full: 'Fully included in plan',
    split: 'Not fully included in plan',
    peers: 'Peers',
    key: 'Public key',
    endpoint: 'Endpoint',
    routes: 'Allowed networks',
    risks: 'Resolve before activation',
    exclusions: 'Required endpoint exclusions',
    none: 'Not specified',
    more: 'Additional notices',
    noEndpoint: 'No fixed endpoint configured',
};
const risks: Record<Locale, Record<string, string>> = {
    de: {
        no_kill_switch: 'Die Prüfung aktiviert keinen Kill Switch.',
        ipv4_not_fully_tunneled: 'Der Plan leitet nicht sämtlichen IPv4-Verkehr durch den Tunnel.',
        ipv6_not_fully_tunneled: 'Der Plan leitet nicht sämtlichen IPv6-Verkehr durch den Tunnel.',
        dns_not_configured: 'Es sind keine DNS-Server konfiguriert.',
        dns_outside_tunnel: 'Mindestens ein DNS-Server liegt außerhalb der geplanten Tunnelnetze.',
    },
    en: {
        no_kill_switch: 'Validation does not activate a kill switch.',
        ipv4_not_fully_tunneled: 'The plan does not route all IPv4 traffic through the tunnel.',
        ipv6_not_fully_tunneled: 'The plan does not route all IPv6 traffic through the tunnel.',
        dns_not_configured: 'No DNS servers are configured.',
        dns_outside_tunnel: 'At least one DNS server is outside the planned tunnel networks.',
    },
};

const germanNotices: Record<string, string> = {
    'Validation only: no tunnel, routes, DNS settings, or kill switch have been applied.':
        'Nur geprüft: Tunnel, Routen, DNS-Einstellungen und Kill Switch wurden nicht aktiviert.',
    'A peer has no configured endpoint and requires an authenticated incoming handshake before outbound traffic can use it.':
        'Eine Gegenstelle hat keinen festen Endpunkt. Ausgehender Verkehr benötigt zuerst einen authentifizierten eingehenden Verbindungsaufbau.',
    'Hostname endpoints require DNS resolution, validation, and bypass-route planning before any routes are applied.':
        'Bei Endpunkten mit Hostnamen müssen vor der Aktivierung DNS-Auflösung und Ausnahmerouten geprüft werden.',
    'Literal endpoints covered by tunnel or connected routes require verified bypass routes through the original uplink to prevent routing loops.':
        'Endpunkte innerhalb geplanter Tunnel- oder Schnittstellennetze benötigen geprüfte Ausnahmerouten über den bisherigen Internetzugang, damit keine Routenschleifen entstehen.',
};

export function WireGuardPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = locale === 'de' ? de : en;
    const [status, setStatus] = useState<AgentStatus>();
    const [statusError, setStatusError] = useState<ErrorCode>();
    const [revision, setRevision] = useState(0);
    const [config, setConfig] = useState('');
    const [busy, setBusy] = useState(false);
    const [failure, setFailure] = useState<ErrorCode | 'invalid'>();
    const [plan, setPlan] = useState<WireGuardPlan>();
    const pending = useRef<AbortController | undefined>(undefined);
    const sequence = useRef(0);
    useEffect(() => {
        const abort = new AbortController();
        setStatusError(undefined);
        void client
            .get('/network-agent/status', agentStatusSchema, abort.signal)
            .then((result) => {
                if (!abort.signal.aborted) setStatus(result.data);
            })
            .catch((error) => {
                if (!abort.signal.aborted) {
                    setStatus(undefined);
                    setStatusError(errorCode(error));
                }
            });
        return () => abort.abort();
    }, [client, revision]);
    useEffect(
        () => () => {
            ++sequence.current;
            pending.current?.abort();
        },
        [],
    );
    useEffect(() => {
        clear();
    }, [locale]);
    function clear() {
        ++sequence.current;
        pending.current?.abort();
        pending.current = undefined;
        setConfig('');
        setPlan(undefined);
        setFailure(undefined);
        setBusy(false);
    }
    async function validate(event: FormEvent) {
        event.preventDefault();
        if (pending.current) return;
        if (!config.trim() || new TextEncoder().encode(config).length > 65536) {
            setFailure('invalid');
            return;
        }
        const abort = new AbortController();
        pending.current = abort;
        const current = ++sequence.current;
        setBusy(true);
        setFailure(undefined);
        setPlan(undefined);
        try {
            const result = await client.post(
                '/wireguard/validate',
                { config },
                wireGuardPlanSchema,
                abort.signal,
            );
            if (current === sequence.current) {
                setPlan(result.data);
                setConfig('');
            }
        } catch (error) {
            if (current === sequence.current) {
                setFailure(errorCode(error));
                setConfig('');
            }
        } finally {
            if (current === sequence.current) {
                pending.current = undefined;
                setBusy(false);
            }
        }
    }
    return (
        <section className="wireguard-page">
            <div className="page-heading">
                <div>
                    <span className="eyebrow">VPN</span>
                    <h1>{t.title}</h1>
                    <p>{t.intro}</p>
                </div>
            </div>
            <p className="feature-note">{t.reviewOnly}</p>
            <WireGuardManager client={client} locale={locale} />
            <section className="wireguard-panel" aria-label={t.status}>
                <h2>{t.status}</h2>
                {statusError ? (
                    <div role="alert">
                        <p>
                            {t.unavailable} {errors[locale][statusError]}
                        </p>
                        <button
                            className="button secondary"
                            onClick={() => setRevision((value) => value + 1)}
                        >
                            {t.retry}
                        </button>
                    </div>
                ) : !status ? (
                    <p role="status">{t.loading}</p>
                ) : (
                    <>
                        <p>
                            {t.kernel}:{' '}
                            <strong>
                                {status.capabilities.wireguard.kernelFamilyRegistered
                                    ? t.present
                                    : t.absent}
                            </strong>
                        </p>
                        <h3>{t.interfaces}</h3>
                        {status.interfaces.length ? (
                            <ul className="wireguard-interfaces">
                                {status.interfaces.map((item) => (
                                    <li key={item.index}>
                                        <strong>{item.name}</strong>
                                        <span>{item.up ? t.up : t.down}</span>
                                        <span>
                                            {item.addresses
                                                .map((address) => address.prefix)
                                                .join(', ') || t.none}
                                        </span>
                                    </li>
                                ))}
                            </ul>
                        ) : (
                            <p>{t.noInterfaces}</p>
                        )}
                    </>
                )}
            </section>
            <form
                className="wireguard-panel"
                onSubmit={(event) => void validate(event)}
                aria-busy={busy}
            >
                <label htmlFor="wireguard-configuration">{t.config}</label>
                <p id="wireguard-hint">{t.hint}</p>
                <textarea
                    id="wireguard-configuration"
                    aria-describedby="wireguard-hint"
                    value={config}
                    disabled={busy}
                    spellCheck={false}
                    autoComplete="off"
                    autoCapitalize="none"
                    onChange={(event) => {
                        setConfig(event.target.value);
                        setPlan(undefined);
                    }}
                    rows={9}
                />
                {failure && (
                    <p role="alert" className="error-message">
                        {failure === 'invalid' ? t.invalid : errors[locale][failure]}
                    </p>
                )}
                <div className="wireguard-actions">
                    <button className="button primary" disabled={busy || !config.trim()}>
                        {busy ? t.busy : t.inspect}
                    </button>
                    <button className="button secondary" type="button" onClick={clear}>
                        {t.clear}
                    </button>
                </div>
            </form>
            {plan && (
                <section className="wireguard-panel" aria-label={t.result}>
                    <h2>{t.result}</h2>
                    <p role="status">{t.boundary}</p>
                    <dl className="wireguard-fields">
                        <div>
                            <dt>{t.addresses}</dt>
                            <dd>{plan.interfaceAddresses.join(', ') || t.none}</dd>
                        </div>
                        <div>
                            <dt>{t.dns}</dt>
                            <dd>{plan.dns.join(', ') || t.none}</dd>
                        </div>
                        <div>
                            <dt>{t.ipv4}</dt>
                            <dd>{plan.defaultRouteIPv4 ? t.full : t.split}</dd>
                        </div>
                        <div>
                            <dt>{t.ipv6}</dt>
                            <dd>{plan.defaultRouteIPv6 ? t.full : t.split}</dd>
                        </div>
                    </dl>
                    <h3>{t.peers}</h3>
                    {plan.peers.map((peer) => (
                        <dl className="wireguard-peer" key={peer.publicKey}>
                            <dt>{t.key}</dt>
                            <dd>{peer.publicKey}</dd>
                            <dt>{t.endpoint}</dt>
                            <dd>{peer.endpoint || t.noEndpoint}</dd>
                            <dt>{t.routes}</dt>
                            <dd>{peer.allowedIPs.join(', ')}</dd>
                        </dl>
                    ))}
                    <h3>{t.risks}</h3>
                    <ul>
                        {plan.leakRisks.map((code) => (
                            <li key={code}>
                                {Object.hasOwn(risks[locale], code) ? risks[locale][code] : code}
                            </li>
                        ))}
                    </ul>
                    {plan.endpointExclusions.length > 0 && (
                        <>
                            <h3>{t.exclusions}</h3>
                            <ul>
                                {plan.endpointExclusions.map((value) => (
                                    <li key={value}>{value}</li>
                                ))}
                            </ul>
                        </>
                    )}
                    {plan.warnings.length > 0 && (
                        <details>
                            <summary>{t.more}</summary>
                            <ul>
                                {plan.warnings.map((message) => (
                                    <li key={message}>
                                        {locale === 'de' && Object.hasOwn(germanNotices, message)
                                            ? germanNotices[message]
                                            : message}
                                    </li>
                                ))}
                            </ul>
                        </details>
                    )}
                </section>
            )}
        </section>
    );
}
