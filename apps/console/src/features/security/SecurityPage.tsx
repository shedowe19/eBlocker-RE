// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState, useSyncExternalStore } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { PasswordForm } from './PasswordForm';
import type { PasswordAction } from './PasswordForm';
import { securityMessages } from './messages';

export function SecurityPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = securityMessages[locale];
    const session = useSyncExternalStore(client.subscribe, client.snapshot);
    const passwordProtected =
        session.phase === 'ready' &&
        'passwordProtected' in session &&
        typeof session.passwordProtected === 'boolean'
            ? session.passwordProtected
            : undefined;
    const [action, setAction] = useState<PasswordAction>();
    const [saved, setSaved] = useState(false);
    const primaryAction = useRef<HTMLButtonElement>(null);
    const wasEditing = useRef(false);
    useEffect(() => {
        if (wasEditing.current && action === undefined) primaryAction.current?.focus();
        wasEditing.current = action !== undefined;
    }, [action]);

    return (
        <section className="security-page">
            <header className="security-heading">
                <span className="eyebrow">eBlocker</span>
                <h1>{t.title}</h1>
                <p>{t.introduction}</p>
            </header>
            {saved ? (
                <p className="security-success" role="status">
                    {t.saved}
                </p>
            ) : passwordProtected === undefined ? (
                <div className="security-panel">
                    <p role="status">{t.statusUnknown}</p>
                    <button
                        type="button"
                        className="security-secondary"
                        onClick={() => void client.bootstrap()}
                    >
                        {t.reload}
                    </button>
                </div>
            ) : (
                <>
                    <section className="security-panel security-protection" aria-label={t.status}>
                        <div>
                            <h2>{t.status}</h2>
                            <p>
                                {passwordProtected
                                    ? t.protectedDescription
                                    : t.unprotectedDescription}
                            </p>
                        </div>
                        <span
                            className={`security-badge${passwordProtected ? ' security-badge--enabled' : ''}`}
                        >
                            {passwordProtected ? t.enabled : t.disabled}
                        </span>
                    </section>
                    {action ? (
                        <PasswordForm
                            client={client}
                            locale={locale}
                            action={action}
                            passwordRequired={passwordProtected}
                            onCancel={() => setAction(undefined)}
                            onSaved={() => {
                                setAction(undefined);
                                setSaved(true);
                            }}
                        />
                    ) : (
                        <div className="security-actions">
                            <button
                                type="button"
                                className="security-primary"
                                ref={primaryAction}
                                onClick={() => setAction(passwordProtected ? 'change' : 'enable')}
                            >
                                {passwordProtected ? t.change : t.enable}
                            </button>
                            {passwordProtected && (
                                <button
                                    type="button"
                                    className="security-secondary"
                                    onClick={() => setAction('disable')}
                                >
                                    {t.disable}
                                </button>
                            )}
                        </div>
                    )}
                </>
            )}
        </section>
    );
}
