// SPDX-License-Identifier: EUPL-1.2
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { NetworkPage, SystemPage } from './index';

const system = {
    executionState: 'RUNNING',
    projectVersion: '4.0.3',
    warnings: ['Disk usage high'],
    subSystemDetails: [
        { name: 'EBLOCKER_CORE', status: 'WARN', order: 9999 },
        { name: 'DATABASE_CLIENT', status: 'OK', order: 2000 },
    ],
    updatingStatus: {
        listsPacketVersion: '20260916093000',
        automaticUpdatesActivated: false,
        updatesAvailable: true,
    },
};
const network = {
    automatic: true,
    dhcp: false,
    dnsServer: true,
    ipAddress: '10.0.17.20',
    gateway: '10.0.17.254',
    networkMask: '255.255.255.0',
    nameServerPrimary: '10.0.17.254',
    nameServerSecondary: null,
};
const ipv6 = {
    routerAdvertisementsEnabled: true,
    privacyExtensionsEnabled: false,
    localAddresses: ['fe80::1234'],
    globalAddresses: ['2001:db8::1234'],
};
const resolvers = {
    defaultResolver: 'custom',
    customResolverMode: 'round_robin',
    dhcpNameServers: ['10.0.17.254'],
    customNameServers: ['2001:db8::53', '192.0.2.53'],
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
function networkHandler(path: string) {
    const values: Record<string, unknown> = {
        '/network': network,
        '/network/ip6': ipv6,
        '/dns/status': false,
        '/dns/config/resolvers': resolvers,
    };
    return json(values[path]);
}

describe('system status', () => {
    it.each(['constructor', 'toString', '__proto__'])(
        'treats %s as an unknown remote state without looking up inherited object properties',
        async (value) => {
            const { client } = await connected(() =>
                json({
                    ...system,
                    executionState: value,
                    subSystemDetails: [{ name: value, status: value }],
                }),
            );
            render(<SystemPage client={client} locale="en" />);
            expect(await screen.findAllByText(`Unknown state: ${value}`)).toHaveLength(2);
            expect(screen.getByRole('rowheader', { name: value })).toBeInTheDocument();
        },
    );

    it('renders real version, translated states, ordered subsystems and server warnings', async () => {
        const { client, transport } = await connected(() => json(system));
        render(<SystemPage client={client} locale="de" />);
        expect(await screen.findByText('4.0.3')).toBeInTheDocument();
        expect(screen.getByText('Läuft')).toBeInTheDocument();
        expect(screen.getByText('Disk usage high')).toBeInTheDocument();
        expect(screen.getByText('20260916093000')).toBeInTheDocument();
        expect(screen.getByText('Deaktiviert')).toBeInTheDocument();
        const rows = within(screen.getByRole('table')).getAllByRole('row');
        expect(rows[1]).toHaveTextContent('Datenbankverbindung');
        expect(rows[2]).toHaveTextContent('eBlocker-Kern');
        expect(rows[2]).toHaveTextContent('Warnung');
        expect(transport.mock.calls[1][0]).toBe('/api/adminconsole/systemstatus');
        expect(new Headers(transport.mock.calls[1][1]?.headers).get('Authorization')).toBe(
            'Bearer test-token',
        );
        expect(transport.mock.calls[1][1]?.method).toBe('GET');
    });

    it('does not invent healthy subsystems, warning counts or versions for partial responses', async () => {
        const { client } = await connected(() => json({ executionState: 'BOOTING' }));
        render(<SystemPage client={client} locale="en" />);
        expect(await screen.findByText('Starting')).toBeInTheDocument();
        expect(screen.getAllByText('Not reported').length).toBeGreaterThan(1);
        expect(screen.queryByText('No warnings reported.')).not.toBeInTheDocument();
        expect(screen.queryByText('No system components reported.')).not.toBeInTheDocument();
        expect(screen.queryByText('Running')).not.toBeInTheDocument();
    });

    it('distinguishes explicitly empty reports and translates update failure', async () => {
        const { client } = await connected(() =>
            json({
                ...system,
                warnings: [],
                subSystemDetails: [],
                updatingStatus: { lastUpdateAttemptFailed: true },
            }),
        );
        render(<SystemPage client={client} locale="en" />);
        expect(await screen.findByText('No warnings reported.')).toBeInTheDocument();
        expect(screen.getByText('No system components reported.')).toBeInTheDocument();
        expect(screen.getByText('The last update attempt failed.')).toBeInTheDocument();
    });

    it('rejects wrong payload shapes instead of showing a healthy state', async () => {
        const { client } = await connected(() => json({ executionState: true }));
        render(<SystemPage client={client} locale="de" />);
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Die Antwort von eBlocker konnte nicht gelesen werden.',
        );
        expect(screen.queryByText('Läuft')).not.toBeInTheDocument();
    });

    it('keeps the last successful report visibly stale and recovers when retried', async () => {
        const user = userEvent.setup();
        let fail = false;
        const { client } = await connected(() => (fail ? json({}, 503) : json(system)));
        render(<SystemPage client={client} locale="en" />);
        await screen.findByText('4.0.3');
        fail = true;
        await user.click(screen.getByRole('button', { name: 'Refresh' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The displayed data could not be refreshed.',
        );
        expect(screen.getByText('4.0.3')).toBeInTheDocument();
        fail = false;
        await user.click(screen.getByRole('button', { name: 'Try again' }));
        await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
    });

    it('aborts an outstanding request when leaving the page', async () => {
        let signal: AbortSignal | undefined | null;
        let finish!: (response: Response) => void;
        const { client } = await connected((_path, init) => {
            signal = init?.signal;
            return new Promise((resolve) => {
                finish = resolve;
            });
        });
        const { unmount } = render(<SystemPage client={client} locale="en" />);
        await waitFor(() => expect(signal).toBeDefined());
        expect(screen.getByRole('status')).toHaveTextContent('Loading data');
        unmount();
        expect(signal?.aborted).toBe(true);
        await act(async () => {
            finish(json(system));
        });
    });

    it('preserves unknown states and escapes remote diagnostic messages', async () => {
        const diagnostic = '<img src=x onerror=alert(1)>';
        const { client } = await connected(() =>
            json({
                ...system,
                executionState: 'MAINTENANCE',
                warnings: [diagnostic, null],
                subSystemDetails: [
                    {
                        name: 'FUTURE_SERVICE',
                        status: 'DEGRADED',
                        msgContext: { error: diagnostic },
                    },
                ],
            }),
        );
        const { container, rerender } = render(<SystemPage client={client} locale="en" />);
        expect(await screen.findByText('Unknown state: MAINTENANCE')).toBeInTheDocument();
        expect(screen.getByText('Unknown state: DEGRADED')).toBeInTheDocument();
        expect(screen.getAllByText(diagnostic)).toHaveLength(2);
        expect(container.querySelector('img')).toBeNull();
        expect(screen.getByText('Warning without a description')).toBeInTheDocument();
        rerender(<SystemPage client={client} locale="de" />);
        expect(screen.getByText('Unbekannter Status: MAINTENANCE')).toBeInTheDocument();
    });
});

describe('network overview', () => {
    it('loads actual read-only endpoints and displays IPv4, IPv6 and DNS state including false', async () => {
        const { client, transport } = await connected(networkHandler);
        render(<NetworkPage client={client} locale="de" />);
        expect(await screen.findByText('2001:db8::1234')).toBeInTheDocument();
        expect(screen.getByText('fe80::1234')).toBeInTheDocument();
        expect(screen.getByText('10.0.17.20')).toBeInTheDocument();
        expect(screen.getByText('2001:db8::53')).toBeInTheDocument();
        expect(screen.getByText('Reihum')).toBeInTheDocument();
        expect(
            within(screen.getByRole('region', { name: 'DNS-Dienst' })).getByText('Deaktiviert'),
        ).toBeInTheDocument();
        expect(screen.queryByText('Erste DHCP-Adresse')).not.toBeInTheDocument();
        expect(transport.mock.calls.slice(1).map(([url]) => url)).toEqual([
            '/api/adminconsole/network',
            '/api/adminconsole/network/ip6',
            '/api/adminconsole/dns/config/resolvers',
            '/api/adminconsole/dns/status',
        ]);
        expect(transport.mock.calls.every(([, init]) => init?.method === 'GET')).toBe(true);
    });

    it('keeps successful sources visible when one source fails without inventing disabled DNS', async () => {
        const { client } = await connected((path) =>
            path === '/dns/status' ? json({}, 503) : networkHandler(path),
        );
        render(<NetworkPage client={client} locale="en" />);
        expect(await screen.findByText('2001:db8::1234')).toBeInTheDocument();
        const status = screen.getByRole('region', { name: 'DNS service' });
        expect(await within(status).findByRole('alert')).toHaveTextContent(
            'eBlocker could not process the request.',
        );
        expect(within(status).queryByText('Disabled')).not.toBeInTheDocument();
        expect(screen.getByText('10.0.17.20')).toBeInTheDocument();
    });

    it('rejects object-shaped IPv6 addresses instead of converting objects into text', async () => {
        const { client } = await connected((path) =>
            path === '/network/ip6'
                ? json({ ...ipv6, globalAddresses: [{ address: '2001:db8::1' }] })
                : networkHandler(path),
        );
        render(<NetworkPage client={client} locale="en" />);
        const section = screen.getByRole('region', { name: 'IPv6 configuration' });
        expect(await within(section).findByRole('alert')).toHaveTextContent(
            'The response from eBlocker could not be read.',
        );
        expect(screen.queryByText('[object Object]')).not.toBeInTheDocument();
        expect(await screen.findByText('10.0.17.20')).toBeInTheDocument();
    });

    it('distinguishes absent from explicitly empty address lists and preserves real DHCP lease values', async () => {
        const { client } = await connected((path) => {
            if (path === '/network/ip6')
                return json({
                    routerAdvertisementsEnabled: false,
                    privacyExtensionsEnabled: false,
                    localAddresses: [],
                });
            if (path === '/network') return json({ ...network, dhcp: true, dhcpLeaseTime: 3600 });
            return networkHandler(path);
        });
        render(<NetworkPage client={client} locale="en" />);
        expect(await screen.findByText('3600 seconds')).toBeInTheDocument();
        const section = screen.getByRole('region', { name: 'IPv6 configuration' });
        expect(within(section).getByText('No entries reported.')).toBeInTheDocument();
        expect(within(section).getByText('Not reported')).toBeInTheDocument();
    });

    it('refreshes every source with the latest values', async () => {
        const user = userEvent.setup();
        let enabled = false;
        const { client, transport } = await connected((path) =>
            path === '/dns/status' ? json(enabled) : networkHandler(path),
        );
        render(<NetworkPage client={client} locale="en" />);
        await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled());
        enabled = true;
        await user.click(screen.getByRole('button', { name: 'Refresh' }));
        const section = screen.getByRole('region', { name: 'DNS service' });
        expect(await within(section).findByText('Enabled')).toBeInTheDocument();
        expect(transport).toHaveBeenCalledTimes(9);
    });
});
