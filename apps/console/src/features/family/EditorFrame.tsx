// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import type { Locale } from '../../i18n';
import { familyMessages } from './messages';
import { editMessages } from './editMessages';

export type MutationResult = 'saved' | 'savedReload' | 'uncertain' | 'conflict' | 'invalid';
export type Mutate = (operation: () => Promise<unknown>) => Promise<MutationResult>;
export class FormValidation extends Error {}
export function EditorFrame({
    title,
    locale,
    onClose,
    onSave,
    children,
    submitLabel,
}: {
    title: string;
    locale: Locale;
    onClose: () => void;
    onSave: () => Promise<MutationResult>;
    children: ReactNode;
    submitLabel?: string;
}) {
    const t = editMessages[locale];
    const [pending, setPending] = useState(false);
    const [result, setResult] = useState<MutationResult>();
    const [validation, setValidation] = useState<string>();
    const lock = useRef(false);
    const mounted = useRef(true);
    const heading = useRef<HTMLHeadingElement>(null);
    useEffect(() => {
        mounted.current = true;
        heading.current?.focus();
        return () => {
            mounted.current = false;
        };
    }, []);
    useEffect(() => {
        setValidation(undefined);
    }, [locale]);
    const finished = result && result !== 'invalid';
    return (
        <section className="family-card family-editor" aria-label={title}>
            <h2 ref={heading} tabIndex={-1}>
                {title}
            </h2>
            <form
                aria-label={title}
                noValidate
                onSubmit={async (event) => {
                    event.preventDefault();
                    if (lock.current || finished) return;
                    lock.current = true;
                    setPending(true);
                    setValidation(undefined);
                    setResult(undefined);
                    try {
                        const status = await onSave();
                        if (mounted.current) setResult(status);
                    } catch (error) {
                        if (mounted.current)
                            setValidation(
                                error instanceof FormValidation ? error.message : t.uncertain,
                            );
                    } finally {
                        lock.current = false;
                        if (mounted.current) setPending(false);
                    }
                }}
            >
                <fieldset disabled={pending || Boolean(finished)} className="family-editor-fields">
                    {children}
                </fieldset>
                {validation && (
                    <p className="family-error" role="alert">
                        {validation}
                    </p>
                )}
                {result && (
                    <p
                        className={result.startsWith('saved') ? 'family-success' : 'family-error'}
                        role={result.startsWith('saved') ? 'status' : 'alert'}
                    >
                        {t[result]}
                    </p>
                )}
                {pending && <p role="status">{t.saving}</p>}
                <div className="family-actions">
                    {!finished && (
                        <button type="submit" className="family-primary" disabled={pending}>
                            {submitLabel ?? t.save}
                        </button>
                    )}
                    <button
                        type="button"
                        className="family-secondary"
                        disabled={pending}
                        onClick={onClose}
                    >
                        {finished ? familyMessages[locale].done : familyMessages[locale].cancel}
                    </button>
                </div>
            </form>
        </section>
    );
}
