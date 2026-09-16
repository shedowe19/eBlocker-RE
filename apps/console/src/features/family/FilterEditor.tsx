// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { errors } from '../../i18n';
import { errorCode } from '../../api/http';
import type { FamilyFilter } from './contracts';
import { domainsSchema, saveFilter } from './api';
import { EditorFrame, FormValidation } from './EditorFrame';
import type { Mutate } from './EditorFrame';
import { editMessages } from './editMessages';
import { familyMessages } from './messages';
import { filterName } from './presentation';

export function FilterEditor(props: {
    client: ConsoleClient;
    locale: Locale;
    mutate: Mutate;
    onClose: () => void;
    filter?: FamilyFilter;
}) {
    const { client, filter, locale, onClose } = props;
    const [domains, setDomains] = useState<string[]>();
    const [failure, setFailure] = useState<ReturnType<typeof errorCode>>();
    const [revision, setRevision] = useState(0);
    useEffect(() => {
        if (!filter || filter.builtin) {
            setDomains([]);
            return;
        }
        const abort = new AbortController();
        client
            .get(`/filterlists/${filter.id}/domains`, domainsSchema, abort.signal)
            .then((value) => {
                if (!abort.signal.aborted) {
                    setDomains(value);
                    setFailure(undefined);
                }
            })
            .catch((error) => {
                if (!abort.signal.aborted) setFailure(errorCode(error));
            });
        return () => abort.abort();
    }, [client, filter, revision]);
    if (failure)
        return (
            <section className="family-card family-editor">
                <p className="family-error" role="alert">
                    {errors[locale][failure]}
                </p>
                <button
                    className="family-secondary"
                    onClick={() => {
                        setFailure(undefined);
                        setRevision((value) => value + 1);
                    }}
                >
                    {familyMessages[locale].retry}
                </button>
                <button className="family-secondary" onClick={onClose}>
                    {familyMessages[locale].cancel}
                </button>
            </section>
        );
    if (!domains)
        return (
            <section className="family-card family-editor">
                <p role="status">{editMessages[locale].loadingDomains}</p>
                <button className="family-secondary" onClick={onClose}>
                    {familyMessages[locale].cancel}
                </button>
            </section>
        );
    return <FilterForm {...props} initialDomains={domains} />;
}

export function parseDomains(text: string): string[] {
    const domains = text
        .split(/\r?\n/)
        .map((line) => line.trim())
        .filter(Boolean);
    if (!domains.length) throw new Error('invalid domain');
    return [
        ...new Set(
            domains.map((domain) => {
                if (domain.length > 253 || /[\s/:@?#\\]/.test(domain))
                    throw new Error('invalid domain');
                const ascii = new URL(`https://${domain}`).hostname;
                if (!/^([a-z0-9-]+\.)+[a-z][a-z0-9-]*$/i.test(ascii))
                    throw new Error('invalid domain');
                return ascii.toLowerCase();
            }),
        ),
    ];
}
function FilterForm({
    client,
    locale,
    mutate,
    onClose,
    filter,
    initialDomains,
}: {
    client: ConsoleClient;
    locale: Locale;
    mutate: Mutate;
    onClose: () => void;
    filter?: FamilyFilter;
    initialDomains: string[];
}) {
    const t = editMessages[locale];
    const f = familyMessages[locale];
    const [name, setName] = useState(filter?.customerCreatedName ?? '');
    const [description, setDescription] = useState(filter?.customerCreatedDescription ?? '');
    const [type, setType] = useState<'blacklist' | 'whitelist'>(
        filter?.filterType === 'whitelist' ? 'whitelist' : 'blacklist',
    );
    const [enabled, setEnabled] = useState(!filter?.disabled);
    const [domains, setDomains] = useState(initialDomains.join('\n'));
    return (
        <EditorFrame
            title={filter ? `${t.editFilter}: ${filterName(filter, locale)}` : t.addFilter}
            locale={locale}
            onClose={onClose}
            onSave={async () => {
                let values = initialDomains;
                if (!filter?.builtin) {
                    if (!name.trim() || name.trim().length > 50 || description.length > 150)
                        throw new FormValidation(t.filterNameInvalid);
                    // Preserve existing imported lists verbatim if the domains were not edited.
                    if (domains !== initialDomains.join('\n') || !filter) {
                        try {
                            values = parseDomains(domains);
                        } catch {
                            throw new FormValidation(t.domainsInvalid);
                        }
                    }
                }
                return mutate(() =>
                    saveFilter(client, filter, initialDomains, {
                        customerCreatedName: name.trim(),
                        customerCreatedDescription: description,
                        domains: values,
                        disabled: !enabled,
                        filterType: type,
                    }),
                );
            }}
        >
            {filter?.builtin ? (
                <p>{t.builtinHelp}</p>
            ) : (
                <>
                    <label>
                        {t.name}
                        <input
                            maxLength={50}
                            required
                            value={name}
                            onChange={(event) => setName(event.target.value)}
                        />
                    </label>
                    <label>
                        {t.description}
                        <textarea
                            maxLength={150}
                            value={description}
                            onChange={(event) => setDescription(event.target.value)}
                        />
                    </label>
                    <label>
                        {f.mode}
                        <select
                            disabled={Boolean(filter)}
                            value={type}
                            onChange={(event) => setType(event.target.value as typeof type)}
                        >
                            <option value="blacklist">{f.blockLists}</option>
                            <option value="whitelist">{f.allowLists}</option>
                        </select>
                    </label>
                    <label>
                        {t.domains}
                        <textarea
                            rows={7}
                            value={domains}
                            onChange={(event) => setDomains(event.target.value)}
                            spellCheck={false}
                            required
                        />
                    </label>
                    <p className="family-muted">{t.domainsHelp}</p>
                </>
            )}
            <label className="family-check">
                <input
                    type="checkbox"
                    checked={enabled}
                    onChange={(event) => setEnabled(event.target.checked)}
                />
                {t.filterEnabled}
            </label>
        </EditorFrame>
    );
}
