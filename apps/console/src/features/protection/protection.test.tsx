// SPDX-License-Identifier: EUPL-1.2
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { ProtectionPage, HttpsPage } from './index';

const app = {
    id: 17,
    name: 'Video App',
    description: { de: 'Video-Kompatibilität', en: 'Video compatibility' },
    enabled: false,
    hidden: false,
    builtin: true,
    whitelistedDomainsIps: ['video.example', '2001:db8::7'],
};
const list = {
    id: 3,
    name: { de: 'Werbeliste', en: 'Advertising list' },
    description: { de: 'Werbung', en: 'Advertising' },
    enabled: true,
    type: 'DOMAIN',
    category: 'ADS',
    providedByEblocker: true,
    updateStatus: 'READY',
};
const certificate = {
    distinguishedName: { commonName: 'eBlocker Test CA' },
    fingerprintSha256: 'AA:BB:CC:DD',
    notBefore: Date.UTC(2020, 0, 1),
    notAfter: Date.UTC(2099, 0, 1),
};
type Handler = (path: string, init?: RequestInit) => Response | Promise<Response>;
async function connected(handler: Handler) {
    const transport = vi.fn<typeof fetch>().mockImplementation((url, init) => {
        const path = String(url).replace('/api/adminconsole', '');
        return Promise.resolve(
            path === '/authentication/token/ADMINCONSOLE' ? json(token()) : handler(path, init),
        );
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
function defaultResponse(path: string) {
    if (path === '/api/blockers/') return json([list]);
    if (path === '/trustedapps/all') return json([app]);
    if (path === '/trustedapps/id/17') return json(app);
    if (path === '/ssl/status') return json(false);
    if (path === '/ssl/rootca') return json(certificate);
    return json({}, 404);
}

describe('protection overview', () => {
    it('loads the authenticated global list route, translates metadata and filters by category', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path === '/api/blockers/'
                ? json([
                      list,
                      {
                          ...list,
                          id: 4,
                          name: { en: 'Malware list' },
                          category: 'MALWARE',
                          updateStatus: 'UPDATE_FAILED',
                          error: 'Download failed',
                      },
                  ])
                : defaultResponse(path),
        );
        render(<ProtectionPage client={client} locale="de" />);
        expect(await screen.findByText('Werbeliste')).toBeInTheDocument();
        expect(screen.getByText('Aktualisierung fehlgeschlagen')).toBeInTheDocument();
        expect(screen.getByText('Download failed')).toBeInTheDocument();
        const request = transport.mock.calls.find(([path]) => path === '/api/blockers/');
        expect(new Headers(request?.[1]?.headers).get('Authorization')).toBe('Bearer test-token');
        await user.selectOptions(screen.getByRole('combobox', { name: 'Kategorie' }), 'MALWARE');
        expect(screen.queryByText('Werbeliste')).not.toBeInTheDocument();
        expect(screen.getByText('Malware list')).toBeInTheDocument();
        await user.type(screen.getByRole('searchbox', { name: 'Filterlisten suchen' }), 'missing');
        expect(screen.getByRole('status')).toHaveTextContent('Keine passenden Einträge.');
    });

    it('searches visible app exceptions by IPv6 address and omits hidden entries', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path) =>
            path === '/trustedapps/all'
                ? json([
                      app,
                      { ...app, id: 18, name: 'Hidden app', hidden: true },
                      {
                          ...app,
                          id: 19,
                          name: 'Other app',
                          whitelistedDomainsIps: ['other.example'],
                      },
                  ])
                : defaultResponse(path),
        );
        render(<ProtectionPage client={client} locale="en" />);
        await screen.findByText('Video App');
        expect(screen.queryByText('Hidden app')).not.toBeInTheDocument();
        await user.type(
            screen.getByRole('searchbox', { name: 'Search app exceptions' }),
            '2001:DB8::7',
        );
        expect(screen.queryByText('Other app')).not.toBeInTheDocument();
        expect(screen.getByText('Video compatibility')).toBeInTheDocument();
    });

    it.each(['de', 'en'] as const)(
        'requires confirmation and sends only the selected exception ID and string state in %s',
        async (locale) => {
            const user = userEvent.setup();
            let enabled = false;
            const { client, transport } = await connected((path, init) => {
                if (path === '/trustedapps/enable') {
                    enabled = true;
                    return new Response(null, { status: 204 });
                }
                if (path === '/trustedapps/all') return json([{ ...app, enabled }]);
                if (path === '/trustedapps/id/17') return json({ ...app, enabled });
                expect(init?.method).toBe('GET');
                return defaultResponse(path);
            });
            render(<ProtectionPage client={client} locale={locale} />);
            const enableLabel =
                locale === 'de' ? 'Ausnahme aktivieren: Video App' : 'Enable exception: Video App';
            await user.click(await screen.findByRole('button', { name: enableLabel }));
            const dialog = screen.getByRole('dialog');
            expect(transport.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
            expect(
                within(dialog).getByRole('button', {
                    name: locale === 'de' ? 'Abbrechen' : 'Cancel',
                }),
            ).toHaveFocus();
            await user.click(
                within(dialog).getByRole('button', {
                    name: locale === 'de' ? 'Änderung speichern' : 'Save change',
                }),
            );
            expect(
                await screen.findByText(
                    locale === 'de'
                        ? 'Änderung gespeichert und vom Gerät bestätigt.'
                        : 'Change saved and confirmed by the appliance.',
                ),
            ).toBeInTheDocument();
            const request = transport.mock.calls.find(([, init]) => init?.method === 'PUT');
            expect(request?.[0]).toBe('/api/adminconsole/trustedapps/enable');
            expect(JSON.parse(String(request?.[1]?.body))).toEqual({
                id: '17',
                setEnabled: 'true',
            });
            await waitFor(() =>
                expect(
                    screen.getByRole('button', {
                        name:
                            locale === 'de'
                                ? 'Ausnahme deaktivieren: Video App'
                                : 'Disable exception: Video App',
                    }),
                ).toBeEnabled(),
            );
            expect(
                transport.mock.calls.filter(([path]) =>
                    String(path).endsWith('/trustedapps/id/17'),
                ),
            ).toHaveLength(2);
        },
    );

    it('cancels a proposed exception change without submitting a write', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected(defaultResponse);
        render(<ProtectionPage client={client} locale="en" />);
        await user.click(
            await screen.findByRole('button', { name: 'Enable exception: Video App' }),
        );
        await user.click(
            within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel' }),
        );
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(transport.mock.calls.some(([, init]) => init?.method !== 'GET')).toBe(false);
    });

    it('does not claim success when a legacy 204 did not change the selected exception', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path) =>
            path === '/trustedapps/enable'
                ? new Response(null, { status: 204 })
                : defaultResponse(path),
        );
        render(<ProtectionPage client={client} locale="en" />);
        await user.click(
            await screen.findByRole('button', { name: 'Enable exception: Video App' }),
        );
        await user.click(screen.getByRole('button', { name: 'Save change' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The change was sent, but its new state could not be confirmed.',
        );
        expect(
            screen.queryByText('Change saved and confirmed by the appliance.'),
        ).not.toBeInTheDocument();
    });

    it('reports a failed write without optimistically switching the exception', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path) =>
            path === '/trustedapps/enable' ? json({}, 503) : defaultResponse(path),
        );
        render(<ProtectionPage client={client} locale="de" />);
        await user.click(
            await screen.findByRole('button', { name: 'Ausnahme aktivieren: Video App' }),
        );
        await user.click(screen.getByRole('button', { name: 'Änderung speichern' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'der neue Zustand konnte aber nicht bestätigt werden',
        );
        expect(
            screen.queryByRole('button', { name: 'Ausnahme deaktivieren: Video App' }),
        ).not.toBeInTheDocument();
    });

    it('does not write an exception removed after the list was loaded', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path === '/trustedapps/id/17' ? json({}, 404) : defaultResponse(path),
        );
        render(<ProtectionPage client={client} locale="en" />);
        await user.click(
            await screen.findByRole('button', { name: 'Enable exception: Video App' }),
        );
        await user.click(screen.getByRole('button', { name: 'Save change' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The requested data could not be found.',
        );
        expect(transport.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
    });

    it('shows independently failed sources and rejects malformed app state', async () => {
        const { client } = await connected((path) =>
            path === '/trustedapps/all'
                ? json([{ ...app, enabled: 'false' }])
                : defaultResponse(path),
        );
        render(<ProtectionPage client={client} locale="en" />);
        expect(await screen.findByText('Advertising list')).toBeInTheDocument();
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The response from eBlocker could not be read.',
        );
        expect(
            screen.queryByRole('button', { name: 'Enable exception: Video App' }),
        ).not.toBeInTheDocument();
    });

    it('distinguishes empty data from a failed request', async () => {
        const { client } = await connected(() => json([]));
        render(<ProtectionPage client={client} locale="de" />);
        expect(await screen.findByText('Keine Filterlisten gemeldet.')).toBeInTheDocument();
        expect(screen.getByText('Keine sichtbaren App-Ausnahmen vorhanden.')).toBeInTheDocument();
    });
});

