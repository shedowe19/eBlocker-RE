// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { Confirmation } from '../protection/Confirmation';
import { backupReferenceSchema, backupResultSchema, validBackupFile } from './contracts';
import type { BackupReference, BackupWarning } from './contracts';
import { backupMessages } from './messages';
import { BackupWarnings } from './BackupWarnings';
import { useTransfer } from './transfer';

export function RestoreBackup({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = backupMessages[locale];
    const transfer = useTransfer();
    const input = useRef<HTMLInputElement>(null);
    const restoreButton = useRef<HTMLButtonElement>(null);
    const verifiedPassword = useRef<string | undefined>(undefined);
    const [selected, setSelected] = useState<File>();
    const [uploaded, setUploaded] = useState<BackupReference & { filename: string }>();
    const [includeKeys, setIncludeKeys] = useState(false);
    const [password, setPassword] = useState('');
    const [validation, setValidation] = useState<'fileInvalid' | 'passwordInvalid'>();
    const [step, setStep] = useState<'select' | 'verify' | 'verified' | 'restored' | 'uncertain'>(
        'select',
    );
    const [confirm, setConfirm] = useState(false);
    const [warnings, setWarnings] = useState<BackupWarning[]>([]);
    useEffect(
        () => () => {
            verifiedPassword.current = undefined;
        },
        [],
    );

    function reset() {
        transfer.cancel();
        setSelected(undefined);
        setUploaded(undefined);
        setPassword('');
        verifiedPassword.current = undefined;
        setValidation(undefined);
        setWarnings([]);
        setStep('select');
        setConfirm(false);
        if (input.current) input.current.value = '';
    }
    function upload() {
        if (!selected || !validBackupFile(selected)) {
            setValidation('fileInvalid');
            return;
        }
        const file = selected;
        setValidation(undefined);
        setSelected(undefined);
        if (input.current) input.current.value = '';
        void transfer.run(
            (signal) => client.uploadConfigBackup(file, backupReferenceSchema, signal),
            (result) => {
                setUploaded({ ...result, filename: file.name });
                setIncludeKeys(result.passwordRequired);
                setStep('verify');
            },
        );
    }
    function verify() {
        if (!uploaded) return;
        if (
            uploaded.passwordRequired &&
            includeKeys &&
            (!password.length || password.length > 50)
        ) {
            setValidation('passwordInvalid');
            return;
        }
        const secret = uploaded.passwordRequired && includeKeys ? password : undefined;
        setPassword('');
        setValidation(undefined);
        void transfer.run(
            (signal) =>
                client.configBackupPost(
                    'verify',
                    {
                        fileReference: uploaded.fileReference,
                        ...(secret === undefined ? {} : { password: secret }),
                    },
                    backupResultSchema,
                    signal,
                ),
            (result) => {
                verifiedPassword.current = secret;
                setWarnings(result.warnings);
                setStep('verified');
            },
            () => {
                verifiedPassword.current = undefined;
            },
        );
    }
    function restore() {
        if (!uploaded || step !== 'verified') return;
        const secret = verifiedPassword.current;
        verifiedPassword.current = undefined;
        setConfirm(false);
        void transfer.run(
            (signal) =>
                client.configBackupPost(
                    'import',
                    {
                        fileReference: uploaded.fileReference,
                        clientFileName: uploaded.filename,
                        ...(secret === undefined ? {} : { password: secret }),
                    },
                    backupResultSchema,
                    signal,
                ),
            (result) => {
                setUploaded(undefined);
                setWarnings(result.warnings);
                setStep('restored');
            },
            () => {
                setUploaded(undefined);
                setStep('uncertain');
            },
        );
    }
    return (
        <section
            className="system-panel backup-panel"
            aria-labelledby="backup-restore-title"
            aria-busy={transfer.busy}
        >
            <h2 id="backup-restore-title">{t.restoreTitle}</h2>
            <p>{t.restoreInfo}</p>
            {step === 'select' && (
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        upload();
                    }}
                >
                    <fieldset disabled={transfer.busy}>
                        <label htmlFor="backup-upload">{t.file}</label>
                        <input
                            ref={input}
                            id="backup-upload"
                            type="file"
                            accept=".eblcfg"
                            onChange={(event) => {
                                setSelected(event.target.files?.[0]);
                                setValidation(undefined);
                            }}
                        />
                        <button className="button" type="submit">
                            {transfer.busy ? t.uploading : t.upload}
                        </button>
                    </fieldset>
                </form>
            )}
            {uploaded && (
                <p>
                    {t.uploaded}: <strong>{uploaded.filename}</strong>
                </p>
            )}
            {step === 'verify' && uploaded && (
                <form
                    onSubmit={(event) => {
                        event.preventDefault();
                        verify();
                    }}
                >
                    <fieldset disabled={transfer.busy}>
                        {uploaded.passwordRequired && (
                            <>
                                <label className="backup-checkbox">
                                    <input
                                        type="checkbox"
                                        checked={includeKeys}
                                        onChange={(event) => {
                                            setIncludeKeys(event.target.checked);
                                            setPassword('');
                                            setValidation(undefined);
                                        }}
                                    />
                                    {t.restoreKeys}
                                </label>
                                {includeKeys ? (
                                    <>
                                        <label htmlFor="backup-import-password">{t.password}</label>
                                        <input
                                            id="backup-import-password"
                                            type="password"
                                            autoComplete="off"
                                            maxLength={50}
                                            value={password}
                                            onChange={(event) => setPassword(event.target.value)}
                                        />
                                    </>
                                ) : (
                                    <p>{t.skipKeys}</p>
                                )}
                            </>
                        )}
                        <button className="button" type="submit">
                            {transfer.busy ? t.verifying : t.verify}
                        </button>
                    </fieldset>
                </form>
            )}
            {validation && (
                <p role="alert" className="error-banner">
                    {t[validation]}
                </p>
            )}
            {transfer.error && (
                <p role="alert" className="error-banner">
                    {errors[locale][transfer.error]}
                </p>
            )}
            <BackupWarnings warnings={warnings} locale={locale} />
            {step === 'verified' && (
                <>
                    <p role="status">{transfer.busy ? t.restoring : t.verified}</p>
                    <div className="backup-actions">
                        <button
                            ref={restoreButton}
                            className="button"
                            disabled={transfer.busy || confirm}
                            onClick={() => setConfirm(true)}
                        >
                            {t.restore}
                        </button>
                        <button
                            className="button secondary"
                            disabled={transfer.busy}
                            onClick={() => {
                                verifiedPassword.current = undefined;
                                setWarnings([]);
                                setConfirm(false);
                                setStep('verify');
                            }}
                        >
                            {t.edit}
                        </button>
                    </div>
                    {confirm && (
                        <Confirmation
                            returnFocusRef={restoreButton}
                            title={t.confirmTitle}
                            description={t.confirmInfo}
                            cancel={() => setConfirm(false)}
                            cancelLabel={t.cancel}
                            disabled={transfer.busy}
                        >
                            <button
                                className="button danger"
                                disabled={transfer.busy}
                                onClick={restore}
                            >
                                {t.confirm}
                            </button>
                        </Confirmation>
                    )}
                </>
            )}
            {step === 'restored' && (
                <>
                    <p role="status">{t.restored}</p>
                    <RestartAfterRestore client={client} locale={locale} />
                </>
            )}
            {step === 'uncertain' && (
                <>
                    <p role="alert">{t.ambiguous}</p>
                    <a className="button secondary" href="#/system">
                        {t.system}
                    </a>
                </>
            )}
            {step !== 'restored' && (
                <button
                    className="button secondary"
                    disabled={transfer.busy && step === 'verified'}
                    onClick={reset}
                >
                    {t.reset}
                </button>
            )}
        </section>
    );
}

