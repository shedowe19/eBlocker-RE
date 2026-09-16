// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useRef, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { ApiError } from '../../api/http';
import type { Locale } from '../../i18n';
import {
    profileDeletedSchema,
    profileResponseSchema,
    profilesResponseSchema,
    profileStatusResponseSchema,
    tunnelObserved,
} from './managementContracts';
import type { ProfileSummary, ProfileStatus as Status } from './managementContracts';
import { managementMessages, phases } from './managementMessages';
import { ProfileImport } from './ProfileImport';
import { ProfileStatus } from './ProfileStatus';

const root = '/wireguard/profiles';
type Action = { kind: 'connect' | 'disconnect' | 'remove'; profile: ProfileSummary };
type Notice =
    | 'imported'
    | 'removed'
    | 'connected'
    | 'disconnected'
    | 'unconfirmed'
    | 'readbackFailed'
    | 'confirmationRequired';
export function WireGuardManager({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = managementMessages[locale];
    const [profiles, setProfiles] = useState<ProfileSummary[]>();
    const [detail, setDetail] = useState<{ profile: Status; checkedAt: number }>();
    const [selected, setSelected] = useState<string>();
    const selectedRef = useRef<string | undefined>(undefined);
    const [loading, setLoading] = useState(true);
    const loadingRef = useRef(false);
    const [failed, setFailed] = useState(false);
    const [busy, setBusy] = useState(false);
    const writing = useRef(false);
    const [importing, setImporting] = useState(false);
    const [action, setAction] = useState<Action>();
    const [confirmed, setConfirmed] = useState(false);
    const [notice, setNotice] = useState<Notice>();
    const request = useRef<AbortController | undefined>(undefined);
    const mounted = useRef(true);
    const importOpener = useRef<HTMLButtonElement>(null);
    const confirmation = useRef<HTMLFormElement>(null);
    const operationActions = useRef<HTMLDivElement>(null);
    const managerHeading = useRef<HTMLHeadingElement>(null);
    const hadImport = useRef(false);
    const hadAction = useRef(false);
    useEffect(() => {
        if (!importing && hadImport.current) importOpener.current?.focus();
        hadImport.current = importing;
    }, [importing]);
    useEffect(() => {
        if (action) confirmation.current?.querySelector<HTMLInputElement>('input')?.focus();
        else if (hadAction.current) {
            const button =
                operationActions.current?.querySelector<HTMLButtonElement>(
                    'button:not([disabled])',
                );
            if (button) button.focus();
            else managerHeading.current?.focus();
        }
        hadAction.current = Boolean(action);
    }, [action]);
    function select(id?: string) {
        selectedRef.current = id;
        setSelected(id);
    }
    const load = useCallback(
        async (id?: string) => {
            request.current?.abort();
            const abort = new AbortController();
            request.current = abort;
            setLoading(true);
            loadingRef.current = true;
            setDetail(undefined);
            setNotice(undefined);
            try {
                // The local controller serializes operations, including reads.
                const list = await client.wireGuardControl(
                    root,
                    'GET',
                    undefined,
                    profilesResponseSchema,
                    abort.signal,
                );
                const status = id
                    ? await client.wireGuardControl(
                          `${root}/${id}`,
                          'GET',
                          undefined,
                          profileStatusResponseSchema,
                          abort.signal,
                      )
                    : undefined;
                if (abort.signal.aborted || !mounted.current) return undefined;
                if (status && status.data.profileId !== id) throw new ApiError('invalidResponse');
                setProfiles(list.data.profiles);
                setDetail(status ? { profile: status.data, checkedAt: Date.now() } : undefined);
                setFailed(false);
                return { profiles: list.data.profiles, profile: status?.data };
            } catch {
                if (!abort.signal.aborted && mounted.current) {
                    setFailed(true);
                    setDetail(undefined);
                }
                return undefined;
            } finally {
                if (!abort.signal.aborted && mounted.current) {
                    loadingRef.current = false;
                    setLoading(false);
                }
            }
        },
        [client],
    );
    useEffect(() => {
        mounted.current = true;
        void load(selectedRef.current);
        const timer = setInterval(() => {
            if (
                !writing.current &&
                !loadingRef.current &&
                selectedRef.current &&
                document.visibilityState !== 'hidden'
            )
                void load(selectedRef.current);
        }, 15_000);
        return () => {
            mounted.current = false;
            request.current?.abort();
            clearInterval(timer);
        };
    }, [load]);
    useEffect(() => {
        setConfirmed(false);
    }, [locale]);

    async function mutate(
        kind: 'import' | Action['kind'],
        id: string,
        configuration?: string,
        replace?: boolean,
    ) {
        if (writing.current) return;
        writing.current = true;
        setBusy(true);
        setNotice(undefined);
        request.current?.abort();
        setDetail(undefined);
        let accepted = false;
        try {
            if (kind === 'import') {
                const before = await client.wireGuardControl(
                    root,
                    'GET',
                    undefined,
                    profilesResponseSchema,
                );
                if (before.data.profiles.some((profile) => profile.profileId === id) && !replace)
                    throw new ApiError('conflict');
                const result = await client.wireGuardControl(
                    `${root}/${id}`,
                    'PUT',
                    { schemaVersion: 1, configuration },
                    profileResponseSchema,
                );
                accepted = result.data.profileId === id;
            } else {
                const current = await client.wireGuardControl(
                    `${root}/${id}`,
                    'GET',
                    undefined,
                    profileStatusResponseSchema,
                );
                if (
                    current.data.profileId !== id ||
                    action?.profile.profileId !== id ||
                    JSON.stringify(current.data.plan) !== JSON.stringify(action.profile.plan)
                )
                    throw new ApiError('conflict');
                const result =
                    kind === 'remove'
                        ? await client.wireGuardControl(
                              `${root}/${id}`,
                              'DELETE',
                              undefined,
                              profileDeletedSchema,
                          )
                        : await client.wireGuardControl(
                              `${root}/${id}/${kind}`,
                              'POST',
                              { schemaVersion: 1 },
                              profileStatusResponseSchema,
                          );
                accepted = result.data.profileId === id;
            }
        } catch {
            /* Every outcome needs a fresh read: timeout may follow a successful mutation. */
        }
        const next = kind === 'remove' ? undefined : id;
        const actual = mounted.current ? await load(next) : undefined;
        if (mounted.current) {
            select(next);
            setAction(undefined);
            setConfirmed(false);
            let result: Notice = actual ? 'unconfirmed' : 'readbackFailed';
            if (accepted && actual) {
                if (
                    kind === 'import' &&
                    actual.profiles.some((profile) => profile.profileId === id)
                )
                    result = 'imported';
                if (
                    kind === 'remove' &&
                    !actual.profiles.some((profile) => profile.profileId === id)
                )
                    result = 'removed';
                if (kind === 'connect' && tunnelObserved(actual.profile?.runtime ?? null))
                    result = 'connected';
                if (
                    kind === 'disconnect' &&
                    actual.profile?.runtime?.phase === 'disconnected' &&
                    !actual.profile.runtime.observation.exists
                )
                    result = 'disconnected';
            }
            setNotice(result);
            setBusy(false);
        }
        writing.current = false;
    }
    const current = detail?.profile;
    const knownInactive =
        current &&
        (current.runtime === null ||
            (['disconnected', 'failed', 'cancelled'].includes(current.runtime.phase) &&
                !current.runtime.observation.exists));
    const unsupportedDns = Boolean(current?.plan.dns.length);
    const unsupportedHost = Boolean(
        current?.plan.peers.some(
            (peer) =>
                peer.endpoint &&
                !/^\[[0-9a-f:.]+\]:\d+$/i.test(peer.endpoint) &&
                !/^\d+\.\d+\.\d+\.\d+:\d+$/.test(peer.endpoint),
        ),
    );
    return (
        <section className="wireguard-panel" aria-label={t.profiles}>
            <div className="wireguard-management-heading">
                <h2 ref={managerHeading} tabIndex={-1}>
                    {t.profiles}
                </h2>
                <div className="wireguard-actions">
                    <button
                        className="button secondary"
                        disabled={busy || loading}
                        onClick={() => void load(selectedRef.current)}
                    >
                        {t.refresh}
                    </button>
                    <button
                        className="button primary"
                        disabled={busy || importing}
                        ref={importOpener}
                        onClick={() => {
                            setImporting(true);
                            setAction(undefined);
                            setNotice(undefined);
                        }}
                    >
                        {t.import}
                    </button>
                </div>
            </div>
            <p className="wireguard-help">{t.limitsHelp}</p>
            {failed && (
                <p role="alert" className="error-message">
                    {t.loadFailed}
                </p>
            )}
            {loading && !busy && <p role="status">{t.loading}</p>}
            {busy && <p role="status">{t.busy}</p>}
            {notice && (
                <p
                    className={
                        ['unconfirmed', 'readbackFailed', 'confirmationRequired'].includes(notice)
                            ? 'error-message'
                            : 'wireguard-success'
                    }
                    role={
                        ['unconfirmed', 'readbackFailed', 'confirmationRequired'].includes(notice)
                            ? 'alert'
                            : 'status'
                    }
                >
                    {t[notice]}
                </p>
            )}
            {importing && (
                <ProfileImport
                    locale={locale}
                    profiles={profiles ?? []}
                    busy={busy}
                    onImport={(id, configuration, replace) =>
                        mutate('import', id, configuration, replace)
                    }
                    onClose={() => setImporting(false)}
                />
            )}
            {profiles &&
                (profiles.length ? (
                    <ul className="wireguard-profile-list">
                        {profiles.map((profile) => (
                            <li key={profile.profileId}>
                                <div>
                                    <strong>{profile.profileId}</strong>
                                    <span>
                                        {t.stored}:{' '}
                                        {phases[locale][profile.phase] ?? t.phaseUnknown}
                                    </span>
                                </div>
                                <button
                                    className="button secondary"
                                    disabled={busy || loading}
                                    aria-pressed={selected === profile.profileId}
                                    onClick={() => {
                                        select(profile.profileId);
                                        setNotice(undefined);
                                        setAction(undefined);
                                        void load(profile.profileId);
                                    }}
                                >
                                    {t.select}
                                </button>
                            </li>
                        ))}
                    </ul>
                ) : (
                    <p>{t.empty}</p>
                ))}
            {detail && !busy && (
                <>
                    <ProfileStatus
                        profile={detail.profile}
                        locale={locale}
                        checkedAt={detail.checkedAt}
                    />
                    {unsupportedDns && <p className="wireguard-help">{t.dnsUnsupported}</p>}
                    {unsupportedHost && <p className="wireguard-help">{t.hostUnsupported}</p>}
                    <div className="wireguard-actions" ref={operationActions}>
                        <button
                            className="button primary"
                            disabled={
                                !knownInactive ||
                                unsupportedDns ||
                                unsupportedHost ||
                                Boolean(action)
                            }
                            onClick={() => {
                                setAction({ kind: 'connect', profile: detail.profile });
                                setConfirmed(false);
                                setNotice(undefined);
                            }}
                        >
                            {t.connect}
                        </button>
                        <button
                            className="button secondary"
                            disabled={current?.runtime === null || Boolean(action)}
                            onClick={() => {
                                setAction({ kind: 'disconnect', profile: detail.profile });
                                setConfirmed(false);
                                setNotice(undefined);
                            }}
                        >
                            {t.disconnect}
                        </button>
                        <button
                            className="button secondary"
                            disabled={!knownInactive || Boolean(action)}
                            onClick={() => {
                                setAction({ kind: 'remove', profile: detail.profile });
                                setConfirmed(false);
                                setNotice(undefined);
                            }}
                        >
                            {t.remove}
                        </button>
                    </div>
                </>
            )}
            {action && (
                <form
                    className="wireguard-confirmation"
                    ref={confirmation}
                    aria-label={`${t[action.kind]}: ${action.profile.profileId}`}
                    onSubmit={(event) => {
                        event.preventDefault();
                        if (!confirmed) {
                            setNotice('confirmationRequired');
                            return;
                        }
                        void mutate(action.kind, action.profile.profileId);
                    }}
                >
                    <h3>
                        {t[action.kind]}: {action.profile.profileId}
                    </h3>
                    <p>{t[`${action.kind}Help`]}</p>
                    <label className="wireguard-check">
                        <input
                            type="checkbox"
                            disabled={busy}
                            checked={confirmed}
                            onChange={(event) => setConfirmed(event.target.checked)}
                        />
                        {t.confirm}
                    </label>
                    <div className="wireguard-actions">
                        <button className="button primary" disabled={busy} type="submit">
                            {t[action.kind]}
                        </button>
                        <button
                            className="button secondary"
                            disabled={busy}
                            type="button"
                            onClick={() => {
                                setAction(undefined);
                                setNotice(undefined);
                            }}
                        >
                            {t.cancel}
                        </button>
                    </div>
                </form>
            )}
        </section>
    );
}
