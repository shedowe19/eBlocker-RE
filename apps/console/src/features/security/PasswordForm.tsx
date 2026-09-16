// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useId, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import { z } from 'zod';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { securityMessages } from './messages';

export type PasswordAction = 'enable' | 'change' | 'disable';
const waitSchema = z.number().finite();
// Matches SecurityService's new-password validation; current passwords retain legacy compatibility.
const maximumNewPasswordLength = 50;

export function PasswordForm({
    client,
    locale,
    action,
    passwordRequired,
    onCancel,
    onSaved,
}: {
    client: ConsoleClient;
    locale: Locale;
    action: PasswordAction;
    passwordRequired: boolean;
    onCancel: () => void;
    onSaved: () => void;
}) {
    const t = securityMessages[locale];
    const id = useId();
    const [currentPassword, setCurrentPassword] = useState('');
    const [newPassword, setNewPassword] = useState('');
    const [repeatPassword, setRepeatPassword] = useState('');
    const [confirmed, setConfirmed] = useState(false);
    const [busy, setBusy] = useState(false);
    const [failure, setFailure] = useState<ErrorCode>();
    const [validation, setValidation] = useState<
        'currentRequired' | 'newInvalid' | 'mismatch' | 'confirmationRequired'
    >();
    const [seconds, setSeconds] = useState(0);
    const pending = useRef(false);
    const mounted = useRef(true);
    const waitController = useRef<AbortController | undefined>(undefined);
    const ambiguous =
        failure === 'network' ||
        failure === 'timeout' ||
        failure === 'server' ||
        failure === 'invalidResponse';

    function clearSecrets() {
        setCurrentPassword('');
        setNewPassword('');
        setRepeatPassword('');
        setConfirmed(false);
    }

    async function refreshWait() {
        try {
            const value = await client.get(
                '/authentication/wait',
                waitSchema,
                waitController.current?.signal,
            );
            if (mounted.current) setSeconds(Math.max(0, Math.ceil(value)));
        } catch {
            // The server also enforces its wait period on every submitted password.
        }
    }

    useEffect(() => {
        mounted.current = true;
        waitController.current = new AbortController();
        void refreshWait();
        const timer = setInterval(() => setSeconds((value) => Math.max(0, value - 1)), 1000);
        return () => {
            mounted.current = false;
            waitController.current?.abort();
            clearInterval(timer);
        };
    }, [client]);

    useEffect(() => {
        clearSecrets();
        setValidation(undefined);
    }, [locale]);

    function cancel() {
        if (pending.current) return;
        clearSecrets();
        onCancel();
    }

    async function submit(event: FormEvent<HTMLFormElement>) {
        event.preventDefault();
        if (pending.current || seconds > 0 || ambiguous) return;
        setValidation(undefined);
        if (passwordRequired && !currentPassword) {
            setValidation('currentRequired');
            return;
        }
        if (
            action !== 'disable' &&
            (!newPassword || newPassword.length > maximumNewPasswordLength)
        ) {
            setValidation('newInvalid');
            return;
        }
        if (action !== 'disable' && newPassword !== repeatPassword) {
            setValidation('mismatch');
            return;
        }
        if (action === 'disable' && !confirmed) {
            setValidation('confirmationRequired');
            return;
        }
        pending.current = true;
        setBusy(true);
        setFailure(undefined);
        try {
            await client.postVoid(
                `/authentication/${action === 'disable' ? 'disable' : 'enable'}`,
                action === 'disable'
                    ? { currentPassword }
                    : { currentPassword: passwordRequired ? currentPassword : '', newPassword },
            );
            if (mounted.current) {
                clearSecrets();
                onSaved();
            }
            // This discards this browser's in-memory token, without claiming server-wide revocation.
            client.logout();
        } catch (error) {
            if (!mounted.current) return;
            const code = errorCode(error);
            clearSecrets();
            setFailure(code);
            if (code === 'credentials' || code === 'wait') await refreshWait();
        } finally {
            pending.current = false;
            if (mounted.current) setBusy(false);
        }
    }

    return (
        <form
            className="security-form"
            onSubmit={submit}
            noValidate
            aria-busy={busy}
            aria-labelledby={`${id}-heading`}
        >
            <h2 id={`${id}-heading`}>{t[action]}</h2>
            <fieldset disabled={busy || ambiguous}>
                <legend className="security-visually-hidden">{t[action]}</legend>
                {passwordRequired && (
                    <div className="security-field">
                        <label htmlFor={`${id}-current`}>{t.currentPassword}</label>
                        <input
                            id={`${id}-current`}
                            type="password"
                            value={currentPassword}
                            autoComplete="current-password"
                            required
                            autoFocus
                            aria-invalid={
                                validation === 'currentRequired' ||
                                failure === 'credentials' ||
                                undefined
                            }
                            onChange={(event) => setCurrentPassword(event.target.value)}
                        />
                    </div>
                )}
                {action !== 'disable' ? (
                    <>
                        <div className="security-field">
                            <label htmlFor={`${id}-new`}>{t.newPassword}</label>
                            <input
                                id={`${id}-new`}
                                type="password"
                                value={newPassword}
                                autoComplete="new-password"
                                required
                                autoFocus={!passwordRequired}
                                maxLength={maximumNewPasswordLength}
                                aria-describedby={`${id}-help`}
                                aria-invalid={validation === 'newInvalid' || undefined}
                                onChange={(event) => setNewPassword(event.target.value)}
                            />
                            <p id={`${id}-help`} className="security-help">
                                {t.passwordHelp}
                            </p>
                        </div>
                        <div className="security-field">
                            <label htmlFor={`${id}-repeat`}>{t.repeatPassword}</label>
                            <input
                                id={`${id}-repeat`}
                                type="password"
                                value={repeatPassword}
                                autoComplete="new-password"
                                required
                                maxLength={maximumNewPasswordLength}
                                aria-invalid={validation === 'mismatch' || undefined}
                                onChange={(event) => setRepeatPassword(event.target.value)}
                            />
                        </div>
                    </>
                ) : (
                    <div className="security-confirmation">
                        <p>{t.disableDescription}</p>
                        <label>
                            <input
                                type="checkbox"
                                checked={confirmed}
                                onChange={(event) => setConfirmed(event.target.checked)}
                            />{' '}
                            <span>{t.confirmDisable}</span>
                        </label>
                    </div>
                )}
            </fieldset>
            {validation && (
                <p className="security-error" role="alert">
                    {t[validation]}
                </p>
            )}
            {failure && (
                <p className="security-error" role="alert">
                    {ambiguous ? t.ambiguous : `${errors[locale][failure]} ${t.erased}`}
                </p>
            )}
            {seconds > 0 && (
                <p className="security-wait" role="status">
                    {t.wait(seconds)}
                </p>
            )}
            <p className="security-help">{t.reauthenticate}</p>
            <div className="security-actions">
                {ambiguous ? (
                    <button
                        type="button"
                        className="security-primary"
                        onClick={() => {
                            clearSecrets();
                            client.logout();
                        }}
                    >
                        {t.reconnect}
                    </button>
                ) : (
                    <button
                        type="submit"
                        className={action === 'disable' ? 'security-danger' : 'security-primary'}
                        disabled={busy || seconds > 0}
                    >
                        {busy ? t.saving : action === 'disable' ? t.disable : t.save}
                    </button>
                )}
                {!ambiguous && (
                    <button
                        type="button"
                        className="security-secondary"
                        onClick={cancel}
                        disabled={busy}
                    >
                        {t.cancel}
                    </button>
                )}
            </div>
            {busy && (
                <p className="security-help" role="status">
                    {t.saving}
                </p>
            )}
        </form>
    );
}
