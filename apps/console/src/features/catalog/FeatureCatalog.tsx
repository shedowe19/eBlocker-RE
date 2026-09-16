// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import { Link } from 'react-router-dom';
import type { Locale } from '../../i18n';
import catalog from './catalog.json';
import './catalog.css';

const messages = {
    de: {
        title: 'Alle Funktionen',
        intro: 'Finde alle Einstellungen und Werkzeuge deines eBlockers.',
        search: 'Funktionen suchen',
        open: 'Vollständige Einstellungen öffnen',
        current: 'In dieser Oberfläche öffnen',
        session:
            'Beim Wechsel zur vollständigen Verwaltung kann eine erneute Anmeldung erforderlich sein.',
        empty: 'Keine passende Funktion gefunden.',
        dashboard: 'Geräte-Dashboard öffnen',
        groups: {
            devices: 'Geräte',
            protection: 'Schutz und HTTPS',
            vpn: 'VPN und Tor',
            family: 'Familie',
            network: 'Netzwerk und DNS',
            system: 'System und Wartung',
        },
    },
    en: {
        title: 'All features',
        intro: 'Find every setting and tool for your eBlocker.',
        search: 'Search features',
        open: 'Open full settings',
        current: 'Open in this interface',
        session: 'Opening the full administration interface may require you to sign in again.',
        empty: 'No matching feature found.',
        dashboard: 'Open device dashboard',
        groups: {
            devices: 'Devices',
            protection: 'Protection and HTTPS',
            vpn: 'VPN and Tor',
            family: 'Family',
            network: 'Network and DNS',
            system: 'System and maintenance',
        },
    },
};

export function FeatureCatalog({ locale }: { locale: Locale }) {
    const t = messages[locale];
    const [search, setSearch] = useState('');
    const query = search.trim().toLocaleLowerCase(locale);
    const entries = catalog.filter(
        (item) =>
            item.visible &&
            `${item.title[locale]} ${t.groups[item.group as keyof typeof t.groups]}`
                .toLocaleLowerCase(locale)
                .includes(query),
    );
    return (
        <section className="feature-catalog">
            <div className="page-heading">
                <div>
                    <h1>{t.title}</h1>
                    <p>{t.intro}</p>
                </div>
                <a className="button secondary" href="/dashboard/">
                    {t.dashboard}
                </a>
            </div>
            <p className="feature-note">{t.session}</p>
            <label className="catalog-search">
                {t.search}
                <input
                    type="search"
                    value={search}
                    onChange={(event) => setSearch(event.target.value)}
                />
            </label>
            {!entries.length && <p role="status">{t.empty}</p>}
            {Object.entries(t.groups).map(([group, title]) => {
                const items = entries.filter((item) => item.group === group);
                if (!items.length) return null;
                return (
                    <section key={group} aria-labelledby={`catalog-${group}`}>
                        <h2 id={`catalog-${group}`}>{title}</h2>
                        <ul className="catalog-grid">
                            {items.map((item) => (
                                <li key={item.id}>
                                    <h3>{item.title[locale]}</h3>
                                    <a
                                        href={item.href}
                                        aria-label={`${item.title[locale]}: ${t.open}`}
                                    >
                                        {t.open} <span aria-hidden="true">↗</span>
                                    </a>
                                    {item.modern && (
                                        <Link
                                            to={item.modern}
                                            aria-label={`${item.title[locale]}: ${t.current}`}
                                        >
                                            {t.current}
                                        </Link>
                                    )}
                                </li>
                            ))}
                        </ul>
                    </section>
                );
            })}
        </section>
    );
}
