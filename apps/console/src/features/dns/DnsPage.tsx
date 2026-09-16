// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useState } from 'react';
import { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { useTransfer } from '../backup/transfer';
import { Confirmation } from '../protection/Confirmation';
import { useLoad } from '../protection/useLoad';
import { PageHeading, ResourcePanel } from '../system/presentation';
import { statsSchema } from './contracts';
import { messages } from './messages';
import { RecordsPanel } from './RecordsPanel';
import { ResolverPanel } from './ResolverPanel';
import './dns.css';

export function DnsPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const [revision, setRevision] = useState(0);
    return (
        <div className="dns-page">
            <PageHeading
                title={t.title}
                description={t.description}
                loading={false}
                refresh={() => setRevision((value) => value + 1)}
                locale={locale}
            />
            <div className="system-grid">
                <StatusPanel client={client} locale={locale} revision={revision} />
                <CachePanel client={client} locale={locale} />
            </div>
            <ResolverPanel client={client} locale={locale} revision={revision} />
            <RecordsPanel client={client} locale={locale} revision={revision} />
            <StatsPanel client={client} locale={locale} revision={revision} />
            <a href="#/network">{t.network}</a>
        </div>
    );
}

function StatusPanel({
    client,
    locale,
    revision,
}: {
    client: ConsoleClient;
    locale: Locale;
    revision: number;
}) {
    const t = messages[locale];
    const [localRevision, setLocalRevision] = useState(0);
    const loader = useCallback(
        (signal: AbortSignal) => client.get('/dns/status', z.boolean(), signal),
        [client],
    );
    const resource = useLoad(loader, revision + localRevision);
    const transfer = useTransfer();
    const [confirm, setConfirm] = useState<boolean>();
    const [saved, setSaved] = useState(false);
    const refresh = () => setLocalRevision((value) => value + 1);
    function apply() {
        if (confirm === undefined || resource.data === undefined) return;
        const expected = !confirm;
        const value = confirm;
        setConfirm(undefined);
        setSaved(false);
        void transfer.run(
            async (signal) => {
                const current = await loader(signal);
                if (current !== expected) throw new ApiError('conflict');
                await client.patch(
                    '/dns/status',
                    { expected: current, value },
                    z.boolean(),
                    signal,
                );
                if ((await loader(signal)) !== value) throw new ApiError('conflict');
            },
            () => {
                setSaved(true);
                refresh();
            },
            refresh,
        );
    }
    return (
        <ResourcePanel title={t.status} resource={resource} locale={locale} retry={refresh}>
            {(enabled) => (
                <div>
                    <p>
                        <strong>{enabled ? t.enabled : t.disabled}</strong>
                    </p>
                    <p>{t.statusInfo}</p>
                    <button
                        className="button"
                        disabled={
                            transfer.busy ||
                            resource.loading ||
                            !!resource.error ||
                            confirm !== undefined
                        }
                        onClick={() => {
                            setConfirm(!enabled);
                            setSaved(false);
                        }}
                    >
                        {enabled ? t.disable : t.enable}
                    </button>
                    {confirm !== undefined && (
                        <Confirmation
                            title={t.statusConfirm}
                            description={t.confirmInfo}
                            cancel={() => setConfirm(undefined)}
                            cancelLabel={t.cancel}
                        >
                            <p>{confirm ? t.enable : t.disable}</p>
                            <button className="button danger" onClick={apply}>
                                {t.confirm}
                            </button>
                        </Confirmation>
                    )}
                    {transfer.error && (
                        <p role="alert">
                            {transfer.error === 'conflict'
                                ? t.conflict
                                : `${errors[locale][transfer.error]} ${t.uncertain}`}
                        </p>
                    )}
                    {saved && <p role="status">{t.saved}</p>}
                </div>
            )}
        </ResourcePanel>
    );
}

