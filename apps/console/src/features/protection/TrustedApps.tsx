// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useId, useRef, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { ApiError, errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { AddressList } from '../system/presentation';
import { trustedAppSchema } from './contracts';
import type { TrustedApp } from './contracts';
import { localized } from './FilterLists';
import { Confirmation } from './Confirmation';
import { messages } from './messages';

export function TrustedApps({
    apps,
    client,
    locale,
    stale,
    onChanged,
}: {
    apps: TrustedApp[];
    client: ConsoleClient;
    locale: Locale;
    stale: boolean;
    onChanged: () => void;
}) {
    const t = messages[locale];
    const searchId = useId();
    const [query, setQuery] = useState('');
    const [pending, setPending] = useState<TrustedApp>();
    const [saving, setSaving] = useState(false);
    const [failure, setFailure] = useState<ErrorCode>();
    const [unconfirmed, setUnconfirmed] = useState(false);
    const [success, setSuccess] = useState(false);
    const [overrides, setOverrides] = useState<Record<number, TrustedApp>>({});
    const generation = useRef(0);
    useEffect(() => {
        ++generation.current;
        return () => {
            ++generation.current;
        };
    }, [client]);
    useEffect(() => {
        setOverrides({});
    }, [apps]);
    const visible = apps.filter((app) => !app.hidden).map((app) => overrides[app.id] ?? app);
    const filtered = visible.filter((app) =>
        `${app.name} ${localized(app.description, locale, '')} ${app.whitelistedDomainsIps.join(' ')}`
            .toLocaleLowerCase(locale)
            .includes(query.toLocaleLowerCase(locale).trim()),
    );

    async function save() {
        if (!pending || saving || stale) return;
        const currentGeneration = generation.current;
        const target = !pending.enabled;
        let writeStarted = false;
        setSaving(true);
        setFailure(undefined);
        setUnconfirmed(false);
        setSuccess(false);
        try {
            // Do not update a module deleted or hidden since this view was loaded.
            const current = await client.get(`/trustedapps/id/${pending.id}`, trustedAppSchema);
            if (currentGeneration !== generation.current) return;
            if (current.id !== pending.id || current.hidden) throw new ApiError('conflict');
            if (current.enabled !== target) {
                writeStarted = true;
                // AppWhitelistModuleControllerImpl expects Map<String,String>, and returns void.
                await client.putVoid('/trustedapps/enable', {
                    id: String(pending.id),
                    setEnabled: String(target),
                });
            }
            if (currentGeneration !== generation.current) return;
            const confirmed = await client.get(`/trustedapps/id/${pending.id}`, trustedAppSchema);
            if (currentGeneration !== generation.current) return;
            // The legacy service silently accepts missing IDs, so a 2xx alone is insufficient.
            if (confirmed.id !== pending.id || confirmed.enabled !== target)
                throw new ApiError('conflict');
            setOverrides((previous) => ({ ...previous, [confirmed.id]: confirmed }));
            setPending(undefined);
            setSuccess(true);
            onChanged();
        } catch (error) {
            if (currentGeneration !== generation.current) return;
            setFailure(errorCode(error));
            setUnconfirmed(writeStarted);
            if (writeStarted) onChanged();
        } finally {
            if (currentGeneration === generation.current) setSaving(false);
        }
    }

    return (
        <>
            <p>{t.appsNote}</p>
            {failure && (
                <div className="error-banner" role="alert">
                    <p>{unconfirmed ? t.unconfirmed : errors[locale][failure]}</p>
                </div>
            )}
            {success && <p role="status">{t.saved}</p>}
            {pending && (
                <Confirmation
                    title={`${t.confirmTitle}: ${pending.name}`}
                    description={pending.enabled ? t.disableExplanation : t.enableExplanation}
                    cancel={() => setPending(undefined)}
                    cancelLabel={t.cancel}
                    disabled={saving}
                >
                    <button
                        className="button primary"
                        onClick={() => void save()}
                        disabled={saving || stale}
                    >
                        {saving ? t.saving : t.confirm}
                    </button>
                </Confirmation>
            )}
            {!visible.length ? (
                <p>{t.emptyApps}</p>
            ) : (
                <>
                    <label className="protection-search" htmlFor={searchId}>
                        {t.search}
                        <input
                            id={searchId}
                            type="search"
                            value={query}
                            onChange={(event) => setQuery(event.target.value)}
                        />
                    </label>
                    {!filtered.length && <p role="status">{t.noResults}</p>}
                    <ul className="protection-apps">
                        {filtered.map((app) => (
                            <li key={app.id} className="protection-app">
                                <div className="protection-app-heading">
                                    <h3>{app.name}</h3>
                                    <span>{app.enabled ? t.enabled : t.disabled}</span>
                                </div>
                                <p>{localized(app.description, locale, '')}</p>
                                <details>
                                    <summary>
                                        {t.addresses} ({app.whitelistedDomainsIps.length})
                                    </summary>
                                    <AddressList
                                        addresses={app.whitelistedDomainsIps}
                                        locale={locale}
                                    />
                                </details>
                                <button
                                    className="button secondary"
                                    aria-label={`${app.enabled ? t.disable : t.enable}: ${app.name}`}
                                    disabled={saving || stale || !!pending}
                                    onClick={() => {
                                        setPending(app);
                                        setFailure(undefined);
                                        setSuccess(false);
                                    }}
                                >
                                    {app.enabled ? t.disable : t.enable}
                                </button>
                            </li>
                        ))}
                    </ul>
                </>
            )}
        </>
    );
}
