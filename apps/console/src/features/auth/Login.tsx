// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import { errors, texts } from '../../i18n';
import type { Locale } from '../../i18n';

export function Login({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = texts[locale];
    const [password, setPassword] = useState('');
    const [busy, setBusy] = useState(false);
    const [failure, setFailure] = useState<ErrorCode>();
    const [seconds, setSeconds] = useState(0);
    const mounted = useRef(true);
    useEffect(() => {
        mounted.current = true;
        void client
            .loginWait()
            .then((value) => {
                if (mounted.current) setSeconds(value);
            })
            .catch(() => {
                /* The server enforces the wait on login too. */
            });
        const timer = setInterval(() => setSeconds((value) => Math.max(0, value - 1)), 1000);
        return () => {
            mounted.current = false;
            clearInterval(timer);
        };
    }, [client]);
    async function submit(event: FormEvent) {
        event.preventDefault();
        if (busy || seconds > 0) return;
        setBusy(true);
        setFailure(undefined);
        try {
            await client.login(password);
        } catch (error) {
            if (!mounted.current) return;
            const code = errorCode(error);
            setFailure(code);
            setPassword('');
            if (code === 'credentials' || code === 'wait') {
                try {
                    const wait = await client.loginWait();
                    if (mounted.current) setSeconds(wait);
                } catch {
                    /* Keep the original login failure visible. */
                }
            }
        } finally {
            if (mounted.current) setBusy(false);
        }
    }
    return (
        <section className="login-card">
            <span className="eyebrow">eBlocker</span>
            <h1>{t.loginTitle}</h1>
            <p>{t.loginText}</p>
            <form onSubmit={(event) => void submit(event)} aria-busy={busy}>
                <label htmlFor="admin-password">{t.password}</label>
                <input
                    id="admin-password"
                    type="password"
                    value={password}
                    autoComplete="current-password"
                    required
                    onChange={(event) => setPassword(event.target.value)}
                    aria-describedby={failure ? 'login-error' : undefined}
                />
                {failure && (
                    <p id="login-error" className="error-message" role="alert">
                        {errors[locale][failure]}
                    </p>
                )}
                {seconds > 0 && (
                    <p className="wait-message" role="status">
                        {t.wait.replace('{seconds}', String(seconds))}
                    </p>
                )}
                <button className="button primary" disabled={busy || seconds > 0 || !password}>
                    {busy ? t.loading : t.signIn}
                </button>
                <a className="help-link" href="/settings/#!/resetpassword">
                    {t.passwordHelp}
                </a>
            </form>
        </section>
    );
}
