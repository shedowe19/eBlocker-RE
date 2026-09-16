// SPDX-License-Identifier: EUPL-1.2
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from './App';
import { ConsoleClient } from './api/client';
import { device, json, token } from './test/fixtures';

// These payloads follow the controller contracts used by the real ConsoleClient adapters.
const payloads: Record<string, unknown> = {
    '/api/adminconsole/devices': [device],
    '/api/adminconsole/systemstatus': {
        executionState: 'RUNNING',
        projectVersion: '4.0.3',
        warnings: [],
        subSystemDetails: [],
    },
    '/api/adminconsole/network': {
        automatic: true,
        dhcp: false,
        dnsServer: true,
        ipAddress: '10.0.17.20',
        gateway: '10.0.17.254',
    },
    '/api/adminconsole/network/ip6': {
        routerAdvertisementsEnabled: true,
        privacyExtensionsEnabled: true,
        localAddresses: ['fe80::20'],
        globalAddresses: ['2001:db8::20'],
    },
    '/api/adminconsole/dns/status': true,
    '/api/adminconsole/dns/config/resolvers': {
        defaultResolver: 'dhcp',
        dhcpNameServers: ['10.0.17.254'],
        customNameServers: [],
    },
    '/api/blockers/': [
        {
            id: 1,
            name: { de: 'Globale Werbeliste', en: 'Global advertising list' },
            enabled: true,
            type: 'DOMAIN',
            category: 'ADS',
            providedByEblocker: true,
        },
    ],
    '/api/adminconsole/trustedapps/all': [],
    '/api/adminconsole/ssl/status': true,
    '/api/adminconsole/ssl/rootca': {
        distinguishedName: { commonName: 'Integration Test CA' },
        fingerprintSha256: 'AA:BB:CC',
        notBefore: Date.UTC(2020, 0, 1),
        notAfter: Date.UTC(2099, 0, 1),
    },
    '/api/adminconsole/wireguard/profiles': { schemaVersion: 1, data: { profiles: [] } },
    '/api/adminconsole/network-agent/status': {
        schemaVersion: 1,
        data: {
            readOnly: true,
            capabilities: {
                readOnly: true,
                wireguard: { kernelFamilyRegistered: true, state: 'registered', management: false },
            },
            interfaces: [
                {
                    index: 2,
                    name: 'eth0',
                    mtu: 1500,
                    up: true,
                    running: true,
                    loopback: false,
                    addresses: [{ prefix: '10.0.17.20/24', family: 'ipv4', scope: 'global' }],
                },
            ],
            routes: [],
        },
    },
};
const pages = [
    {
        path: '/system',
        de: 'System',
        en: 'System',
        headingDe: 'Systemstatus',
        headingEn: 'System status',
        endpoint: '/api/adminconsole/systemstatus',
        marker: '4.0.3',
    },
    {
        path: '/network',
        de: 'Netzwerk',
        en: 'Network',
        headingDe: 'Netzwerk',
        headingEn: 'Network',
        endpoint: '/api/adminconsole/network/ip6',
        marker: 'fe80::20',
    },
    {
        path: '/protection',
        de: 'Schutz',
        en: 'Protection',
        headingDe: 'Schutz und Ausnahmen',
        headingEn: 'Protection and exceptions',
        endpoint: '/api/blockers/',
        marker: 'Globale Werbeliste',
    },
    {
        path: '/https',
        de: 'HTTPS',
        en: 'HTTPS',
        headingDe: 'HTTPS-Analyse',
        headingEn: 'HTTPS inspection',
        endpoint: '/api/adminconsole/ssl/rootca',
        marker: 'Integration Test CA',
    },
    {
        path: '/security',
        de: 'Sicherheit',
        en: 'Security',
        headingDe: 'Anmeldesicherheit',
        headingEn: 'Sign-in security',
        endpoint: undefined,
        marker: 'Für die Verwaltung wird ein Administratorpasswort benötigt.',
    },
    {
        path: '/wireguard',
        de: 'WireGuard',
        en: 'WireGuard',
        headingDe: 'WireGuard',
        headingEn: 'WireGuard',
        endpoint: '/api/adminconsole/network-agent/status',
        marker: 'eth0',
    },
] as const;

beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    localStorage.setItem('eblocker.console.language', 'de');
    window.history.replaceState(null, '', '#/devices');
});

function createTransport(
    override?: (path: string, init?: RequestInit) => Response | Promise<Response> | undefined,
) {
    return vi.fn<typeof fetch>().mockImplementation(async (url, init) => {
        const path = String(url);
        if (path === '/api/adminconsole/authentication/token/ADMINCONSOLE')
            return json(token(true, 'login-token'));
        if (path === '/api/adminconsole/authentication/wait') return json(0);
        if (path === '/api/adminconsole/authentication/login/ADMINCONSOLE')
            return json(token(true, 'authenticated-token'));
        const custom = override?.(path, init);
        if (custom !== undefined) return custom;
        if (Object.hasOwn(payloads, path)) return json(payloads[path]);
        throw new Error(`Unexpected integration request: ${init?.method} ${path}`);
    });
}
async function signIn(user: ReturnType<typeof userEvent.setup>) {
    await user.type(await screen.findByLabelText('Administratorpasswort'), 'integration-password');
    await user.click(screen.getByRole('button', { name: 'Anmelden' }));
    await screen.findByRole('button', { name: 'Abmelden' });
}

describe('authenticated application navigation', () => {
    it('visits all new features using real adapters, maintains active links and focuses the main content', async () => {
        const user = userEvent.setup();
        const errors = vi.spyOn(console, 'error');
        const transport = createTransport();
        render(<App client={new ConsoleClient(transport)} />);
        await signIn(user);
        await screen.findByRole('button', { name: /Arbeitszimmer/ });
        for (const page of pages) {
            const navigation = screen.getByRole('navigation', { name: 'Netzwerkverwaltung' });
            await user.click(within(navigation).getByRole('link', { name: page.de }));
            expect(
                await screen.findByRole('heading', { level: 1, name: page.headingDe }),
            ).toBeInTheDocument();
            expect(await screen.findByText(page.marker)).toBeInTheDocument();
            expect(window.location.hash).toBe(`#${page.path}`);
            expect(within(navigation).getByRole('link', { name: page.de })).toHaveAttribute(
                'aria-current',
                'page',
            );
            expect(navigation.querySelectorAll('[aria-current="page"]')).toHaveLength(1);
            expect(screen.getByRole('main')).toHaveFocus();
            expect(screen.queryByRole('alert')).not.toBeInTheDocument();
            if (page.endpoint)
                expect(transport.mock.calls.some(([url]) => url === page.endpoint)).toBe(true);
        }
        await user.click(
            within(screen.getByRole('navigation')).getByRole('link', {
                name: 'Geräte',
            }),
        );
        expect(await screen.findByRole('button', { name: /Arbeitszimmer/ })).toBeInTheDocument();
        expect(screen.getByRole('main')).toHaveFocus();
        expect(window.location.hash).toBe('#/devices');
        for (const [url, init] of transport.mock.calls.filter(
            ([url]) => !String(url).includes('/authentication/'),
        )) {
            expect(new Headers(init?.headers).get('Authorization'), String(url)).toBe(
                'Bearer authenticated-token',
            );
            expect(init?.method, String(url)).toBe('GET');
        }
        expect(errors.mock.calls).toEqual([]);
        expect(JSON.stringify(localStorage)).not.toMatch(
            /integration-password|authenticated-token/,
        );
        expect(sessionStorage.length).toBe(0);
    });

    it('changes every feature and its navigation between German and English without losing its route or refetching', async () => {
        const user = userEvent.setup();
        const errors = vi.spyOn(console, 'error');
        const transport = createTransport();
        render(<App client={new ConsoleClient(transport)} />);
        await signIn(user);
        for (const page of pages) {
            await user.click(
                within(screen.getByRole('navigation')).getByRole('link', {
                    name: page.de,
                }),
            );
            await screen.findByText(page.marker);
            const requests = transport.mock.calls.length;
            await user.selectOptions(screen.getByRole('combobox', { name: 'Sprache' }), 'en');
            expect(
                screen.getByRole('heading', { level: 1, name: page.headingEn }),
            ).toBeInTheDocument();
            expect(
                within(screen.getByRole('navigation', { name: 'Network management' })).getByRole(
                    'link',
                    { name: page.en },
                ),
            ).toHaveAttribute('aria-current', 'page');
            expect(window.location.hash).toBe(`#${page.path}`);
            expect(document.documentElement.lang).toBe('en');
            expect(localStorage.getItem('eblocker.console.language')).toBe('en');
            expect(transport.mock.calls.length).toBe(requests);
            await user.selectOptions(screen.getByRole('combobox', { name: 'Language' }), 'de');
            expect(
                screen.getByRole('heading', { level: 1, name: page.headingDe }),
            ).toBeInTheDocument();
            expect(document.documentElement.lang).toBe('de');
            expect(transport.mock.calls.length).toBe(requests);
        }
        expect(errors.mock.calls).toEqual([]);
    });

    it.each(pages)(
        'keeps the $path deep link behind login and restores it after authentication',
        async (page) => {
            const user = userEvent.setup();
            window.history.replaceState(null, '', `#${page.path}`);
            const transport = createTransport();
            render(<App client={new ConsoleClient(transport)} />);
            await screen.findByLabelText('Administratorpasswort');
            expect(screen.queryByRole('link', { name: page.de })).not.toBeInTheDocument();
            expect(
                transport.mock.calls.every(([url]) => String(url).includes('/authentication/')),
            ).toBe(true);
            await signIn(user);
            expect(
                await screen.findByRole('heading', { name: page.headingDe, level: 1 }),
            ).toBeInTheDocument();
            expect(await screen.findByText(page.marker)).toBeInTheDocument();
            expect(window.location.hash).toBe(`#${page.path}`);
            expect(transport.mock.calls.some(([url]) => url === '/api/adminconsole/devices')).toBe(
                false,
            );
            expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        },
    );

    it('aborts an outstanding page request when navigation changes and ignores the late response', async () => {
        const user = userEvent.setup();
        let systemSignal: AbortSignal | null | undefined;
        let finish!: (response: Response) => void;
        const transport = createTransport((path, init) => {
            if (path !== '/api/adminconsole/systemstatus') return undefined;
            systemSignal = init?.signal;
            return new Promise((resolve) => {
                finish = resolve;
            });
        });
        render(<App client={new ConsoleClient(transport)} />);
        await signIn(user);
        await user.click(
            within(screen.getByRole('navigation')).getByRole('link', {
                name: 'System',
            }),
        );
        await waitFor(() => expect(systemSignal).toBeDefined());
        await user.click(
            within(screen.getByRole('navigation')).getByRole('link', {
                name: 'Netzwerk',
            }),
        );
        expect(await screen.findByText('fe80::20')).toBeInTheDocument();
        expect(systemSignal?.aborted).toBe(true);
        await act(async () => {
            finish(json({ executionState: 'RUNNING', projectVersion: 'STALE_PAGE_VALUE' }));
        });
        expect(screen.queryByText('STALE_PAGE_VALUE')).not.toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'Netzwerk', level: 1 })).toBeInTheDocument();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('removes private feature data and navigation when a feature request expires the session', async () => {
        const user = userEvent.setup();
        let expired = false;
        const transport = createTransport((path) =>
            expired && path === '/api/adminconsole/ssl/status' ? json({}, 401) : undefined,
        );
        render(<App client={new ConsoleClient(transport)} />);
        await signIn(user);
        await user.click(
            within(screen.getByRole('navigation')).getByRole('link', {
                name: 'HTTPS',
            }),
        );
        await screen.findByText('Integration Test CA');
        expired = true;
        await user.click(screen.getByRole('button', { name: 'Aktualisieren' }));
        expect(
            await screen.findByRole('heading', {
                name: 'Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.',
            }),
        ).toBeInTheDocument();
        expect(screen.queryByText('Integration Test CA')).not.toBeInTheDocument();
        expect(screen.queryByRole('link', { name: 'HTTPS' })).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Zertifikat herunterladen' }),
        ).not.toBeInTheDocument();
    });
});