describe('HTTPS status and public certificate', () => {
    it.each(['de', 'en'] as const)(
        'displays status and certificate dates and confirms the device-side download effect in %s',
        async (locale) => {
            const user = userEvent.setup();
            const { client, transport } = await connected(defaultResponse);
            render(<HttpsPage client={client} locale={locale} />);
            expect(await screen.findByText('eBlocker Test CA')).toBeInTheDocument();
            expect(screen.getByText('AA:BB:CC:DD')).toBeInTheDocument();
            expect(
                screen.getByText(locale === 'de' ? 'Deaktiviert' : 'Disabled'),
            ).toBeInTheDocument();
            expect(screen.queryByRole('link')).not.toBeInTheDocument();
            await user.click(
                screen.getByRole('button', {
                    name: locale === 'de' ? 'Zertifikat herunterladen' : 'Download certificate',
                }),
            );
            const dialog = screen.getByRole('dialog');
            expect(dialog).toHaveTextContent(
                locale === 'de'
                    ? 'aktiviert eBlocker automatisch die HTTPS-Analyse'
                    : 'automatically enables HTTPS inspection',
            );
            expect(within(dialog).getByRole('link')).toHaveAttribute(
                'href',
                '/api/ssl/caCertificate.crt',
            );
            expect(transport.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(true);
        },
    );

    it('shows a genuinely absent CA without a download link or invented certificate fields', async () => {
        const { client } = await connected((path) =>
            path === '/ssl/rootca' ? json(null) : defaultResponse(path),
        );
        render(<HttpsPage client={client} locale="en" />);
        expect(
            await screen.findByText('No CA certificate has been configured yet.'),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Download certificate' }),
        ).not.toBeInTheDocument();
        expect(screen.queryByText('SHA-256 fingerprint')).not.toBeInTheDocument();
    });

    it('warns about an expired certificate using millisecond timestamps', async () => {
        const { client } = await connected((path) =>
            path === '/ssl/rootca'
                ? json({ ...certificate, notAfter: Date.UTC(2021, 0, 1) })
                : defaultResponse(path),
        );
        render(<HttpsPage client={client} locale="de" />);
        expect(await screen.findByText('Dieses Zertifikat ist abgelaufen.')).toBeInTheDocument();
        expect(screen.getByText(/1\. Januar 2021/)).toBeInTheDocument();
    });

    it('rejects invalid date metadata but keeps a valid HTTPS status visible', async () => {
        const { client } = await connected((path) =>
            path === '/ssl/rootca'
                ? json({ ...certificate, notAfter: 'tomorrow' })
                : defaultResponse(path),
        );
        render(<HttpsPage client={client} locale="en" />);
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The response from eBlocker could not be read.',
        );
        expect(screen.getByText('Disabled')).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Download certificate' }),
        ).not.toBeInTheDocument();
    });

    it('does not claim HTTPS is disabled when its status request failed', async () => {
        const { client } = await connected((path) =>
            path === '/ssl/status' ? json({}, 503) : defaultResponse(path),
        );
        render(<HttpsPage client={client} locale="en" />);
        expect(await screen.findByText('eBlocker Test CA')).toBeInTheDocument();
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'eBlocker could not process the request.',
        );
        expect(screen.queryByText('Disabled')).not.toBeInTheDocument();
    });
});
