// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { useTransfer } from '../backup/transfer';
import { Confirmation } from '../protection/Confirmation';
import { useLoad } from '../protection/useLoad';
import { ipv6Schema } from './contracts';
import { ResourcePanel } from './presentation';
import { networkMessages } from './networkMessages';
import './network.css';

export function Ipv6Editor({
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
        (signal: AbortSignal) => client.get('/network/ip6', ipv6Schema, signal),
        [client],
    );
    const resource = useLoad(loader, revision);
    const transfer = useTransfer();
    const [draft, setDraft] = useState<{
        routerAdvertisementsEnabled: boolean;
        privacyExtensionsEnabled: boolean;
    }>();
    const [confirm, setConfirm] = useState(false);
    const [saved, setSaved] = useState(false);
    const refresh = () => setRevision((value) => value + 1);
    useEffect(() => {
        if (resource.data)
            setDraft({
                routerAdvertisementsEnabled: resource.data.routerAdvertisementsEnabled,
                privacyExtensionsEnabled: resource.data.privacyExtensionsEnabled,
            });
    }, [resource.data]);
    function apply() {
        if (!resource.data || !draft) return;
        const baseline = resource.data;
        const chosen = draft;
        setConfirm(false);
        setSaved(false);
        void transfer.run(
            async (signal) => {
                const fresh = await loader(signal);
                if (
                    fresh.routerAdvertisementsEnabled !== baseline.routerAdvertisementsEnabled ||
                    fresh.privacyExtensionsEnabled !== baseline.privacyExtensionsEnabled
                )
                    throw new ApiError('conflict');
                await client.patch(
                    '/network/ip6',
                    { expected: fresh, value: chosen },
                    ipv6Schema,
                    signal,
                );
                const observed = await loader(signal);
                if (
                    observed.routerAdvertisementsEnabled !== chosen.routerAdvertisementsEnabled ||
                    observed.privacyExtensionsEnabled !== chosen.privacyExtensionsEnabled
                )
                    throw new ApiError('conflict');
            },
            () => {
                setSaved(true);
                refresh();
                updated();
            },
            () => {
                refresh();
                updated();
            },
        );
    }
    return (
        <ResourcePanel title={t.edit6} resource={resource} locale={locale} retry={refresh}>
            {(data) => (
                <div className="network-editor">
                    <p>{t.ip6Help}</p>
                    {data.routerAdvertisementsEnabled && data.globalAddresses?.length === 0 && (
                        <p role="status">{t.ip6Missing}</p>
                    )}
                    {!data.routerAdvertisementsEnabled && !!data.globalAddresses?.length && (
                        <p role="alert">{t.ip6Leak}</p>
                    )}
                    {draft && (
                        <form
                            onSubmit={(event) => {
                                event.preventDefault();
                                setConfirm(true);
                            }}
                        >
                            <fieldset
                                disabled={
                                    transfer.busy || resource.loading || !!resource.error || confirm
                                }
                            >
                                <label className="network-check">
                                    <input
                                        type="checkbox"
                                        checked={draft.routerAdvertisementsEnabled}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                routerAdvertisementsEnabled: event.target.checked,
                                            })
                                        }
                                    />
                                    {t.ra}
                                </label>
                                <label className="network-check">
                                    <input
                                        type="checkbox"
                                        checked={draft.privacyExtensionsEnabled}
                                        onChange={(event) =>
                                            setDraft({
                                                ...draft,
                                                privacyExtensionsEnabled: event.target.checked,
                                            })
                                        }
                                    />
                                    {t.privacy}
                                </label>
                                <button
                                    className="button"
                                    disabled={
                                        draft.routerAdvertisementsEnabled ===
                                            data.routerAdvertisementsEnabled &&
                                        draft.privacyExtensionsEnabled ===
                                            data.privacyExtensionsEnabled
                                    }
                                >
                                    {t.save}
                                </button>
                            </fieldset>
                        </form>
                    )}
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
                    {transfer.error && (
                        <p role="alert">
                            {transfer.error === 'conflict'
                                ? t.stale
                                : errors[locale][transfer.error]}{' '}
                            {t.uncertain}
                        </p>
                    )}
                    {saved && <p role="status">{t.saved}</p>}
                    <button className="button secondary" disabled={transfer.busy} onClick={close}>
                        {t.close}
                    </button>
                </div>
            )}
        </ResourcePanel>
    );
}
