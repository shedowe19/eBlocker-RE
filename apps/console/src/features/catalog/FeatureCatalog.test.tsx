// SPDX-License-Identifier: EUPL-1.2
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { FeatureCatalog } from './FeatureCatalog';
import catalog from './catalog.json';

// These paths were independently checked against settings/app/routes/*.states.js.
const retained = [
    ['Blockierungsanalyse', '/settings/#!/filter/analysis'],
    ['Erweiterte Filtereinstellungen', '/settings/#!/filter/advanced'],
    ['Domain-Ausnahmen', '/settings/#!/https/trusteddomains'],
    ['HTTPS-Verbindungsfehler', '/settings/#!/https/fails'],
    ['Verbindungen aufzeichnen', '/settings/#!/https/manualrecording'],
    ['Mobiler Fernzugriff', '/settings/#!/mobile'],
    ['Lokale DNS-Einträge', '/settings/#!/dns/local'],
    ['Zeit, Sprache und Zeitzone', '/settings/#!/system/locale'],
    ['Sicherung und Wiederherstellung', '/settings/#!/system/backup'],
    ['Ereignisse', '/settings/#!/system/events'],
    ['Diagnoseberichte', '/settings/#!/system/diagnostics'],
    ['Zurücksetzen', '/settings/#!/system/reset'],
    ['Registrierung und Lizenz', '/settings/#!/home/license'],
    ['Netzwerkdiagnose', '/settings/#!/doctor'],
    ['Open-Source-Lizenzen aller Komponenten', '/settings/#!/licenses'],
] as const;

describe('feature preservation catalog', () => {
    it.each(['de', 'en'] as const)(
        'renders every visible catalog entry with accessible translated link names in %s',
        (locale) => {
            render(
                <MemoryRouter>
                    <FeatureCatalog locale={locale} />
                </MemoryRouter>,
            );
            for (const entry of catalog.filter((item) => item.visible)) {
                const label = `${entry.title[locale]}: ${locale === 'de' ? 'Vollständige Einstellungen öffnen' : 'Open full settings'}`;
                expect(screen.getByRole('link', { name: label })).toHaveAttribute(
                    'href',
                    entry.href,
                );
                expect(
                    screen.getByRole('heading', { level: 3, name: entry.title[locale] }),
                ).toBeInTheDocument();
            }
            expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(35);
            expect(
                screen.queryByRole('heading', {
                    name:
                        locale === 'de'
                            ? 'Einrichtung und Systemabläufe'
                            : 'Setup and system flows',
                }),
            ).not.toBeInTheDocument();
        },
    );

    it('retains exact full-settings links for features whose new implementation is incomplete', () => {
        render(
            <MemoryRouter>
                <FeatureCatalog locale="de" />
            </MemoryRouter>,
        );
        for (const [title, href] of retained) {
            expect(
                screen.getByRole('link', { name: `${title}: Vollständige Einstellungen öffnen` }),
            ).toHaveAttribute('href', href);
        }
        expect(screen.getByRole('link', { name: 'Geräte-Dashboard öffnen' })).toHaveAttribute(
            'href',
            '/dashboard/',
        );
    });

    it('searches German labels and categories case-insensitively and restores everything when cleared', async () => {
        const user = userEvent.setup();
        render(
            <MemoryRouter>
                <FeatureCatalog locale="de" />
            </MemoryRouter>,
        );
        const input = screen.getByRole('searchbox', { name: 'Funktionen suchen' });
        await user.type(input, '  ZERTIFIKATE ');
        expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(1);
        expect(
            screen.getByRole('heading', { name: 'Zertifikate und Vertrauensstellung' }),
        ).toBeInTheDocument();
        await user.clear(input);
        await user.type(input, 'FAMILIE');
        expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(3);
        await user.clear(input);
        expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(35);
    });

    it('searches English titles and gives a translated empty state', async () => {
        const user = userEvent.setup();
        const { rerender } = render(
            <MemoryRouter>
                <FeatureCatalog locale="en" />
            </MemoryRouter>,
        );
        await user.type(screen.getByRole('searchbox', { name: 'Search features' }), 'BACKUP');
        expect(screen.getByRole('heading', { name: 'Backup and restore' })).toBeInTheDocument();
        expect(screen.getAllByRole('heading', { level: 3 })).toHaveLength(1);
        await user.clear(screen.getByRole('searchbox'));
        await user.type(screen.getByRole('searchbox'), 'no-such-feature');
        expect(screen.getByRole('status')).toHaveTextContent('No matching feature found.');
        rerender(
            <MemoryRouter>
                <FeatureCatalog locale="de" />
            </MemoryRouter>,
        );
        expect(screen.getByRole('status')).toHaveTextContent('Keine passende Funktion gefunden.');
    });

    it('keeps modern routes alongside the full settings rather than replacing the complete feature', () => {
        render(
            <MemoryRouter>
                <FeatureCatalog locale="en" />
            </MemoryRouter>,
        );
        const heading = screen.getByRole('heading', { name: 'OpenVPN connections' });
        const row = heading.closest('li');
        expect(row).not.toBeNull();
        expect(
            within(row!).getByRole('link', { name: 'OpenVPN connections: Open in this interface' }),
        ).toHaveAttribute('href', '/vpn');
        expect(
            within(row!).getByRole('link', { name: 'OpenVPN connections: Open full settings' }),
        ).toHaveAttribute('href', '/settings/#!/anonymization/vpn/');
    });

    it('uses only same-origin static routes with no token query strings or credentials', () => {
        render(
            <MemoryRouter>
                <FeatureCatalog locale="en" />
            </MemoryRouter>,
        );
        for (const link of screen.getAllByRole('link')) {
            const href = link.getAttribute('href')!;
            expect(href).toMatch(/^\/(?!\/)/);
            expect(href).not.toMatch(/[?\\@]|(?:token|password|authorization)=/i);
            expect(new URL(href, 'https://eblocker.test').origin).toBe('https://eblocker.test');
        }
        expect(new Set(catalog.map((entry) => entry.id)).size).toBe(catalog.length);
        for (const entry of catalog) {
            expect(entry.href).toMatch(/^\/settings\/#!\//);
            expect(entry.states.length).toBeGreaterThan(0);
        }
    });
});
