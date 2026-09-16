// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import type { Locale } from '../../i18n';
import { profileIdSchema } from './managementContracts';
import type { ProfileSummary } from './managementContracts';
import { managementMessages } from './managementMessages';

export function ProfileImport({
    locale,
    profiles,
    busy,
    onImport,
    onClose,
}: {
    locale: Locale;
    profiles: ProfileSummary[];
    busy: boolean;
    onImport: (id: string, configuration: string, replace: boolean) => Promise<void>;
    onClose: () => void;
}) {
    const t = managementMessages[locale];
    const [id, setId] = useState('');
    const [configuration, setConfiguration] = useState('');
    const [replace, setReplace] = useState(false);
    const [failure, setFailure] = useState<
        'invalidId' | 'configInvalid' | 'fileFailed' | 'confirmationRequired'
    >();
    const fileInput = useRef<HTMLInputElement>(null);
    const identifierInput = useRef<HTMLInputElement>(null);
    const reading = useRef(0);
    const submitting = useRef(false);
    const existing = profiles.some((profile) => profile.profileId === id.trim());
    useEffect(() => {
        identifierInput.current?.focus();
    }, []);
    function erase() {
        ++reading.current;
        setConfiguration('');
        if (fileInput.current) fileInput.current.value = '';
    }
    useEffect(() => {
        erase();
        setFailure(undefined);
        return () => {
            ++reading.current;
        };
    }, [locale]);
    return (
        <form
            className="wireguard-import"
            aria-label={t.import}
            noValidate
            onSubmit={async (event) => {
                event.preventDefault();
                if (busy || submitting.current) return;
                if (!profileIdSchema.safeParse(id.trim()).success) {
                    setFailure('invalidId');
                    return;
                }
                if (
                    !configuration.trim() ||
                    new TextEncoder().encode(configuration).length > 65536
                ) {
                    erase();
                    setFailure('configInvalid');
                    return;
                }
                if (existing && !replace) {
                    setFailure('confirmationRequired');
                    return;
                }
                const secret = configuration;
                erase();
                setFailure(undefined);
                submitting.current = true;
                try {
                    await onImport(id.trim(), secret, replace);
                } finally {
                    submitting.current = false;
                }
            }}
        >
            <h3>{t.import}</h3>
            <p>{t.importHelp}</p>
            <fieldset disabled={busy}>
                <label>
                    {t.profileId}
                    <input
                        value={id}
                        ref={identifierInput}
                        maxLength={32}
                        autoComplete="off"
                        autoCapitalize="none"
                        spellCheck={false}
                        onChange={(event) => {
                            setId(event.target.value);
                            setReplace(false);
                        }}
                        required
                    />
                </label>
                <p className="wireguard-help">{t.idHelp}</p>
                <label>
                    {t.file}
                    <input
                        type="file"
                        accept=".conf,.txt,text/plain"
                        ref={fileInput}
                        onChange={async (event) => {
                            const file = event.target.files?.[0];
                            erase();
                            setFailure(undefined);
                            if (!file) return;
                            if (file.size > 65536 || file.size === 0) {
                                setFailure('configInvalid');
                                return;
                            }
                            const request = ++reading.current;
                            try {
                                const text = await file.text();
                                if (reading.current === request) {
                                    if (new TextEncoder().encode(text).length > 65536)
                                        setFailure('configInvalid');
                                    else setConfiguration(text);
                                }
                            } catch {
                                if (reading.current === request) setFailure('fileFailed');
                            }
                        }}
                    />
                </label>
                <label>
                    {t.config}
                    <textarea
                        rows={8}
                        value={configuration}
                        onChange={(event) => {
                            ++reading.current;
                            setConfiguration(event.target.value);
                        }}
                        autoComplete="off"
                        autoCapitalize="none"
                        spellCheck={false}
                        required
                    />
                </label>
                {existing && (
                    <label className="wireguard-check">
                        <input
                            type="checkbox"
                            checked={replace}
                            onChange={(event) => setReplace(event.target.checked)}
                        />
                        {t.overwrite}
                    </label>
                )}
            </fieldset>
            {failure && (
                <p className="error-message" role="alert">
                    {t[failure]}
                </p>
            )}
            <div className="wireguard-actions">
                <button className="button primary" disabled={busy} type="submit">
                    {t.import}
                </button>
                <button
                    className="button secondary"
                    disabled={busy}
                    type="button"
                    onClick={() => {
                        erase();
                        setFailure(undefined);
                    }}
                >
                    {t.clear}
                </button>
                <button
                    className="button secondary"
                    disabled={busy}
                    type="button"
                    onClick={onClose}
                >
                    {t.close}
                </button>
            </div>
        </form>
    );
}
