// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useState } from 'react';
import { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { useTransfer } from '../backup/transfer';
import { Confirmation } from '../protection/Confirmation';
import { useLoad } from '../protection/useLoad';
import { ResourcePanel } from './presentation';
import {
    editableNetworkSchema,
    dhcpServersSchema,
    leaseTimes,
    networkValidation,
    networkSettings,
    sameNetwork,
} from './networkEditing';
import type { NetworkConfiguration, NetworkSettings } from './networkEditing';
import { networkMessages } from './networkMessages';
import './network.css';

export function NetworkEditor({
    client,
    locale,
    close,
    updated,
}: {
    client: ConsoleClient;
    locale: Locale;
    close: () => void;
    updated: () => void;
}) {
    const t = networkMessages[locale];
    const [revision, setRevision] = useState(0);
    const loader = useCallback(
        async (signal: AbortSignal) => {
            const [configuration, dhcpActive] = await Promise.all([
                client.get('/network', editableNetworkSchema, signal),
                client.get('/network/dhcpstate', z.boolean(), signal),
            ]);
            return { configuration, dhcpActive };
        },
        [client],
    );
    const resource = useLoad(loader, revision);
    const [draft, setDraft] = useState<NetworkConfiguration>();
    const [confirm, setConfirm] = useState(false);
    const [autoConfirmed, setAutoConfirmed] = useState(false);
    const [validation, setValidation] = useState<
        ReturnType<typeof networkValidation> | 'unchanged'
    >();
    const [result, setResult] = useState<{ configuration: NetworkSettings; reboot: boolean }>();
    const [uncertain, setUncertain] = useState(false);
    const transfer = useTransfer();
    useEffect(() => {
        if (resource.data) {
            setDraft(resource.data.configuration);
            setConfirm(false);
            setValidation(undefined);
            setAutoConfirmed(false);
            setResult((previous) =>
                resource.data!.configuration.pendingReboot &&
                resource.data!.configuration.pendingConfiguration
                    ? {
                          configuration: resource.data!.configuration.pendingConfiguration,
                          reboot: true,
                      }
                    : previous?.reboot
                      ? undefined
                      : previous,
            );
        }
    }, [resource.data]);
    function reload() {
        setUncertain(false);
        setRevision((value) => value + 1);
    }
    function review() {
        if (!draft || !resource.data) return;
        const invalid = networkValidation(draft);
        if (invalid) {
            setValidation(invalid);
            return;
        }
        if (sameNetwork(draft, resource.data.configuration)) {
            setValidation('unchanged');
            return;
        }
        setValidation(undefined);
        setConfirm(true);
    }
    function apply() {
        if (!draft || !resource.data) return;
        const baseline = resource.data.configuration;
        const selected = draft;
        let written = false;
        setConfirm(false);
        setUncertain(false);
        setResult(undefined);
        void transfer.run(
            async (signal) => {
                const current = await client.get('/network', editableNetworkSchema, signal);
                if (
                    current.pendingReboot ||
                    current.revision !== baseline.revision ||
                    !sameNetwork(current, baseline)
                )
                    throw new ApiError('conflict');
                // Preserve the fresh read-only/VPN fields; change only fields shown in this form.
                const body = networkSettings(selected);
                written = true;
                const accepted = await client.patch(
                    '/network',
                    { expected: current, value: body },
                    editableNetworkSchema,
                    signal,
                );
                if (accepted.pendingReboot || accepted.rebootNecessary) {
                    if (!accepted.pendingConfiguration) throw new ApiError('invalidResponse');
                    return { configuration: accepted.pendingConfiguration, reboot: true };
                }
                const observed = await client.get('/network', editableNetworkSchema, signal);
                if (!sameNetwork(observed, accepted)) throw new ApiError('conflict');
                return { configuration: observed, reboot: false };
            },
            (accepted) => {
                setResult(accepted);
                updated();
                if (!accepted.reboot) setRevision((value) => value + 1);
            },
            () => {
                setUncertain(written);
                setRevision((value) => value + 1);
                updated();
            },
        );
    }
    const mode = draft?.automatic ? 'auto' : draft?.expertMode ? 'expert' : 'individual';
    const disabled =
        transfer.busy ||
        resource.loading ||
        !!resource.error ||
        uncertain ||
        !!result?.reboot ||
        !!resource.data?.configuration.pendingReboot;
    const labels = {
        ipAddress: t.ip,
        networkMask: t.mask,
        gateway: t.gateway,
        nameServerPrimary: t.primary,
        nameServerSecondary: t.secondary,
        dhcpRangeFirst: t.first,
        dhcpRangeLast: t.last,
    };
    const field = (key: keyof typeof labels) => (
        <label key={key}>
            {labels[key]}
            <input
                name={key}
                value={draft?.[key] ?? ''}
                onChange={(event) =>
                    setDraft(
                        (value) => value && { ...value, [key]: event.target.value.trim() || null },
                    )
                }
            />
        </label>
    );
    return (
        <ResourcePanel title={t.configuration} resource={resource} locale={locale} retry={reload}>
            {({ configuration, dhcpActive }) => (
                <div className="network-editor">
                    <p>
                        {t.dhcpActual}: {dhcpActive ? t.yes : t.no}
                    </p>
                    {draft && (
                        <form
                            onSubmit={(event) => {
                                event.preventDefault();
                                review();
                            }}
                        >
                            <fieldset disabled={disabled || confirm}>
                                <label>
                                    {t.mode}
                                    <select
                                        value={mode}
                                        onChange={(event) => {
                                            const selected = event.target.value;
                                            setAutoConfirmed(false);
                                            setDraft({
                                                ...draft,
                                                automatic: selected === 'auto',
                                                expertMode: selected === 'expert',
                                                dhcp:
                                                    selected === 'individual' ||
                                                    (selected === 'expert' && draft.dhcp),
                                                dhcpLeaseTime:
                                                    selected === 'expert'
                                                        ? draft.dhcpLeaseTime
                                                        : 600,
                                            });
                                        }}
                                    >
                                        <option value="auto">{t.auto}</option>
                                        <option value="individual">{t.individual}</option>
                                        <option value="expert">{t.expert}</option>
                                    </select>
                                </label>
                                <p>
                                    {mode === 'auto'
                                        ? t.autoInfo
                                        : draft.dhcp
                                          ? t.individualInfo
                                          : t.externalInfo}
                                </p>
                                {mode !== 'auto' && (
                                    <>
                                        <div className="network-form-grid">
                                            {field('ipAddress')}
                                            {field('networkMask')}
                                            {field('gateway')}
                                            {!draft.dnsServer && (
                                                <>
                                                    {field('nameServerPrimary')}
                                                    {field('nameServerSecondary')}
                                                </>
                                            )}
                                        </div>
                                        {mode === 'expert' && (
                                            <label className="network-check">
                                                <input
                                                    type="checkbox"
                                                    checked={draft.dhcp}
                                                    onChange={(event) =>
                                                        setDraft({
                                                            ...draft,
                                                            dhcp: event.target.checked,
                                                        })
                                                    }
                                                />
                                                {t.dhcp}
                                            </label>
                                        )}
                                        {draft.dhcp && (
                                            <>
                                                <div className="network-form-grid">
                                                    {field('dhcpRangeFirst')}
                                                    {field('dhcpRangeLast')}
                                                    {mode === 'expert' && (
                                                        <label>
                                                            {t.lease}
                                                            <select
                                                                value={draft.dhcpLeaseTime}
                                                                onChange={(event) =>
                                                                    setDraft({
                                                                        ...draft,
                                                                        dhcpLeaseTime: Number(
                                                                            event.target.value,
                                                                        ),
                                                                    })
                                                                }
                                                            >
                                                                {[
                                                                    ...new Set([
                                                                        ...leaseTimes,
                                                                        configuration.dhcpLeaseTime,
                                                                    ]),
                                                                ]
                                                                    .sort((a, b) => a - b)
                                                                    .map((seconds) => (
                                                                        <option
                                                                            key={seconds}
                                                                            value={seconds}
                                                                        >
                                                                            {seconds}
                                                                        </option>
                                                                    ))}
                                                            </select>
                                                        </label>
                                                    )}
                                                </div>
                                                <label className="network-check">
                                                    <input
                                                        type="checkbox"
                                                        checked={draft.ipFixedByDefault}
                                                        onChange={(event) =>
                                                            setDraft({
                                                                ...draft,
                                                                ipFixedByDefault:
                                                                    event.target.checked,
                                                            })
                                                        }
                                                    />
                                                    {t.fixed}
                                                </label>
                                            </>
                                        )}
                                    </>
                                )}
                                {mode === 'auto' && !configuration.automatic && (
                                    <label className="network-check">
                                        <input
                                            type="checkbox"
                                            checked={autoConfirmed}
                                            onChange={(event) =>
                                                setAutoConfirmed(event.target.checked)
                                            }
                                        />
                                        {t.autoConfirmed}
                                    </label>
                                )}
                                {validation && (
                                    <p role="alert">
                                        {validation === 'mask' ? t.maskInvalid : t[validation]}
                                    </p>
                                )}
                                <button
                                    className="button"
                                    disabled={
                                        mode === 'auto' &&
                                        !configuration.automatic &&
                                        !autoConfirmed
                                    }
                                >
                                    {transfer.busy ? t.saving : t.save}
                                </button>
                            </fieldset>
                        </form>
                    )}
                    <DhcpDiscovery
                        client={client}
                        locale={locale}
                        ownIp={configuration.ipAddress ?? undefined}
                    />
                    {transfer.error && (
                        <p className="error-banner" role="alert">
                            {transfer.error === 'conflict'
                                ? t.stale
                                : errors[locale][transfer.error]}
                        </p>
                    )}
                    {uncertain && <p role="alert">{t.uncertain}</p>}
                    {confirm && (
                        <Confirmation
                            title={t.confirmTitle}
                            description={t.confirmInfo}
                            cancel={() => setConfirm(false)}
                            cancelLabel={t.cancel}
                        >
                            <button className="button danger" onClick={apply}>
                                {t.apply}
                            </button>
                        </Confirmation>
                    )}
                    {result && (
                        <section className="network-result">
                            <h3>{t.planned}</h3>
                            <p role="status">{result.reboot ? t.accepted : t.saved}</p>
                            <dl>
                                <dt>{t.ip}</dt>
                                <dd>{result.configuration.ipAddress}</dd>
                                <dt>{t.gateway}</dt>
                                <dd>{result.configuration.gateway}</dd>
                                <dt>{t.mask}</dt>
                                <dd>{result.configuration.networkMask}</dd>
                            </dl>
                            <button className="button secondary" onClick={() => window.print()}>
                                {t.print}
                            </button>
                            {result.reboot && <NetworkRestart client={client} locale={locale} />}
                        </section>
                    )}
                    <div className="network-actions">
                        <button
                            className="button secondary"
                            disabled={transfer.busy}
                            onClick={reload}
                        >
                            {t.reload}
                        </button>
                        <button
                            className="button secondary"
                            disabled={transfer.busy}
                            onClick={close}
                        >
                            {t.close}
                        </button>
                        <a href="#/dns">{t.dns}</a>
                    </div>
                </div>
            )}
        </ResourcePanel>
    );
}

function DhcpDiscovery({
    client,
    locale,
    ownIp,
}: {
    client: ConsoleClient;
    locale: Locale;
    ownIp?: string;
}) {
    const t = networkMessages[locale];
    const transfer = useTransfer();
    const [servers, setServers] = useState<string[]>();
    return (
        <div className="network-discovery">
            <button
                className="button secondary"
                disabled={transfer.busy}
                onClick={() => {
                    setServers(undefined);
                    void transfer.run(
                        (signal) => client.discoverDhcpServers(dhcpServersSchema, signal),
                        setServers,
                    );
                }}
            >
                {transfer.busy ? t.scanning : t.discovery}
            </button>
            {transfer.error && <p role="alert">{errors[locale][transfer.error]}</p>}
            {servers && (
                <div role="status">
                    {servers.length ? (
                        <>
                            <p>{t.scanFound}</p>
                            <ul>
                                {servers.map((server) => (
                                    <li key={server}>
                                        {server}
                                        {server === ownIp ? ' (eBlocker)' : ''}
                                    </li>
                                ))}
                            </ul>
                        </>
                    ) : (
                        <p>{t.scanNone}</p>
                    )}
                </div>
            )}
        </div>
    );
}

export function NetworkRestart({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = networkMessages[locale];
    const transfer = useTransfer();
    const [confirm, setConfirm] = useState(false);
    const [requested, setRequested] = useState(false);
    return (
        <div>
            {!requested && (
                <button className="button" disabled={confirm} onClick={() => setConfirm(true)}>
                    {t.restart}
                </button>
            )}
            {confirm && (
                <Confirmation
                    title={t.restartTitle}
                    description={t.restartInfo}
                    cancel={() => setConfirm(false)}
                    cancelLabel={t.cancel}
                >
                    <button
                        className="button danger"
                        onClick={() => {
                            setConfirm(false);
                            setRequested(true);
                            void transfer.run(
                                (signal) => client.postVoid('/systemstatus/reboot', {}, signal),
                                () => {},
                            );
                        }}
                    >
                        {t.restartConfirm}
                    </button>
                </Confirmation>
            )}
            {requested && (
                <p role={transfer.error ? 'alert' : 'status'}>
                    {transfer.error ? t.restartUncertain : t.restarting}
                </p>
            )}
        </div>
    );
}
