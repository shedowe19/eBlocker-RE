// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { ApiError, errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { Confirmation } from '../protection/Confirmation';
import { PageHeading, ResourcePanel, Field } from '../system/presentation';
import { useResource } from '../system/resource';
import './updates.css';

export const updateSchema = z.object({
    updating: z.boolean(),
    downloading: z.boolean(),
    checking: z.boolean(),
    recovering: z.boolean(),
    disabled: z.boolean(),
    updatesAvailable: z.boolean(),
    automaticUpdatesActivated: z.boolean(),
    automaticUpdatesAllowed: z.boolean(),
    lastUpdateAttemptFailed: z.boolean(),
    projectVersion: z.string().nullable().optional(),
    listsPacketVersion: z.string().nullable().optional(),
    lastAutomaticUpdate: z.string().nullable().optional(),
    nextAutomaticUpdate: z.string().nullable().optional(),
    updateProgress: z.array(z.string()).nullable().optional(),
    updateablePackages: z.array(z.string()).nullable().optional(),
    beginHour: z.number().int().min(0).max(23).optional(),
    beginMin: z.number().int().min(0).max(59).optional(),
    endHour: z.number().int().min(0).max(23).optional(),
    endMin: z.number().int().min(0).max(59).optional(),
});
type Update = z.infer<typeof updateSchema>;
type Action = 'install' | 'recover' | 'automatic' | 'schedule';
const texts = {
    de: {
        title: 'Updates',
        intro: 'Software, Filterlisten und automatische Aktualisierungen verwalten.',
        status: 'Update-Status',
        version: 'Softwareversion',
        filters: 'Filterlistenversion',
        available: 'Updates verfügbar',
        yes: 'Ja',
        no: 'Nein',
        unknown: 'Nicht gemeldet',
        idle: 'Bereit',
        active: 'Ein Vorgang läuft',
        disabled: 'Updates sind derzeit deaktiviert.',
        failed: 'Der letzte Updateversuch ist fehlgeschlagen.',
        check: 'Nach Updates suchen',
        install: 'Updates installieren',
        recover: 'Update-Wiederherstellung anfordern',
        automatic: 'Automatische Updates',
        enable: 'Automatische Updates einschalten',
        disable: 'Automatische Updates ausschalten',
        restricted: 'Automatische Updates sind laut Server für dieses Gerät nicht verfügbar.',
        next: 'Nächstes automatisches Update',
        last: 'Letztes automatisches Update',
        window: 'Zeitfenster',
        from: 'Von',
        until: 'Bis',
        save: 'Zeitfenster speichern',
        invalidTime: 'Wähle zwei gültige und unterschiedliche Uhrzeiten.',
        timezone: 'Das Zeitfenster verwendet die am eBlocker eingestellte Zeitzone.',
        confirm: 'Änderung bestätigen',
        confirmInstall:
            'Das Update kann die Verbindung unterbrechen. Sichere wichtige Einstellungen vor der Installation.',
        confirmRecover:
            'Die bestehende Update-Wiederherstellung wird angefordert. Dabei können Dienste neu gestartet werden.',
        confirmAutomatic: 'Die Einstellung für automatische Updates wird geändert.',
        confirmSchedule: 'Das Zeitfenster für automatische Updates wird geändert.',
        proceed: 'Bestätigen',
        cancel: 'Abbrechen',
        busy: 'Anfrage läuft …',
        sent: 'Anfrage bestätigt. Der aktuelle Serverstatus wird neu geladen.',
        uncertain:
            'Die Änderung konnte nicht bestätigt werden. Prüfe den aktuellen Status, bevor du erneut eine Änderung anforderst.',
        progress: 'Gemeldeter Fortschritt',
        packages: 'Gemeldete Pakete',
        full: 'Vollständige Updateverwaltung',
        backup: 'Sicherung öffnen',
    },
    en: {
        title: 'Updates',
        intro: 'Manage software, filter lists and automatic updates.',
        status: 'Update status',
        version: 'Software version',
        filters: 'Filter list version',
        available: 'Updates available',
        yes: 'Yes',
        no: 'No',
        unknown: 'Not reported',
        idle: 'Ready',
        active: 'An operation is running',
        disabled: 'Updates are currently disabled.',
        failed: 'The last update attempt failed.',
        check: 'Check for updates',
        install: 'Install updates',
        recover: 'Request update recovery',
        automatic: 'Automatic updates',
        enable: 'Enable automatic updates',
        disable: 'Disable automatic updates',
        restricted: 'The server reports that automatic updates are unavailable for this device.',
        next: 'Next automatic update',
        last: 'Last automatic update',
        window: 'Time window',
        from: 'From',
        until: 'Until',
        save: 'Save time window',
        invalidTime: 'Choose two valid, different times.',
        timezone: 'The time window uses the timezone configured on your eBlocker.',
        confirm: 'Confirm change',
        confirmInstall:
            'Updating may interrupt your connection. Back up important settings before installation.',
        confirmRecover: 'This requests the existing update recovery process. Services may restart.',
        confirmAutomatic: 'The automatic update setting will change.',
        confirmSchedule: 'The automatic update time window will change.',
        proceed: 'Confirm',
        cancel: 'Cancel',
        busy: 'Sending request …',
        sent: 'Request acknowledged. Reloading the actual server status.',
        uncertain:
            'The change could not be confirmed. Check the current status before requesting another change.',
        progress: 'Reported progress',
        packages: 'Reported packages',
        full: 'Full update administration',
        backup: 'Open backup',
    },
};
const active = (data: Update) =>
    data.updating || data.checking || data.downloading || data.recovering;
