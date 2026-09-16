// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { useTransfer } from '../backup/transfer';
import { Confirmation } from '../protection/Confirmation';
import { useLoad } from '../protection/useLoad';
import { AddressList, ResourcePanel } from '../system/presentation';
import { parseServers, resolversSchema, sameResolvers, validServers } from './contracts';
import type { Resolvers } from './contracts';
import { messages } from './messages';

export function ResolverPanel({
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
        (signal: AbortSignal) => client.get('/dns/config/resolvers', resolversSchema, signal),
        [client],
    );
    const resource = useLoad(loader, revision + localRevision);
    const transfer = useTransfer();
    const [draft, setDraft] = useState<Resolvers>();
    const [servers, setServers] = useState('');
    const [invalid, setInvalid] = useState<'invalidServers' | 'unchanged'>();
    const [confirm, setConfirm] = useState(false);
    const [saved, setSaved] = useState(false);
    const refresh = () => setLocalRevision((value) => value + 1);
    useEffect(() => {
        if (resource.data) {
            setDraft({
                ...resource.data,
                customResolverMode: resource.data.customResolverMode ?? 'default',
            });
            setServers(resource.data.customNameServers.join('\n'));
            setConfirm(false);
            setInvalid(undefined);
        }
    }, [resource.data]);
    const value = draft && { ...draft, customNameServers: parseServers(servers) };
    function review() {
        setSaved(false);
        if (!value || !resource.data) return;
        if (!validServers(value)) {
            setInvalid('invalidServers');
            return;
        }
        if (sameResolvers(value, resource.data)) {
            setInvalid('unchanged');
            return;
        }
        setInvalid(undefined);
        setConfirm(true);
    }
    function apply() {
        if (!value || !resource.data) return;
        const baseline = resource.data;
        const selected = value;
        setConfirm(false);
        void transfer.run(
            async (signal) => {
                const current = await loader(signal);
                if (!sameResolvers(current, baseline)) throw new ApiError('conflict');
                const expected = current;
                await client.patch(
                    '/dns/config/resolvers',
                    { expected, value: { ...selected, dhcpNameServers: current.dhcpNameServers } },
                    resolversSchema,
                    signal,
                );
                const observed = await loader(signal);
                if (!sameResolvers(observed, selected)) throw new ApiError('conflict');
            },
            () => {
                setSaved(true);
                refresh();
            },
            refresh,
        );
    }
    return (
        <ResourcePanel title={t.resolvers} resource={resource} locale={locale} retry={refresh}>
            {(data) => (
                <div>
                    <h3>{t.dhcpServers}</h3>
                    <AddressList addresses={data.dhcpNameServers} locale={locale} />
                    {draft && (
                        <form
                            onSubmit={(event) => {
                                event.preventDefault();
                                review();
                            }}
                        >
                            <fieldset
                                disabled={
                                    transfer.busy || resource.loading || !!resource.error || confirm
                                }
                            >
                                <label>
                                    {t.mode}
                                    <select
                                        value={draft.defaultResolver}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                defaultResolver: event.target
                                                    .value as Resolvers['defaultResolver'],
                                            })
                                        }
                                    >
                                        <option value="dhcp">{t.dhcp}</option>
                                        <option value="custom">{t.custom}</option>
                                        <option value="tor">{t.tor}</option>
                                    </select>
                                </label>
                                {draft.defaultResolver === 'tor' && <p>{t.torInfo}</p>}
                                <label>
                                    {t.customServers}
                                    <textarea
                                        rows={5}
                                        spellCheck={false}
                                        value={servers}
                                        onChange={(event) => setServers(event.target.value)}
                                    />
                                </label>
                                <p>{t.serverHint}</p>
                                <label>
                                    {t.order}
                                    <select
                                        value={draft.customResolverMode ?? 'default'}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                customResolverMode: event.target
                                                    .value as Resolvers['customResolverMode'],
                                            })
                                        }
                                    >
                                        <option value="default">{t.ordered}</option>
                                        <option value="round_robin">{t.roundRobin}</option>
                                        <option value="random">{t.random}</option>
                                    </select>
                                </label>
                                {invalid && <p role="alert">{t[invalid]}</p>}
                                <button className="button">{t.save}</button>
                            </fieldset>
                        </form>
                    )}
                    {confirm && (
                        <Confirmation
                            title={t.resolverConfirm}
                            description={t.confirmInfo}
                            cancel={() => setConfirm(false)}
                            cancelLabel={t.cancel}
                        >
                            <p>
                                {t.mode}:{' '}
                                {value?.defaultResolver === 'dhcp'
                                    ? t.dhcp
                                    : value?.defaultResolver === 'tor'
                                      ? t.tor
                                      : t.custom}
                            </p>
                            <AddressList addresses={value?.customNameServers} locale={locale} />
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
