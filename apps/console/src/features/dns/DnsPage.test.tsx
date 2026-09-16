// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { DnsPage } from './DnsPage';
import { validNameServer } from './contracts';
import type { DnsRecord, Resolvers } from './contracts';
const resolvers: Resolvers = {
    defaultResolver: 'dhcp',
    customResolverMode: 'default',
    dhcpNameServers: ['192.168.1.1'],
    customNameServers: ['1.1.1.1'],
};
const record: DnsRecord = {
    name: 'printer.home',
    builtin: false,
    hidden: false,
    ipAddress: '192.168.1.30',
    ip6Address: null,
    vpnIpAddress: '10.8.0.30',
    vpnIp6Address: 'fd00::30',
};
const builtin: DnsRecord = { ...record, name: 'eblocker.box', builtin: true };
const second: DnsRecord = {
    ...record,
    name: 'nas.home',
    ipAddress: '192.168.1.40',
    vpnIpAddress: '10.8.0.40',
};
async function connected(
    handler?: (path: string, options?: RequestInit) => Response | Promise<Response> | undefined,
) {
    const defaults: Record<string, unknown> = {
        '/dns/status': true,
        '/dns/config/resolvers': resolvers,
        '/dns/config/records': [builtin, record, second],
        '/dns/stats?hours=4': { nameServerStats: [] },
    };
    const transport = vi.fn<typeof fetch>().mockImplementation((url, options) => {
        const path = String(url).replace('/api/adminconsole', '');
        return Promise.resolve(
            path.includes('/authentication/')
                ? json(token())
                : (handler?.(path, options) ?? json(defaults[path])),
        );
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
function mutations(transport: ReturnType<typeof vi.fn<typeof fetch>>) {
    return transport.mock.calls.filter(([, options]) => options?.method !== 'GET');
}

it.each([
    ['1.1.1.1', true],
    ['2001:db8::53', true],
    ['tcp:[2001:db8::53]:853', true],
    ['udp:1.1.1.1:53', true],
    ['https://dns.example/query', false],
    ['dns.example', false],
    ['udp:999.1.1.1:53', false],
    ['tcp:[2001:db8::53]:65536', false],
    ['udp:1.1.1.1:0', false],
])('validates NameServer.parse syntax %s', (value, valid) =>
    expect(validNameServer(String(value))).toBe(valid),
);

it.each(['de', 'en'] as const)(
    'shows actual records, empty statistics and non-editable builtins in %s',
    async (locale) => {
        const { client, transport } = await connected();
        render(<DnsPage client={client} locale={locale} />);
        expect(await screen.findByRole('rowheader', { name: 'printer.home' })).toBeInTheDocument();
        expect(
            screen.getByRole('rowheader', { name: 'eblocker.box' }).closest('tr'),
        ).toHaveTextContent(locale === 'de' ? 'Systemeintrag' : 'System entry');
        expect(
            within(
                screen.getByRole('rowheader', { name: 'eblocker.box' }).closest('tr')!,
            ).queryByRole('button'),
        ).not.toBeInTheDocument();
        expect(
            await screen.findByText(
                locale === 'de'
                    ? 'Noch keine DNS-Statistik verfügbar.'
                    : 'No DNS statistics available yet.',
            ),
        ).toBeInTheDocument();
        expect(mutations(transport)).toHaveLength(0);
    },
);

it('requires DNS status confirmation, applies compare-and-set and reads back the result', async () => {
    const user = userEvent.setup();
    let enabled = true;
    let body: unknown;
    const { client, transport } = await connected((path, options) => {
        if (path !== '/dns/status') return;
        if (options?.method === 'PATCH') {
            body = JSON.parse(String(options.body));
            enabled = (body as { value: boolean }).value;
        }
        return json(enabled);
    });
    render(<DnsPage client={client} locale="en" />);
    await user.click(await screen.findByRole('button', { name: 'Disable DNS' }));
    expect(mutations(transport)).toHaveLength(0);
    expect(screen.getByRole('dialog')).toHaveTextContent('interrupt connections');
    await user.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Confirm change' }),
    );
    await screen.findByText('Saved and confirmed by reading the current state.');
    expect(body).toEqual({ expected: true, value: false });
    expect(screen.getByRole('button', { name: 'Enable DNS' })).toBeEnabled();
});

it('validates custom DNS, preserves server order and supports Tor without enabling DNS automatically', async () => {
    const user = userEvent.setup();
    let current = structuredClone(resolvers);
    const bodies: unknown[] = [];
    const { client, transport } = await connected((path, options) => {
        if (path !== '/dns/config/resolvers') return;
        if (options?.method === 'PATCH') {
            const body = JSON.parse(String(options.body));
            bodies.push(body);
            current = body.value;
        }
        return json(current);
    });
    render(<DnsPage client={client} locale="en" />);
    const panel = within(await screen.findByRole('region', { name: 'DNS servers' }));
    const text = await panel.findByLabelText('Custom DNS servers, one per line');
    await user.selectOptions(panel.getByLabelText('Resolve names using'), 'custom');
    fireEvent.change(text, { target: { value: 'dns.example' } });
    await user.click(panel.getByRole('button', { name: 'Review change' }));
    expect(panel.getByRole('alert')).toHaveTextContent('Check IP addresses');
    expect(mutations(transport)).toHaveLength(0);
    fireEvent.change(text, { target: { value: 'tcp:[2001:db8::53]:853\n1.1.1.1' } });
    await user.selectOptions(panel.getByLabelText('Custom DNS server selection'), 'round_robin');
    await user.click(panel.getByRole('button', { name: 'Review change' }));
    await user.click(panel.getByRole('button', { name: 'Confirm change' }));
    await panel.findByText('Saved and confirmed by reading the current state.');
    expect(bodies[0]).toMatchObject({
        expected: resolvers,
        value: {
            customNameServers: ['tcp:[2001:db8::53]:853', '1.1.1.1'],
            customResolverMode: 'round_robin',
            dhcpNameServers: ['192.168.1.1'],
        },
    });
    await user.selectOptions(panel.getByLabelText('Resolve names using'), 'tor');
    await user.click(panel.getByRole('button', { name: 'Review change' }));
    await user.click(panel.getByRole('button', { name: 'Confirm change' }));
    await waitFor(() => expect(bodies).toHaveLength(2));
    expect(bodies[1]).toMatchObject({ value: { defaultResolver: 'tor' } });
    expect(
        mutations(transport).every(([url]) => String(url).endsWith('/dns/config/resolvers')),
    ).toBe(true);
});

it('preserves unrelated records and all VPN fields when editing, and never sends builtin records as replacements', async () => {
    const user = userEvent.setup();
    let records = [builtin, record, second];
    let body: unknown;
    const { client } = await connected((path, options) => {
        if (path !== '/dns/config/records') return;
        if (options?.method === 'PATCH') {
            body = JSON.parse(String(options.body));
            records = [builtin, ...(body as { value: DnsRecord[] }).value];
        }
        return json(records);
    });
    render(<DnsPage client={client} locale="en" />);
    await user.click(await screen.findByRole('button', { name: 'Edit: printer.home' }));
    const form = within(screen.getByRole('form', { name: 'Edit local DNS name' }));
    fireEvent.change(form.getByLabelText('IPv4 address'), { target: { value: '192.168.1.31' } });
    await user.click(form.getByRole('button', { name: 'Review change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm change' }));
    await screen.findByText('Saved and confirmed by reading the current state.');
    expect(body).toEqual({
        expected: [builtin, record, second],
        value: [second, { ...record, ipAddress: '192.168.1.31' }],
    });
    expect(screen.getByRole('rowheader', { name: 'printer.home' }).closest('tr')).toHaveTextContent(
        '10.8.0.30, fd00::30',
    );
});

it('rejects duplicate records and confirms a targeted delete', async () => {
    const user = userEvent.setup();
    let records = [builtin, record, second];
    let body: unknown;
    const { client, transport } = await connected((path, options) => {
        if (path !== '/dns/config/records') return;
        if (options?.method === 'PATCH') {
            body = JSON.parse(String(options.body));
            records = [builtin, ...(body as { value: DnsRecord[] }).value];
        }
        return json(records);
    });
    render(<DnsPage client={client} locale="en" />);
    await user.click(await screen.findByRole('button', { name: 'Add local name' }));
    const form = within(screen.getByRole('form', { name: 'Edit local DNS name' }));
    fireEvent.change(form.getByLabelText('Name'), { target: { value: 'PRINTER.home' } });
    fireEvent.change(form.getByLabelText('IPv6 address'), { target: { value: '2001:db8::3' } });
    await user.click(form.getByRole('button', { name: 'Review change' }));
    expect(form.getByRole('alert')).toHaveTextContent('unique name');
    expect(mutations(transport)).toHaveLength(0);
    await user.click(form.getByRole('button', { name: 'Cancel' }));
    await user.click(screen.getByRole('button', { name: 'Delete: printer.home' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('printer.home');
    expect(mutations(transport)).toHaveLength(0);
    await user.click(screen.getByRole('button', { name: 'Permanently delete entry' }));
    await screen.findByText('Saved and confirmed by reading the current state.');
    expect(body).toEqual({ expected: [builtin, record, second], value: [second] });
});

it('blocks a stale resolver confirmation and loads the changed settings', async () => {
    const user = userEvent.setup();
    let reads = 0;
    const { client, transport } = await connected((path) =>
        path === '/dns/config/resolvers'
            ? json(++reads === 1 ? resolvers : { ...resolvers, defaultResolver: 'tor' })
            : undefined,
    );
    render(<DnsPage client={client} locale="en" />);
    const panel = within(await screen.findByRole('region', { name: 'DNS servers' }));
    await user.selectOptions(await panel.findByLabelText('Resolve names using'), 'custom');
    await user.click(panel.getByRole('button', { name: 'Review change' }));
    await user.click(panel.getByRole('button', { name: 'Confirm change' }));
    expect(await panel.findByRole('alert')).toHaveTextContent('settings changed');
    await waitFor(() => expect(panel.getByLabelText('Resolve names using')).toHaveValue('tor'));
    expect(mutations(transport)).toHaveLength(0);
});

it('reads back a rejected record PATCH, does not lose entries and does not report success', async () => {
    const user = userEvent.setup();
    let reads = 0;
    const { client } = await connected((path, options) =>
        path === '/dns/config/records'
            ? options?.method === 'PATCH'
                ? json({}, 409)
                : (++reads, json([builtin, record, second]))
            : undefined,
    );
    render(<DnsPage client={client} locale="de" />);
    await user.click(await screen.findByRole('button', { name: 'Löschen: printer.home' }));
    await user.click(screen.getByRole('button', { name: 'Eintrag endgültig löschen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('zwischenzeitlich geändert');
    await waitFor(() => expect(reads).toBeGreaterThanOrEqual(3));
    expect(screen.getByRole('rowheader', { name: 'printer.home' })).toBeInTheDocument();
    expect(
        screen.queryByText('Gespeichert und durch erneutes Lesen bestätigt.'),
    ).not.toBeInTheDocument();
});

it('clears cache only after confirmation and handles a void response', async () => {
    const user = userEvent.setup();
    const { client, transport } = await connected((path) =>
        path === '/dns/cache' ? new Response(null, { status: 204 }) : undefined,
    );
    render(<DnsPage client={client} locale="en" />);
    await user.click(screen.getByRole('button', { name: 'Clear cache' }));
    expect(mutations(transport)).toHaveLength(0);
    await user.click(
        within(screen.getByRole('dialog')).getByRole('button', { name: 'Clear cache' }),
    );
    expect(
        await screen.findByText('The server confirmed that the DNS cache was cleared.'),
    ).toBeInTheDocument();
    expect(mutations(transport)).toHaveLength(1);
    expect(mutations(transport)[0][1]?.method).toBe('DELETE');
});

it('isolates invalid responses without inventing enabled status or hiding healthy records', async () => {
    const { client } = await connected((path) =>
        path === '/dns/status' ? json({ enabled: true }) : undefined,
    );
    render(<DnsPage client={client} locale="en" />);
    const panel = within(screen.getByRole('region', { name: 'DNS service' }));
    await panel.findByRole('alert');
    expect(panel.queryByRole('button', { name: 'Disable DNS' })).not.toBeInTheDocument();
    expect(await screen.findByRole('rowheader', { name: 'printer.home' })).toBeInTheDocument();
});

it('aborts all outstanding DNS loads on navigation', async () => {
    const signals: AbortSignal[] = [];
    const finish: ((value: Response) => void)[] = [];
    const { client } = await connected(
        (_path, options) =>
            new Promise((resolve) => {
                signals.push(options!.signal!);
                finish.push(resolve);
            }),
    );
    const view = render(<DnsPage client={client} locale="en" />);
    await waitFor(() => expect(signals).toHaveLength(4));
    view.unmount();
    expect(signals.every((signal) => signal.aborted)).toBe(true);
    await act(async () => finish.forEach((resolve) => resolve(json({}))));
});

it('keeps omitted nullable fields out of expected and accepts omission in a saved new record', async () => {
    const user = userEvent.setup();
    let records: DnsRecord[] = [
        { name: 'router.home', builtin: false, hidden: false, ipAddress: '192.168.1.1' },
    ];
    let request: { expected: DnsRecord[]; value: DnsRecord[] } | undefined;
    const { client } = await connected((path, options) => {
        if (path !== '/dns/config/records') return;
        if (options?.method === 'PATCH') {
            request = JSON.parse(String(options.body));
            records = request!.value.map(
                (record) =>
                    Object.fromEntries(
                        Object.entries(record).filter(([, value]) => value !== null),
                    ) as DnsRecord,
            );
        }
        return json(records);
    });
    render(<DnsPage client={client} locale="en" />);
    await screen.findByRole('rowheader', { name: 'router.home' });
    await user.click(screen.getByRole('button', { name: 'Add local name' }));
    const form = within(screen.getByRole('form', { name: 'Edit local DNS name' }));
    fireEvent.change(form.getByLabelText('Name'), { target: { value: 'new.home' } });
    fireEvent.change(form.getByLabelText('IPv4 address'), { target: { value: '192.168.1.50' } });
    await user.click(form.getByRole('button', { name: 'Review change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm change' }));
    await screen.findByText('Saved and confirmed by reading the current state.');
    expect(request?.expected).toEqual([
        { name: 'router.home', builtin: false, hidden: false, ipAddress: '192.168.1.1' },
    ]);
    expect(screen.getByRole('rowheader', { name: 'new.home' }).closest('tr')).toHaveTextContent(
        '192.168.1.50',
    );
});

it('accepts an omitted customResolverMode from the real Java serializer', async () => {
    const user = userEvent.setup();
    const { customResolverMode: _mode, ...wire } = resolvers;
    let current: Resolvers = wire;
    const { client } = await connected((path, options) => {
        if (path !== '/dns/config/resolvers') return;
        if (options?.method === 'PATCH') {
            const change = JSON.parse(String(options.body));
            expect(change.expected).not.toHaveProperty('customResolverMode');
            current = change.value;
        }
        return json(current);
    });
    render(<DnsPage client={client} locale="en" />);
    const panel = within(screen.getByRole('region', { name: 'DNS servers' }));
    await user.selectOptions(await panel.findByLabelText('Resolve names using'), 'tor');
    await user.click(panel.getByRole('button', { name: 'Review change' }));
    await user.click(panel.getByRole('button', { name: 'Confirm change' }));
    await panel.findByText('Saved and confirmed by reading the current state.');
    expect(current.customResolverMode).toBe('default');
});