const time = (hour?: number, minute?: number) =>
    hour === undefined || minute === undefined
        ? ''
        : `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`;

export function UpdatesPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = texts[locale];
    const [revision, setRevision] = useState(0);
    const resource = useResource(client, '/updates/status', updateSchema, revision);
    const [pending, setPending] = useState<Action>();
    const [busy, setBusy] = useState(false);
    const [failure, setFailure] = useState<ErrorCode | 'time'>();
    const [sent, setSent] = useState(false);
    const [start, setStart] = useState('');
    const [end, setEnd] = useState('');
    const request = useRef<AbortController | undefined>(undefined);
    const mounted = useRef(true);
    const refresh = () => setRevision((value) => value + 1);
    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
            request.current?.abort();
        };
    }, []);
    useEffect(() => {
        setPending(undefined);
    }, [locale]);
    useEffect(() => {
        if (resource.data && !pending) {
            setStart(time(resource.data.beginHour, resource.data.beginMin));
            setEnd(time(resource.data.endHour, resource.data.endMin));
        }
    }, [resource.data]);
    useEffect(() => {
        if (!resource.data || !active(resource.data) || busy || resource.error) return;
        const timer = setInterval(refresh, 5000);
        return () => clearInterval(timer);
    }, [resource.data, resource.error, busy]);
    function propose(action: Action) {
        setFailure(undefined);
        setSent(false);
        if (
            action === 'schedule' &&
            (!/^\d{2}:\d{2}$/.test(start) || !/^\d{2}:\d{2}$/.test(end) || start === end)
        ) {
            setFailure('time');
            return;
        }
        setPending(action);
    }
    async function perform(action?: Action) {
        if (request.current || busy) return;
        const abort = new AbortController();
        request.current = abort;
        setBusy(true);
        setFailure(undefined);
        setSent(false);
        try {
            const current = await client.get('/updates/status', updateSchema, abort.signal);
            if (current.disabled || active(current)) throw new ApiError('conflict');
            if (action === 'automatic' || action === 'schedule') {
                if (!current.automaticUpdatesAllowed) throw new ApiError('forbidden');
            }
            if (!action) await client.get('/updates/check', updateSchema, abort.signal);
            else if (action === 'install' || action === 'recover') {
                if (action === 'install' && !current.updatesAvailable)
                    throw new ApiError('conflict');
                if (action === 'recover' && !current.lastUpdateAttemptFailed)
                    throw new ApiError('conflict');
                await client.post(
                    '/updates/status',
                    action === 'install' ? { updating: true } : { recovering: true },
                    updateSchema,
                    abort.signal,
                );
            } else if (action === 'automatic') {
                if (current.automaticUpdatesActivated !== resource.data?.automaticUpdatesActivated)
                    throw new ApiError('conflict');
                await client.post(
                    '/updates/automaticUpdatesStatus',
                    { automaticUpdatesActivated: !current.automaticUpdatesActivated },
                    updateSchema,
                    abort.signal,
                );
            } else {
                if (
                    ['beginHour', 'beginMin', 'endHour', 'endMin'].some(
                        (key) =>
                            current[key as keyof Update] !== resource.data?.[key as keyof Update],
                    )
                ) {
                    throw new ApiError('conflict');
                }
                const [beginHour, beginMin] = start.split(':').map(Number);
                const [endHour, endMin] = end.split(':').map(Number);
                await client.post(
                    '/updates/automaticUpdatesConfig',
                    { beginHour, beginMin, endHour, endMin },
                    updateSchema,
                    abort.signal,
                );
            }
            if (mounted.current) {
                setPending(undefined);
                setSent(true);
            }
        } catch (error) {
            if (mounted.current) {
                setPending(undefined);
                setFailure(errorCode(error));
            }
        } finally {
            if (mounted.current) {
                request.current = undefined;
                setBusy(false);
                refresh();
            }
        }
    }
    const locked =
        busy ||
        resource.loading ||
        Boolean(resource.error) ||
        !resource.data ||
        active(resource.data) ||
        resource.data.disabled;
    const descriptions = {
        install: t.confirmInstall,
        recover: t.confirmRecover,
        automatic: t.confirmAutomatic,
        schedule: t.confirmSchedule,
    };
    return (
        <section className="updates-page">
            <PageHeading
                title={t.title}
                description={t.intro}
                loading={resource.loading || busy}
                refresh={refresh}
                locale={locale}
            />
            <div className="updates-links">
                <a href="/settings/#!/home/update">{t.full}</a>
                <a href="/settings/#!/system/backup">{t.backup}</a>
            </div>
            {failure && (
                <p role="alert" className="error-banner">
                    {failure === 'time'
                        ? t.invalidTime
                        : `${t.uncertain} ${errors[locale][failure]}`}
                </p>
            )}
            {sent && <p role="status">{t.sent}</p>}
            <ResourcePanel title={t.status} resource={resource} locale={locale} retry={refresh}>
                {(data) => (
                    <>
                        <p>{active(data) ? t.active : t.idle}</p>
                        {data.disabled && <p>{t.disabled}</p>}
                        {data.lastUpdateAttemptFailed && (
                            <p className="system-warning">{t.failed}</p>
                        )}
                        <dl className="system-fields">
                            <Field label={t.version}>{data.projectVersion || t.unknown}</Field>
                            <Field label={t.filters}>{data.listsPacketVersion || t.unknown}</Field>
                            <Field label={t.available}>
                                {data.updatesAvailable ? t.yes : t.no}
                            </Field>
                        </dl>
                        <div className="updates-actions">
                            <button
                                className="button secondary"
                                disabled={locked || Boolean(pending)}
                                onClick={() => void perform()}
                            >
                                {t.check}
                            </button>
                            <button
                                className="button primary"
                                disabled={locked || Boolean(pending) || !data.updatesAvailable}
                                onClick={() => propose('install')}
                            >
                                {t.install}
                            </button>
                            {data.lastUpdateAttemptFailed && (
                                <button
                                    className="button secondary"
                                    disabled={locked || Boolean(pending)}
                                    onClick={() => propose('recover')}
                                >
                                    {t.recover}
                                </button>
                            )}
                        </div>
                        <h3>{t.automatic}</h3>
                        <p>{data.automaticUpdatesActivated ? t.yes : t.no}</p>
                        {!data.automaticUpdatesAllowed && <p>{t.restricted}</p>}
                        <dl className="system-fields">
                            <Field label={t.last}>{data.lastAutomaticUpdate || t.unknown}</Field>
                            <Field label={t.next}>{data.nextAutomaticUpdate || t.unknown}</Field>
                        </dl>
                        <button
                            className="button secondary"
                            disabled={locked || Boolean(pending) || !data.automaticUpdatesAllowed}
                            onClick={() => propose('automatic')}
                        >
                            {data.automaticUpdatesActivated ? t.disable : t.enable}
                        </button>
                        {data.beginHour !== undefined &&
                            data.beginMin !== undefined &&
                            data.endHour !== undefined &&
                            data.endMin !== undefined && (
                                <fieldset
                                    className="updates-window"
                                    disabled={
                                        locked || Boolean(pending) || !data.automaticUpdatesAllowed
                                    }
                                >
                                    <legend>{t.window}</legend>
                                    <p>{t.timezone}</p>
                                    <label>
                                        {t.from}
                                        <input
                                            type="time"
                                            value={start}
                                            onChange={(event) => setStart(event.target.value)}
                                        />
                                    </label>
                                    <label>
                                        {t.until}
                                        <input
                                            type="time"
                                            value={end}
                                            onChange={(event) => setEnd(event.target.value)}
                                        />
                                    </label>
                                    <button
                                        className="button secondary"
                                        onClick={() => propose('schedule')}
                                    >
                                        {t.save}
                                    </button>
                                </fieldset>
                            )}
                        {data.updateablePackages?.length ? (
                            <details>
                                <summary>{t.packages}</summary>
                                <ul>
                                    {data.updateablePackages.map((name, i) => (
                                        <li key={i}>{name}</li>
                                    ))}
                                </ul>
                            </details>
                        ) : null}
                        {data.updateProgress?.length ? (
                            <details>
                                <summary>{t.progress}</summary>
                                <pre>{data.updateProgress.join('\n')}</pre>
                            </details>
                        ) : null}
                    </>
                )}
            </ResourcePanel>
            {pending && (
                <Confirmation
                    title={t.confirm}
                    description={descriptions[pending]}
                    cancel={() => setPending(undefined)}
                    cancelLabel={t.cancel}
                    disabled={busy}
                >
                    <button
                        className="button primary"
                        disabled={busy}
                        onClick={() => void perform(pending)}
                    >
                        {busy ? t.busy : t.proceed}
                    </button>
                </Confirmation>
            )}
        </section>
    );
}
