// SPDX-License-Identifier: EUPL-1.2
import { useId } from 'react';
import type { ReactNode } from 'react';
import { errors } from '../../i18n';
import type { Locale } from '../../i18n';
import { messages, states } from './messages';
import type { Resource } from './resource';

export function PageHeading({
    title,
    description,
    loading,
    refresh,
    locale,
}: {
    title: string;
    description: string;
    loading: boolean;
    refresh: () => void;
    locale: Locale;
}) {
    const t = messages[locale];
    return (
        <div className="page-heading system-page-heading">
            <div>
                <span className="eyebrow">eBlocker</span>
                <h1>{title}</h1>
                <p>{description}</p>
            </div>
            <button className="button secondary" disabled={loading} onClick={refresh}>
                {loading ? t.refreshing : t.refresh}
            </button>
        </div>
    );
}

export function ResourcePanel<T>({
    title,
    resource,
    locale,
    retry,
    children,
}: {
    title: string;
    resource: Resource<T>;
    locale: Locale;
    retry: () => void;
    children: (data: T) => ReactNode;
}) {
    const headingId = useId();
    const t = messages[locale];
    return (
        <section className="system-panel" aria-labelledby={headingId} aria-busy={resource.loading}>
            <h2 id={headingId}>{title}</h2>
            {resource.updated && (
                <p className="freshness">
                    {t.loaded}:{' '}
                    {new Intl.DateTimeFormat(locale, { timeStyle: 'medium' }).format(
                        resource.updated,
                    )}
                </p>
            )}
            {resource.error && (
                <div className="error-banner" role="alert">
                    <p>
                        {resource.data !== undefined && `${t.stale} `}
                        {errors[locale][resource.error]}
                    </p>
                    <button
                        className="button secondary"
                        onClick={retry}
                        disabled={resource.loading}
                    >
                        {t.retry}
                    </button>
                </div>
            )}
            {resource.data === undefined && resource.loading && <p role="status">{t.loading}</p>}
            {resource.data !== undefined && children(resource.data)}
        </section>
    );
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
    return (
        <div className="system-field">
            <dt>{label}</dt>
            <dd>{children}</dd>
        </div>
    );
}

export function reported(value: string | number | undefined | null, locale: Locale): string {
    return value === null ||
        value === undefined ||
        (typeof value === 'string' && value.trim() === '')
        ? messages[locale].unknown
        : String(value);
}

export function lookupLabel(
    labels: Record<string, string>,
    value: string,
    fallback = value,
): string {
    return Object.hasOwn(labels, value) ? labels[value] : fallback;
}

export function booleanLabel(value: boolean | undefined, locale: Locale, yesNo = false): string {
    const t = messages[locale];
    if (value === undefined) return t.unknown;
    return yesNo ? (value ? t.yes : t.no) : value ? t.enabled : t.disabled;
}

export function AddressList({
    addresses,
    locale,
}: {
    addresses?: string[] | null;
    locale: Locale;
}) {
    if (!addresses) return <span>{messages[locale].unknown}</span>;
    if (!addresses.length) return <span>{messages[locale].empty}</span>;
    return (
        <ul className="system-addresses">
            {addresses.map((address, index) => (
                <li key={`${index}-${address}`}>
                    <code>{address}</code>
                </li>
            ))}
        </ul>
    );
}

export function Status({ state, locale }: { state: string; locale: Locale }) {
    const severity = ['ERROR', 'SELF_CHECK_NOT_OK'].includes(state)
        ? 'error'
        : ['WARN', 'UPDATING', 'BOOTING', 'STARTING'].includes(state)
          ? 'warning'
          : ['OK', 'RUNNING', 'SELF_CHECK_OK'].includes(state)
            ? 'ok'
            : 'neutral';
    return (
        <span className={`system-status ${severity}`}>
            {lookupLabel(states[locale], state, `${messages[locale].stateUnknown}: ${state}`)}
        </span>
    );
}
