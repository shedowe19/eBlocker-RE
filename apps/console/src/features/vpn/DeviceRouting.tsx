// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Device } from '../../api/contracts';
import { ApiError, errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { Confirmation } from '../protection/Confirmation';
import { useLoad } from '../protection/useLoad';
import { ResourcePanel } from '../system/presentation';
import { legacyDevice, profileSchema, torConfigSchema, vpnStatusSchema } from './contracts';
import type { Profile } from './contracts';
import { messages } from './messages';
import { profileName } from './ProfileCard';

type Action =
    { kind: 'vpn'; profileId: number; enabled: boolean } | { kind: 'tor'; enabled: boolean };
export function DeviceRouting({
    device,
    profiles,
    client,
    locale,
    revision,
    stale,
    refresh,
    onBusy,
}: {
    device: Device;
    profiles: Profile[];
    client: ConsoleClient;
    locale: Locale;
    revision: number;
    stale: boolean;
    refresh: () => void;
    onBusy: (value: boolean) => void;
}) {
    const t = messages[locale];
    const profileId = useId();
    const path = `/tor/config/${encodeURIComponent(device.id)}`;
    const loader = useCallback(
        async (signal: AbortSignal) => {
            const [tor, vpn] = await Promise.all([
                client.get(path, torConfigSchema, signal),
                client.getVpnDeviceStatus(device.id, vpnStatusSchema, signal),
            ]);
            return { tor, vpn };
        },
        [client, device.id, path],
    );
    const resource = useLoad(loader, revision);
    const [selection, setSelection] = useState('');
    const [pending, setPending] = useState<Action>();
    const [busy, setBusy] = useState(false);
    const [failure, setFailure] = useState<ErrorCode>();
    const [unconfirmed, setUnconfirmed] = useState(false);
    const [success, setSuccess] = useState(false);
    const generation = useRef(0);
    const activeWrite = useRef(false);
    const operationRead = useRef<AbortController | undefined>(undefined);
    useEffect(() => {
        ++generation.current;
        return () => {
            ++generation.current;
            operationRead.current?.abort();
        };
    }, [client, device.id]);
    const available = profiles.filter(
        (profile) => profile.enabled && !profile.temporary && !profile.deleted,
    );
    const blocked = busy || stale || resource.loading || !!resource.error;

    async function submit() {
        if (!pending || blocked || activeWrite.current) return;
        activeWrite.current = true;
        const current = generation.current;
        const action = pending;
        const readController = new AbortController();
        operationRead.current = readController;
        setBusy(true);
        onBusy(true);
        setFailure(undefined);
        setSuccess(false);
        let writeStarted = false;
        try {
            // Re-read both protocols before applying a change to avoid disabling a replacement route.
            const before = await loader(readController.signal);
            if (current !== generation.current) return;
            if (
                action.kind === 'vpn' &&
                !action.enabled &&
                (before.vpn?.profileId !== action.profileId || before.tor.sessionUseTor)
            )
                throw new ApiError('conflict');
            if (
                action.kind === 'tor' &&
                !action.enabled &&
                (!before.tor.sessionUseTor || before.vpn !== null)
            )
                throw new ApiError('conflict');
            if (action.kind === 'vpn' && action.enabled) {
                const selected = await client.get(
                    `/vpn/profile/${action.profileId}`,
                    profileSchema,
                    readController.signal,
                );
                if (current !== generation.current) return;
                if (
                    selected.id !== action.profileId ||
                    !selected.enabled ||
                    selected.temporary ||
                    selected.deleted
                )
                    throw new ApiError('conflict');
            }
            writeStarted = true;
            if (action.kind === 'vpn') {
                await client.putVoid(
                    `/vpn/profile/${action.profileId}/status/${encodeURIComponent(device.id)}`,
                    action.enabled,
                );
            } else {
                await client.put(path, { sessionUseTor: action.enabled }, torConfigSchema);
            }
            if (current !== generation.current) return;
            const after = await loader(readController.signal);
            if (current !== generation.current) return;
            const confirmed =
                action.kind === 'tor'
                    ? after.tor.sessionUseTor === action.enabled && after.vpn === null
                    : action.enabled
                      ? after.vpn?.profileId === action.profileId &&
                        after.vpn.devices.includes(device.id) &&
                        !after.tor.sessionUseTor
                      : after.vpn === null && !after.tor.sessionUseTor;
            if (!confirmed) throw new ApiError('conflict');
            setPending(undefined);
            setSuccess(true);
            refresh();
        } catch (error) {
            if (current !== generation.current) return;
            setFailure(errorCode(error));
            setUnconfirmed(writeStarted);
            if (writeStarted) {
                setPending(undefined);
                refresh();
            }
        } finally {
            if (operationRead.current === readController) operationRead.current = undefined;
            activeWrite.current = false;
            if (current === generation.current) {
                setBusy(false);
                onBusy(false);
            }
        }
    }

    return (
        <ResourcePanel
            title={`${t.currentRoute}: ${device.name?.trim() || device.id}`}
            resource={resource}
            locale={locale}
            retry={refresh}
        >
            {({ tor, vpn }) => (
                <>
                    <p>
                        {tor.sessionUseTor && vpn
                            ? t.inconsistent
                            : tor.sessionUseTor
                              ? t.tor
                              : vpn
                                ? `${t.vpn}: ${profiles.find((profile) => profile.id === vpn.profileId)?.name || `#${vpn.profileId}`}`
                                : t.direct}
                    </p>
                    {vpn && (
                        <p>
                            {t.state}: {vpn.up ? t.connected : vpn.active ? t.starting : t.stopped}
                        </p>
                    )}
                    <p className="feature-note">{t.noProof}</p>
                    {failure && (
                        <p className="error-message" role="alert">
                            {unconfirmed ? t.unconfirmed : errors[locale][failure]}
                        </p>
                    )}
                    {success && <p role="status">{t.confirmed}</p>}
                    {pending && (
                        <Confirmation
                            title={`${t.confirmTitle}: ${device.name?.trim() || device.id}`}
                            description={
                                !pending.enabled
                                    ? t.directWarning
                                    : pending.kind === 'tor'
                                      ? t.torWarning
                                      : t.connectWarning
                            }
                            cancel={() => setPending(undefined)}
                            cancelLabel={t.cancel}
                            disabled={busy}
                        >
                            <button
                                className="button primary"
                                disabled={blocked}
                                onClick={() => void submit()}
                            >
                                {busy ? t.saving : t.confirm}
                            </button>
                        </Confirmation>
                    )}
                    <div className="vpn-routing-actions">
                        {available.length ? (
                            <>
                                <label htmlFor={profileId}>
                                    {t.chooseProfile}
                                    <select
                                        id={profileId}
                                        disabled={blocked || !!pending}
                                        value={selection}
                                        onChange={(event) => setSelection(event.target.value)}
                                    >
                                        <option value="">{t.choose}</option>
                                        {available.map((profile) => (
                                            <option value={profile.id} key={profile.id}>
                                                {profileName(profile, locale)}
                                            </option>
                                        ))}
                                    </select>
                                </label>
                                <button
                                    className="button secondary"
                                    disabled={
                                        blocked ||
                                        !!pending ||
                                        !selection ||
                                        vpn?.profileId === Number(selection)
                                    }
                                    onClick={() => {
                                        setFailure(undefined);
                                        setSuccess(false);
                                        setPending({
                                            kind: 'vpn',
                                            profileId: Number(selection),
                                            enabled: true,
                                        });
                                    }}
                                >
                                    {t.connect}
                                </button>
                            </>
                        ) : (
                            <p>{t.noUsableProfiles}</p>
                        )}
                        {vpn && (
                            <button
                                className="button secondary"
                                disabled={blocked || !!pending || tor.sessionUseTor}
                                onClick={() => {
                                    setFailure(undefined);
                                    setSuccess(false);
                                    setPending({
                                        kind: 'vpn',
                                        profileId: vpn.profileId,
                                        enabled: false,
                                    });
                                }}
                            >
                                {t.disconnect}
                            </button>
                        )}
                        <button
                            className="button secondary"
                            disabled={blocked || !!pending || (tor.sessionUseTor && vpn !== null)}
                            onClick={() => {
                                setFailure(undefined);
                                setSuccess(false);
                                setPending({ kind: 'tor', enabled: !tor.sessionUseTor });
                            }}
                        >
                            {tor.sessionUseTor ? t.disableTor : t.enableTor}
                        </button>
                    </div>
                    <a href={legacyDevice(device.id)}>{t.legacyDevice}</a>
                </>
            )}
        </ResourcePanel>
    );
}
