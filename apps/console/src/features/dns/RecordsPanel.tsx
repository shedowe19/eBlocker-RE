// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { useTransfer } from '../backup/transfer';
import { Confirmation } from '../protection/Confirmation';
import { useLoad } from '../protection/useLoad';
import { ResourcePanel } from '../system/presentation';
import { recordsSchema, sameRecords, validRecord } from './contracts';
import type { DnsRecord } from './contracts';
import { messages } from './messages';

const emptyRecord: DnsRecord = {
    name: '',
    builtin: false,
    hidden: false,
    ipAddress: null,
    ip6Address: null,
    vpnIpAddress: null,
    vpnIp6Address: null,
};
type Edit = { original?: DnsRecord; draft: DnsRecord };
type Change = { kind: 'save'; edit: Edit } | { kind: 'delete'; record: DnsRecord };
export function RecordsPanel({
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
        (signal: AbortSignal) => client.get('/dns/config/records', recordsSchema, signal),
        [client],
    );
    const resource = useLoad(loader, revision + localRevision);
    const transfer = useTransfer();
    const [search, setSearch] = useState('');
    const [edit, setEdit] = useState<Edit>();
    const [change, setChange] = useState<Change>();
    const [invalid, setInvalid] = useState(false);
    const [saved, setSaved] = useState(false);
    const refresh = () => setLocalRevision((value) => value + 1);
    useEffect(() => {
        setEdit(undefined);
        setChange(undefined);
        setInvalid(false);
    }, [resource.data]);
    const disabled = transfer.busy || resource.loading || !!resource.error || !!change;
    function apply() {
        if (!change || !resource.data) return;
        const selected = change;
        const baseline = resource.data;
        setChange(undefined);
        setSaved(false);
        void transfer.run(
            async (signal) => {
                const current = await loader(signal);
                if (!sameRecords(current, baseline)) throw new ApiError('conflict');
                let value = current.filter((record) => !record.builtin);
                if (selected.kind === 'delete')
                    value = value.filter((record) => record.name !== selected.record.name);
                else {
                    value = value.filter((record) => record.name !== selected.edit.original?.name);
                    // The edited record retains its VPN fields, as do all unrelated records.
                    value.push(selected.edit.draft);
                }
                await client.patch(
                    '/dns/config/records',
                    { expected: current, value },
                    recordsSchema,
                    signal,
                );
                const observed = await loader(signal);
                if (
                    !sameRecords(
                        observed.filter((record) => !record.builtin),
                        value,
                    )
                )
                    throw new ApiError('conflict');
            },
            () => {
                setSaved(true);
                refresh();
            },
            refresh,
        );
    }
    return (
        <ResourcePanel title={t.records} resource={resource} locale={locale} retry={refresh}>
            {(data) => {
                const shown = data.filter((record) =>
                    record.name
                        .toLocaleLowerCase(locale)
                        .includes(search.toLocaleLowerCase(locale)),
                );
                return (
                    <div>
                        <p>{t.recordInfo}</p>
                        <label>
                            {t.search}
                            <input
                                type="search"
                                value={search}
                                onChange={(event) => setSearch(event.target.value)}
                            />
                        </label>
                        <button
                            className="button"
                            disabled={disabled}
                            onClick={() => {
                                setEdit({ draft: { ...emptyRecord } });
                                setInvalid(false);
                                setSaved(false);
                            }}
                        >
                            {t.add}
                        </button>
                        <div className="dns-table-scroll">
                            <table>
                                <caption className="sr-only">{t.records}</caption>
                                <thead>
                                    <tr>
                                        <th>{t.name}</th>
                                        <th>{t.ip4}</th>
                                        <th>{t.ip6}</th>
                                        <th>{t.vpn}</th>
                                        <th>
                                            <span className="sr-only">{t.edit}</span>
                                        </th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {shown.map((record) => (
                                        <tr key={record.name}>
                                            <th scope="row">{record.name}</th>
                                            <td>{record.ipAddress ?? '—'}</td>
                                            <td>{record.ip6Address ?? '—'}</td>
                                            <td>
                                                {[record.vpnIpAddress, record.vpnIp6Address]
                                                    .filter(Boolean)
                                                    .join(', ') || '—'}
                                            </td>
                                            <td>
                                                {record.builtin ? (
                                                    t.builtin
                                                ) : (
                                                    <div className="dns-actions">
                                                        <button
                                                            className="button secondary"
                                                            disabled={disabled}
                                                            aria-label={`${t.edit}: ${record.name}`}
                                                            onClick={() => {
                                                                setEdit({
                                                                    original: record,
                                                                    draft: { ...record },
                                                                });
                                                                setInvalid(false);
                                                                setSaved(false);
                                                            }}
                                                        >
                                                            {t.edit}
                                                        </button>
                                                        <button
                                                            className="button secondary"
                                                            disabled={disabled}
                                                            aria-label={`${t.remove}: ${record.name}`}
                                                            onClick={() => {
                                                                setChange({
                                                                    kind: 'delete',
                                                                    record,
                                                                });
                                                                setSaved(false);
                                                            }}
                                                        >
                                                            {t.remove}
                                                        </button>
                                                    </div>
                                                )}
                                            </td>
                                        </tr>
                                    ))}
                                </tbody>
                            </table>
                        </div>
                        {!shown.length && <p>{t.noRecords}</p>}
                        {edit && (
                            <form
                                aria-label={t.recordForm}
                                onSubmit={(event) => {
                                    event.preventDefault();
                                    if (!validRecord(edit.draft, data, edit.original)) {
                                        setInvalid(true);
                                        return;
                                    }
                                    setInvalid(false);
                                    setChange({ kind: 'save', edit });
                                }}
                            >
                                <fieldset disabled={disabled}>
                                    <legend>{t.recordForm}</legend>
                                    <label>
                                        {t.name}
                                        <input
                                            maxLength={50}
                                            required
                                            value={edit.draft.name}
                                            onChange={(event) =>
                                                setEdit({
                                                    ...edit,
                                                    draft: {
                                                        ...edit.draft,
                                                        name: event.target.value.trim(),
                                                    },
                                                })
                                            }
                                        />
                                    </label>
                                    <label>
                                        {t.ip4}
                                        <input
                                            value={edit.draft.ipAddress ?? ''}
                                            onChange={(event) =>
                                                setEdit({
                                                    ...edit,
                                                    draft: {
                                                        ...edit.draft,
                                                        ipAddress:
                                                            event.target.value.trim() || null,
                                                    },
                                                })
                                            }
                                        />
                                    </label>
                                    <label>
                                        {t.ip6}
                                        <input
                                            value={edit.draft.ip6Address ?? ''}
                                            onChange={(event) =>
                                                setEdit({
                                                    ...edit,
                                                    draft: {
                                                        ...edit.draft,
                                                        ip6Address:
                                                            event.target.value.trim() || null,
                                                    },
                                                })
                                            }
                                        />
                                    </label>
                                    {invalid && <p role="alert">{t.recordInvalid}</p>}
                                    <div className="dns-actions">
                                        <button className="button">{t.save}</button>
                                        <button
                                            className="button secondary"
                                            type="button"
                                            onClick={() => setEdit(undefined)}
                                        >
                                            {t.cancel}
                                        </button>
                                    </div>
                                </fieldset>
                            </form>
                        )}
                        {change && (
                            <Confirmation
                                title={change.kind === 'delete' ? t.deleteConfirm : t.recordConfirm}
                                description={
                                    change.kind === 'delete' ? t.deleteInfo : t.confirmInfo
                                }
                                cancel={() => setChange(undefined)}
                                cancelLabel={t.cancel}
                            >
                                <p>
                                    <strong>
                                        {change.kind === 'delete'
                                            ? change.record.name
                                            : change.edit.draft.name}
                                    </strong>
                                </p>
                                {change.kind === 'save' && (
                                    <p>
                                        {[change.edit.draft.ipAddress, change.edit.draft.ip6Address]
                                            .filter(Boolean)
                                            .join(', ')}
                                    </p>
                                )}
                                <button className="button danger" onClick={apply}>
                                    {change.kind === 'delete' ? t.deleteApply : t.confirm}
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
                );
            }}
        </ResourcePanel>
    );
}
