// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useState } from 'react';
import { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import type { Resource } from '../system/resource';
import { PageHeading, ResourcePanel } from '../system/presentation';
import { Confirmation } from '../protection/Confirmation';
import { useDownload, useTransfer } from '../backup/transfer';
import '../backup/backup.css';
import './diagnostics.css';

// DiagnosticsReportService.State; no synthetic completion percentage.
export const reportStatusSchema = z.enum(['NOT_STARTED', 'PENDING', 'FINISHED', 'ERROR']);
type ReportStatus = z.infer<typeof reportStatusSchema>;

const de = {
    title: 'Diagnosebericht',
    intro: 'Erstelle einen lokalen Bericht zur Fehlersuche und lade ihn bei Bedarf herunter.',
    report: 'Bericht auf dem eBlocker',
    generate: 'Diagnosebericht erstellen',
    generating: 'Bericht wird erstellt …',
    confirmTitle: 'Diagnosedaten zusammenstellen?',
    confirmInfo:
        'Der Bericht enthält Systemprotokolle, Ereignisse und weitere Diagnosedaten. Er kann persönliche Daten enthalten. Ein vorhandener Bericht wird ersetzt. Die Erstellung sendet den Bericht nicht automatisch an Dritte.',
    confirm: 'Bericht jetzt erstellen',
    cancel: 'Abbrechen',
    prepare: 'Download bereitstellen',
    downloading: 'Bericht wird übertragen …',
    download: 'Diagnosebericht herunterladen',
    privacy:
        'Prüfe den Bericht vor dem Teilen und übermittle ihn nur gezielt an eine vertrauenswürdige Person. Ein Download bestätigt nicht, dass alle Teilberichte vollständig erstellt wurden.',
    release: 'Lokale Downloadkopie freigeben',
    retention:
        'Die lokale Freigabe entfernt nur den Download-Verweis dieser Seite. Die vorhandene API bietet keine Löschung des Berichts auf dem eBlocker und keine Auswahl seiner Inhalte.',
    failed: 'Die Berichtserstellung ist fehlgeschlagen. Du kannst sie erneut anfordern.',
    uncertain:
        'Die Anforderung konnte nicht bestätigt werden. Aktualisiere den Status, bevor du eine weitere Erstellung startest.',
    states: {
        NOT_STARTED: 'Noch kein Bericht erstellt',
        PENDING: 'Erstellung läuft',
        FINISHED: 'Bericht verfügbar',
        ERROR: 'Erstellung fehlgeschlagen',
    },
};
const en: typeof de = {
    title: 'Diagnostic report',
    intro: 'Create a local troubleshooting report and download it when needed.',
    report: 'Report on eBlocker',
    generate: 'Create diagnostic report',
    generating: 'Creating report …',
    confirmTitle: 'Collect diagnostic data?',
    confirmInfo:
        'The report includes system logs, events and other diagnostic data. It may contain personal information. An existing report will be replaced. Creating it does not automatically send the report to anyone.',
    confirm: 'Create report now',
    cancel: 'Cancel',
    prepare: 'Prepare download',
    downloading: 'Transferring report …',
    download: 'Download diagnostic report',
    privacy:
        'Review the report before sharing it and send it only to a trusted recipient. Downloading it does not confirm that every report section was generated completely.',
    release: 'Release local download copy',
    retention:
        'Releasing the local copy removes only this page’s download reference. The existing API provides no deletion of the report on eBlocker and no selection of its contents.',
    failed: 'Report generation failed. You can request it again.',
    uncertain:
        'The request could not be confirmed. Refresh the status before requesting another report.',
    states: {
        NOT_STARTED: 'No report created yet',
        PENDING: 'Generation in progress',
        FINISHED: 'Report available',
        ERROR: 'Generation failed',
    },
};

export function DiagnosticsPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = locale === 'de' ? de : en;
    const [resource, setResource] = useState<Resource<ReportStatus>>({ loading: true });
    const [revision, setRevision] = useState(0);
    const [confirm, setConfirm] = useState(false);
    const [uncertain, setUncertain] = useState(false);
    const transfer = useTransfer();
    const file = useDownload();
    useEffect(() => {
        const controller = new AbortController();
        let timer: ReturnType<typeof setTimeout> | undefined;
        async function read() {
            setResource((previous) => ({ ...previous, loading: true }));
            try {
                const status = await client.get(
                    '/diagnostics/report',
                    reportStatusSchema,
                    controller.signal,
                );
                if (controller.signal.aborted) return;
                setResource({ data: status, loading: false, updated: new Date() });
                if (status === 'PENDING')
                    timer = setTimeout(() => {
                        void read();
                    }, 2000);
            } catch (cause) {
                if (!controller.signal.aborted)
                    setResource((previous) => ({
                        ...previous,
                        loading: false,
                        error: errorCode(cause),
                    }));
            }
        }
        void read();
        return () => {
            controller.abort();
            clearTimeout(timer);
        };
    }, [client, revision]);
    const refresh = () => {
        setUncertain(false);
        setRevision((previous) => previous + 1);
    };
    function generate() {
        setConfirm(false);
        setUncertain(false);
        file.clear();
        void transfer.run(
            async (signal) => {
                // A different administrator may have started generation while confirmation was open.
                const current = await client.get('/diagnostics/report', reportStatusSchema, signal);
                if (current !== 'PENDING') await client.postVoid('/diagnostics/report', {}, signal);
            },
            refresh,
            () => {
                setUncertain(true);
                setRevision((previous) => previous + 1);
            },
        );
    }
    const locked =
        resource.loading ||
        !!resource.error ||
        !resource.data ||
        resource.data === 'PENDING' ||
        transfer.busy;
    return (
        <div className="diagnostics-page">
            <PageHeading
                title={t.title}
                description={t.intro}
                loading={resource.loading || transfer.busy}
                refresh={refresh}
                locale={locale}
            />
            <ResourcePanel title={t.report} resource={resource} locale={locale} retry={refresh}>
                {(status) => (
                    <>
                        <p className="diagnostics-state" role="status">
                            {t.states[status]}
                        </p>
                        {status === 'ERROR' && <p role="alert">{t.failed}</p>}
                        <div className="backup-actions">
                            <button
                                className="button"
                                disabled={locked || confirm || uncertain}
                                onClick={() => setConfirm(true)}
                            >
                                {status === 'PENDING' ? t.generating : t.generate}
                            </button>
                            {status === 'FINISHED' && !file.download && (
                                <button
                                    className="button secondary"
                                    disabled={locked || confirm}
                                    onClick={() => {
                                        void transfer.run(
                                            (signal) => client.downloadDiagnostics(signal),
                                            file.receive,
                                        );
                                    }}
                                >
                                    {transfer.busy ? t.downloading : t.prepare}
                                </button>
                            )}
                        </div>
                    </>
                )}
            </ResourcePanel>
            {transfer.error && (
                <p className="error-banner" role="alert">
                    {errors[locale][transfer.error]}
                </p>
            )}
            {uncertain && <p role="alert">{t.uncertain}</p>}
            {confirm && (
                <Confirmation
                    title={t.confirmTitle}
                    description={t.confirmInfo}
                    cancel={() => setConfirm(false)}
                    cancelLabel={t.cancel}
                    disabled={transfer.busy}
                >
                    <button className="button" disabled={transfer.busy} onClick={generate}>
                        {t.confirm}
                    </button>
                </Confirmation>
            )}
            <section className="system-panel diagnostics-privacy">
                <p>{t.privacy}</p>
                {file.download && (
                    <div className="backup-actions">
                        <a
                            className="button"
                            href={file.download.url}
                            download={file.download.filename}
                        >
                            {t.download}: {file.download.filename}
                        </a>
                        <button className="button secondary" onClick={file.clear}>
                            {t.release}
                        </button>
                    </div>
                )}
                <p className="muted">{t.retention}</p>
            </section>
        </div>
    );
}
