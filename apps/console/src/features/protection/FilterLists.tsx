// SPDX-License-Identifier: EUPL-1.2
import { useId, useState } from 'react';
import type { Locale } from '../../i18n';
import type { Blocker } from './contracts';
import { categories, messages, methods, updateStates } from './messages';
import { lookupLabel } from '../system/presentation';

export function localized(
    value: Record<string, string> | null | undefined,
    locale: Locale,
    fallback: string,
): string {
    const text = value && (Object.hasOwn(value, locale) ? value[locale] : value.en);
    return text?.trim() || fallback;
}

export function FilterLists({ lists, locale }: { lists: Blocker[]; locale: Locale }) {
    const t = messages[locale];
    const searchId = useId();
    const categoryId = useId();
    const [query, setQuery] = useState('');
    const [category, setCategory] = useState('');
    const filtered = lists.filter(
        (list) =>
            (!category || list.category === category) &&
            `${localized(list.name, locale, String(list.id))} ${localized(list.description, locale, '')}`
                .toLocaleLowerCase(locale)
                .includes(query.toLocaleLowerCase(locale).trim()),
    );
    if (!lists.length) return <p>{t.emptyLists}</p>;
    return (
        <>
            <p>{t.listsNote}</p>
            <div className="protection-controls">
                <label htmlFor={searchId}>
                    {t.listSearch}
                    <input
                        id={searchId}
                        type="search"
                        value={query}
                        onChange={(event) => setQuery(event.target.value)}
                    />
                </label>
                <label htmlFor={categoryId}>
                    {t.category}
                    <select
                        id={categoryId}
                        value={category}
                        onChange={(event) => setCategory(event.target.value)}
                    >
                        <option value="">{t.allCategories}</option>
                        {[...new Set(lists.map((list) => list.category))].sort().map((value) => (
                            <option key={value} value={value}>
                                {lookupLabel(categories[locale], value)}
                            </option>
                        ))}
                    </select>
                </label>
            </div>
            {!filtered.length ? (
                <p role="status">{t.noResults}</p>
            ) : (
                <div className="system-table-scroll">
                    <table className="system-table" aria-label={t.lists}>
                        <thead>
                            <tr>
                                <th scope="col">{t.listName}</th>
                                <th scope="col">{t.category}</th>
                                <th scope="col">{t.method}</th>
                                <th scope="col">{t.state}</th>
                                <th scope="col">{t.update}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filtered.map((list) => (
                                <tr key={list.id}>
                                    <th scope="row">
                                        {localized(list.name, locale, `#${list.id}`)}
                                        <small className="protection-caption">
                                            {list.providedByEblocker ? t.builtin : t.custom}
                                        </small>
                                    </th>
                                    <td>{lookupLabel(categories[locale], list.category)}</td>
                                    <td>{lookupLabel(methods[locale], list.type)}</td>
                                    <td>{list.enabled ? t.enabled : t.disabled}</td>
                                    <td>
                                        {list.updateStatus
                                            ? lookupLabel(updateStates[locale], list.updateStatus)
                                            : t.unknown}
                                        {list.error && (
                                            <p className="error-message">{list.error}</p>
                                        )}
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            )}
        </>
    );
}
