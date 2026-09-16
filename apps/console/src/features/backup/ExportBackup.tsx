// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { backupExportSchema } from './contracts';
import type { BackupWarning } from './contracts';
import { backupMessages } from './messages';
import { BackupWarnings } from './BackupWarnings';
import { useDownload, useTransfer } from './transfer';

export function ExportBackup({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = backupMessages[locale];
    const transfer = useTransfer();
    const file = useDownload();
    const [includeKeys, setIncludeKeys] = useState(true);
    const [password, setPassword] = useState('');
    const [repeat, setRepeat] = useState('');
    const [validation, setValidation] = useState<'passwordInvalid' | 'mismatch'>();
    const [reference, setReference] = useState<string>();
    const [warnings, setWarnings] = useState<BackupWarning[]>([]);

    function reset() {
        transfer.cancel();
        file.clear();
        setReference(undefined);
        setWarnings([]);
        setPassword('');
        setRepeat('');
        setValidation(undefined);
    }
    function create() {
        if (includeKeys && (password.length === 0 || password.length > 50)) {
            setValidation('passwordInvalid');
            return;
        }
        if (includeKeys && password !== repeat) {
            setValidation('mismatch');
            return;
        }
        const body = { passwordRequired: includeKeys, ...(includeKeys ? { password } : {}) };
        setPassword('');
        setRepeat('');
        setValidation(undefined);
        file.clear();
        setReference(undefined);
        setWarnings([]);
        void transfer.run(
            (signal) => client.configBackupPost('export', body, backupExportSchema, signal),
            (result) => {
                setReference(result.configBackupReference.fileReference);
                setWarnings(result.warnings);
            },
        );
    }
    return (
        <section
            className="system-panel backup-panel"
            aria-labelledby="backup-export-title"
            aria-busy={transfer.busy}
        >
            <h2 id="backup-export-title">{t.exportTitle}</h2>
            <p>{t.exportInfo}</p>
            <form
                onSubmit={(event) => {
                    event.preventDefault();
                    create();
                }}
            >
                <fieldset disabled={transfer.busy}>
                    <label className="backup-checkbox">
                        <input
                            type="checkbox"
                            checked={includeKeys}
                            onChange={(event) => {
                                setIncludeKeys(event.target.checked);
                                setPassword('');
                                setRepeat('');
                                setValidation(undefined);
                            }}
                        />
                        {t.includeKeys}
                    </label>
                    {includeKeys ? (
                        <>
                            <label htmlFor="backup-export-password">{t.password}</label>
                            <input
                                id="backup-export-password"
                                type="password"
                                autoComplete="new-password"
                                maxLength={50}
                                value={password}
                                onChange={(event) => setPassword(event.target.value)}
                                aria-describedby="backup-password-help"
                            />
                            <label htmlFor="backup-export-repeat">{t.repeat}</label>
                            <input
                                id="backup-export-repeat"
                                type="password"
                                autoComplete="new-password"
                                maxLength={50}
                                value={repeat}
                                onChange={(event) => setRepeat(event.target.value)}
                            />
                            <p id="backup-password-help" className="muted">
                                {t.passwordHelp}
                            </p>
                        </>
                    ) : (
                        <p>{t.noKeys}</p>
                    )}
                    {validation && (
                        <p role="alert" className="error-banner">
                            {t[validation]}
                        </p>
                    )}
                    <button className="button" type="submit">
                        {transfer.busy ? t.creating : t.create}
                    </button>
                </fieldset>
            </form>
            {transfer.error && (
                <p role="alert" className="error-banner">
                    {errors[locale][transfer.error]}
                </p>
            )}
            <BackupWarnings warnings={warnings} locale={locale} />
            {reference && !file.download && (
                <button
                    className="button"
                    disabled={transfer.busy}
                    onClick={() => {
                        void transfer.run(
                            (signal) => client.downloadConfigBackup(reference, signal),
                            file.receive,
                        );
                    }}
                >
                    {transfer.busy ? t.downloading : t.prepareDownload}
                </button>
            )}
            {file.download && (
                <div role="status">
                    <p>{t.ready}</p>
                    <a
                        className="button"
                        href={file.download.url}
                        download={file.download.filename}
                    >
                        {t.download}: {file.download.filename}
                    </a>
                </div>
            )}
            <button className="button secondary" onClick={reset}>
                {t.reset}
            </button>
        </section>
    );
}