function RestartAfterRestore({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const transfer = useTransfer();
    const restartButton = useRef<HTMLButtonElement>(null);
    const [confirm, setConfirm] = useState(false);
    const [requested, setRequested] = useState(false);
    const t =
        locale === 'de'
            ? {
                  open: 'eBlocker neu starten',
                  title: 'Neustart bestätigen',
                  description:
                      'Der Neustart unterbricht Netzwerkverbindungen und diese Verwaltung. Öffne die Oberfläche danach erneut; die wiederhergestellten Netzwerkeinstellungen können eine andere Adresse erfordern.',
                  confirm: 'Jetzt neu starten',
                  cancel: 'Abbrechen',
                  done: 'Neustart angefordert. Warte, bis der eBlocker wieder erreichbar ist.',
                  unknown:
                      'Die Antwort konnte nicht bestätigt werden. Der Neustart kann bereits begonnen haben. Warte und prüfe die Erreichbarkeit, bevor du erneut startest.',
              }
            : {
                  open: 'Restart eBlocker',
                  title: 'Confirm restart',
                  description:
                      'Restarting interrupts network connections and this console. Reopen it afterwards; restored network settings may require a different address.',
                  confirm: 'Restart now',
                  cancel: 'Cancel',
                  done: 'Restart requested. Wait until eBlocker is reachable again.',
                  unknown:
                      'The response could not be confirmed. The restart may already have begun. Wait and check reachability before trying again.',
              };
    return (
        <div>
            {!requested && (
                <button
                    ref={restartButton}
                    className="button"
                    disabled={transfer.busy || confirm}
                    onClick={() => setConfirm(true)}
                >
                    {t.open}
                </button>
            )}
            {confirm && (
                <Confirmation
                    returnFocusRef={restartButton}
                    title={t.title}
                    description={t.description}
                    cancel={() => setConfirm(false)}
                    cancelLabel={t.cancel}
                    disabled={transfer.busy}
                >
                    <button
                        className="button danger"
                        disabled={transfer.busy}
                        onClick={() => {
                            setConfirm(false);
                            setRequested(true);
                            void transfer.run(
                                (signal) => client.postVoid('/systemstatus/reboot', {}, signal),
                                () => {},
                            );
                        }}
                    >
                        {t.confirm}
                    </button>
                </Confirmation>
            )}
            {requested && (
                <p role={transfer.error ? 'alert' : 'status'}>
                    {transfer.error ? t.unknown : t.done}
                </p>
            )}
        </div>
    );
}