function CachePanel({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const transfer = useTransfer();
    const [confirm, setConfirm] = useState(false);
    const [done, setDone] = useState(false);
    return (
        <section className="system-panel" aria-label={t.cache}>
            <h2>{t.cache}</h2>
            <p>{t.cacheInfo}</p>
            <button
                className="button secondary"
                disabled={transfer.busy || confirm}
                onClick={() => {
                    setConfirm(true);
                    setDone(false);
                }}
            >
                {t.clear}
            </button>
            {confirm && (
                <Confirmation
                    title={t.cacheConfirm}
                    description={t.cacheInfo}
                    cancel={() => setConfirm(false)}
                    cancelLabel={t.cancel}
                >
                    <button
                        className="button"
                        onClick={() => {
                            setConfirm(false);
                            void transfer.run(
                                (signal) => client.deleteVoid('/dns/cache', signal),
                                () => setDone(true),
                            );
                        }}
                    >
                        {t.clear}
                    </button>
                </Confirmation>
            )}
            {transfer.error && <p role="alert">{errors[locale][transfer.error]}</p>}
            {done && <p role="status">{t.cacheDone}</p>}
        </section>
    );
}

function StatsPanel({
    client,
    locale,
    revision,
}: {
    client: ConsoleClient;
    locale: Locale;
    revision: number;
}) {
    const t = messages[locale];
    const [localRevision, setLocalRevision] = useState(0);
    const loader = useCallback(
        (signal: AbortSignal) => client.get('/dns/stats?hours=4', statsSchema, signal),
        [client],
    );
    const resource = useLoad(loader, revision + localRevision);
    const ratings = {
        GOOD: t.good,
        MEDIUM: t.medium,
        BAD: t.bad,
        LOW: t.low,
        HIGH: t.high,
        FAST: t.fast,
        SLOW: t.slow,
        UNAVAILABLE: t.unavailable,
    };
    return (
        <ResourcePanel
            title={t.stats}
            resource={resource}
            locale={locale}
            retry={() => setLocalRevision((value) => value + 1)}
        >
            {(data) =>
                data.nameServerStats.length ? (
                    <div className="dns-table-scroll">
                        <table>
                            <caption className="sr-only">{t.stats}</caption>
                            <thead>
                                <tr>
                                    {[
                                        t.server,
                                        t.valid,
                                        t.invalid,
                                        t.error,
                                        t.timeout,
                                        t.average,
                                        t.median,
                                        t.range,
                                        t.rating,
                                    ].map((label) => (
                                        <th key={label}>{label}</th>
                                    ))}
                                </tr>
                            </thead>
                            <tbody>
                                {data.nameServerStats.map((stat) => (
                                    <tr key={stat.nameServer}>
                                        <th scope="row">{stat.nameServer}</th>
                                        <td>{stat.valid}</td>
                                        <td>{stat.invalid}</td>
                                        <td>{stat.error}</td>
                                        <td>{stat.timeout}</td>
                                        <td>
                                            {stat.responseTimeRating === 'UNAVAILABLE'
                                                ? t.unavailable
                                                : `${stat.responseTimeAverage} ms`}
                                        </td>
                                        <td>
                                            {stat.responseTimeRating === 'UNAVAILABLE'
                                                ? t.unavailable
                                                : `${stat.responseTimeMedian} ms`}
                                        </td>
                                        <td>
                                            {stat.responseTimeRating === 'UNAVAILABLE'
                                                ? t.unavailable
                                                : `${stat.responseTimeMin} / ${stat.responseTimeMax} ms`}
                                        </td>
                                        <td>
                                            {stat.reliabilityRating === 'UNAVAILABLE'
                                                ? t.unavailable
                                                : ratings[stat.rating]}
                                            <br />
                                            {t.reliability}: {ratings[stat.reliabilityRating]}
                                            <br />
                                            {t.response}: {ratings[stat.responseTimeRating]}
                                        </td>
                                    </tr>
                                ))}
                            </tbody>
                        </table>
                    </div>
                ) : (
                    <p>{t.noStats}</p>
                )
            }
        </ResourcePanel>
    );
}
