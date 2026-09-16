// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { booleanLabel, Field, PageHeading, reported, ResourcePanel } from '../system/presentation';
import { useResource } from '../system/resource';
import { certificateSchema, sslStatusSchema } from './contracts';
import { Confirmation } from './Confirmation';
import { messages } from './messages';

export function HttpsPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const [revision, setRevision] = useState(0);
    const [confirmDownload, setConfirmDownload] = useState(false);
    const status = useResource(client, '/ssl/status', sslStatusSchema, revision);
    const certificate = useResource(client, '/ssl/rootca', certificateSchema, revision);
    const refresh = () => {
        setConfirmDownload(false);
        setRevision((previous) => previous + 1);
    };
    const formatDate = (time: number) =>
        new Intl.DateTimeFormat(locale, { dateStyle: 'long', timeStyle: 'short' }).format(time);
    return (
        <>
            <PageHeading
                title={t.https}
                description={t.httpsIntro}
                loading={status.loading || certificate.loading}
                refresh={refresh}
                locale={locale}
            />
            <div className="protection-sections">
                <ResourcePanel
                    title={t.httpsStatus}
                    resource={status}
                    locale={locale}
                    retry={refresh}
                >
                    {(enabled) => (
                        <>
                            <p>{booleanLabel(enabled, locale)}</p>
                            <p>{t.httpsNote}</p>
                        </>
                    )}
                </ResourcePanel>
                <ResourcePanel
                    title={t.certificate}
                    resource={certificate}
                    locale={locale}
                    retry={refresh}
                >
                    {(cert) =>
                        cert === null ? (
                            <p>{t.noCertificate}</p>
                        ) : (
                            <>
                                {cert.notAfter <= Date.now() && (
                                    <p className="system-warning">{t.expired}</p>
                                )}
                                {cert.notBefore > Date.now() && (
                                    <p className="system-warning">{t.notYetValid}</p>
                                )}
                                <dl className="system-fields">
                                    <Field label={t.certificateName}>
                                        {reported(cert.distinguishedName?.commonName, locale)}
                                    </Field>
                                    <Field label={t.validFrom}>{formatDate(cert.notBefore)}</Field>
                                    <Field label={t.validUntil}>{formatDate(cert.notAfter)}</Field>
                                    <Field label={t.fingerprint}>
                                        <code>{reported(cert.fingerprintSha256, locale)}</code>
                                    </Field>
                                </dl>
                                <button
                                    className="button secondary"
                                    onClick={() => setConfirmDownload(true)}
                                    disabled={
                                        !!certificate.error ||
                                        certificate.loading ||
                                        confirmDownload
                                    }
                                >
                                    {t.download}
                                </button>
                                {confirmDownload && (
                                    <Confirmation
                                        title={t.downloadTitle}
                                        description={t.downloadExplanation}
                                        cancel={() => setConfirmDownload(false)}
                                        cancelLabel={t.cancel}
                                    >
                                        {/* This existing public route returns only the certificate, but also enables SSL for the requesting device. */}
                                        <a
                                            className="button primary"
                                            href="/api/ssl/caCertificate.crt"
                                            download
                                            onClick={() => setConfirmDownload(false)}
                                        >
                                            {t.downloadConfirm}
                                        </a>
                                    </Confirmation>
                                )}
                            </>
                        )
                    }
                </ResourcePanel>
            </div>
        </>
    );
}
