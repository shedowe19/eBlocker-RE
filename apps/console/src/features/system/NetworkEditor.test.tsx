// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { NetworkEditor } from './NetworkEditor';
import { Ipv6Editor } from './Ipv6Editor';
import { networkValidation } from './networkEditing';
import type { NetworkConfiguration } from './networkEditing';
const configuration: NetworkConfiguration = {
    automatic: false,
    expertMode: true,
    dhcp: true,
    dnsServer: true,
    ipAddress: '192.168.1.10',
    networkMask: '255.255.255.0',
    gateway: '192.168.1.1',
    nameServerPrimary: '192.168.1.1',
    nameServerSecondary: null,
    dhcpRangeFirst: '192.168.1.20',
    dhcpRangeLast: '192.168.1.200',
    dhcpLeaseTime: 600,
    ipFixedByDefault: false,
    vpnIpAddress: '10.8.0.1',
    globalIp6AddressAvailable: false,
    advisedNameServer: '192.168.1.10',
    rebootNecessary: false,
    revision: 'configuration-1',
    pendingReboot: false,
    pendingConfiguration: null,
};
async function connected(
    handler: (path: string, options?: RequestInit) => Response | Promise<Response>,
) {
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation((url, options) =>
            Promise.resolve(
                String(url).includes('/authentication/')
                    ? json(token())
                    : handler(String(url).replace('/api/adminconsole', ''), options),
            ),
        );
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
const writes = (transport: ReturnType<typeof vi.fn<typeof fetch>>) =>
    transport.mock.calls.filter(
        ([, init]) =>
            ['PATCH', 'POST', 'PUT'].includes(init?.method ?? '') &&
            !String(init?.body).includes('currentPassword'),
    );

it.each([
    [{ networkMask: '255.0.255.0' }, 'mask'],
    [{ gateway: '192.168.2.1' }, 'subnet'],
    [{ dhcpRangeFirst: '192.168.1.201' }, 'range'],
    [{ dhcpRangeLast: '192.168.2.5' }, 'subnet'],
    [{ ipAddress: 'invalid' }, 'address'],
    [{ nameServerSecondary: '2001:db8::53' }, undefined],
    [{ automatic: true, ipAddress: null }, undefined],
] as const)('validates the actual network constraints %j', (change, error) =>
    expect(networkValidation({ ...configuration, ...change })).toBe(error),
);

it('uses revisioned PATCH, separates planned and active addresses, and requires a second restart confirmation', async () => {
    const user = userEvent.setup();
    let current = structuredClone(configuration);
    let submitted: unknown;
    const { client, transport } = await connected((path, init) => {
        if (path === '/network/dhcpstate') return json(true);
        if (path === '/systemstatus/reboot') return new Response(null, { status: 204 });
        if (init?.method === 'PATCH') {
            submitted = JSON.parse(String(init.body));
            const body = submitted as { expected: unknown; value: typeof configuration };
            current = {
                ...current,
                revision: 'configuration-2',
                pendingReboot: true,
                rebootNecessary: true,
                pendingConfiguration: { ...configuration, ...body.value },
            };
        }
        return json(current);
    });
    render(<NetworkEditor client={client} locale="en" close={() => {}} updated={() => {}} />);
    fireEvent.change(await screen.findByLabelText('eBlocker IPv4 address'), {
        target: { value: '192.168.1.11' },
    });
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    expect(transport.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(false);
    await user.click(screen.getByRole('button', { name: 'Confirm network change' }));
    expect(await screen.findByText(/eBlocker accepted the configuration/)).toBeInTheDocument();
    expect(submitted).toMatchObject({
        expected: { revision: 'configuration-1', ipAddress: '192.168.1.10' },
        value: { ipAddress: '192.168.1.11', dnsServer: true },
    });
    expect((submitted as { value: unknown }).value).not.toHaveProperty('vpnIpAddress');
    expect((submitted as { value: unknown }).value).not.toHaveProperty('pendingConfiguration');
    expect(screen.getByText('192.168.1.11')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled();
    expect(transport.mock.calls.some(([url]) => String(url).endsWith('/systemstatus/reboot'))).toBe(
        false,
    );
    await user.click(screen.getByRole('button', { name: 'Restart eBlocker' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('Restarting interrupts networking');
    await user.click(screen.getByRole('button', { name: 'Restart now' }));
    await screen.findByText('Restart requested. Wait and check reachability.');
    expect(
        transport.mock.calls.filter(([url]) => String(url).endsWith('/systemstatus/reboot')),
    ).toHaveLength(1);
});

it('restores durable pending settings on remount and prevents another IPv4 change', async () => {
    const pending = {
        ...configuration,
        pendingReboot: true,
        pendingConfiguration: { ...configuration, ipAddress: '192.168.1.12' },
    };
    const { client } = await connected((path) =>
        json(path === '/network/dhcpstate' ? true : pending),
    );
    render(<NetworkEditor client={client} locale="de" close={() => {}} updated={() => {}} />);
    expect(await screen.findByText('192.168.1.12')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Änderung prüfen' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'eBlocker neu starten' })).toBeEnabled();
});

it('rejects a changed revision even when runtime addresses have not changed', async () => {
    const user = userEvent.setup();
    let reads = 0;
    const { client, transport } = await connected((path) =>
        json(
            path === '/network/dhcpstate'
                ? true
                : {
                      ...configuration,
                      revision: ++reads > 1 ? 'configuration-2' : 'configuration-1',
                  },
        ),
    );
    render(<NetworkEditor client={client} locale="en" close={() => {}} updated={() => {}} />);
    fireEvent.change(await screen.findByLabelText('eBlocker IPv4 address'), {
        target: { value: '192.168.1.11' },
    });
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm network change' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('configuration changed');
    expect(transport.mock.calls.some(([, init]) => init?.method === 'PATCH')).toBe(false);
    await waitFor(() =>
        expect(screen.getByLabelText('eBlocker IPv4 address')).toHaveValue(configuration.ipAddress),
    );
});

it('requires verified external DHCP before switching to automatic and reports discovery without inventing absence', async () => {
    const user = userEvent.setup();
    const { client, transport } = await connected((path) =>
        json(
            path === '/network/dhcpstate'
                ? true
                : path === '/network/dhcpservers'
                  ? []
                  : configuration,
        ),
    );
    render(<NetworkEditor client={client} locale="en" close={() => {}} updated={() => {}} />);
    await user.selectOptions(await screen.findByLabelText('Network mode'), 'auto');
    expect(screen.getByRole('button', { name: 'Review change' })).toBeDisabled();
    await user.click(screen.getByRole('checkbox', { name: /verified another active DHCP server/ }));
    expect(screen.getByRole('button', { name: 'Review change' })).toBeEnabled();
    await user.click(screen.getByRole('button', { name: 'Discover DHCP servers' }));
    expect(await screen.findByText(/does not prove that no other DHCP server/)).toBeInTheDocument();
    expect(writes(transport)).toHaveLength(0);
});

it('reads a failed PATCH back instead of claiming success', async () => {
    const user = userEvent.setup();
    let reads = 0;
    const { client } = await connected((path, init) =>
        init?.method === 'PATCH'
            ? json({}, 409)
            : json(path === '/network/dhcpstate' ? true : (++reads, configuration)),
    );
    render(<NetworkEditor client={client} locale="de" close={() => {}} updated={() => {}} />);
    fireEvent.change(await screen.findByLabelText('eBlocker IPv4-Adresse'), {
        target: { value: '192.168.1.11' },
    });
    await user.click(screen.getByRole('button', { name: 'Änderung prüfen' }));
    await user.click(screen.getByRole('button', { name: 'Netzwerkänderung bestätigen' }));
    await screen.findByText(/Die Konfiguration wurde zwischenzeitlich geändert/);
    await waitFor(() => expect(reads).toBeGreaterThanOrEqual(3));
    expect(
        screen.queryByText(/gespeicherte Konfiguration wurde neu gelesen/),
    ).not.toBeInTheDocument();
});

it('changes only IPv6 flags and preserves fresh runtime addresses in expected', async () => {
    const user = userEvent.setup();
    let state = {
        routerAdvertisementsEnabled: true,
        privacyExtensionsEnabled: false,
        localAddresses: ['fe80::1'],
        globalAddresses: ['2001:db8::1'],
    };
    let patch: unknown;
    const { client } = await connected((_path, init) => {
        if (init?.method === 'PATCH') {
            patch = JSON.parse(String(init.body));
            state = { ...state, ...(patch as { value: object }).value };
        }
        return json(state);
    });
    render(<Ipv6Editor client={client} locale="en" close={() => {}} updated={() => {}} />);
    await user.click(await screen.findByRole('checkbox', { name: 'Use IPv6 privacy extensions' }));
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm network change' }));
    await screen.findByText('The saved configuration was read back and confirmed.');
    expect(patch).toEqual({
        expected: { ...state, privacyExtensionsEnabled: false },
        value: { routerAdvertisementsEnabled: true, privacyExtensionsEnabled: true },
    });
});

it('aborts a pending network load on unmount', async () => {
    let signal: AbortSignal | undefined | null;
    let finish!: (value: Response) => void;
    const { client } = await connected((path, init) =>
        path.endsWith('/dhcpstate')
            ? json(true)
            : new Promise((resolve) => {
                  signal = init?.signal;
                  finish = resolve;
              }),
    );
    const view = render(
        <NetworkEditor client={client} locale="en" close={() => {}} updated={() => {}} />,
    );
    await waitFor(() => expect(signal).toBeDefined());
    view.unmount();
    expect(signal?.aborted).toBe(true);
    await act(async () => finish(json(configuration)));
});

it('accepts actual NON_NULL responses and keeps an in-place save visibly confirmed', async () => {
    const user = userEvent.setup();
    const {
        pendingConfiguration: _pending,
        nameServerSecondary: _secondary,
        ...wire
    } = configuration;
    let current = wire;
    const { client } = await connected((path, options) => {
        if (path === '/network/dhcpstate') return json(true);
        if (options?.method === 'PATCH') {
            const change = JSON.parse(String(options.body));
            expect(change.expected).not.toHaveProperty('pendingConfiguration');
            expect(change.expected).not.toHaveProperty('nameServerSecondary');
            current = {
                ...current,
                dhcpLeaseTime: change.value.dhcpLeaseTime,
                revision: 'configuration-2',
            };
        }
        return json(current);
    });
    render(<NetworkEditor client={client} locale="en" close={() => {}} updated={() => {}} />);
    await user.selectOptions(await screen.findByLabelText('DHCP lease time in seconds'), '1800');
    await user.click(screen.getByRole('button', { name: 'Review change' }));
    await user.click(screen.getByRole('button', { name: 'Confirm network change' }));
    expect(
        await screen.findByText('The saved configuration was read back and confirmed.'),
    ).toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('DHCP lease time in seconds')).toBeEnabled());
    expect(
        screen.getByText('The saved configuration was read back and confirmed.'),
    ).toBeInTheDocument();
});

it('rejects an incomplete pending-state response instead of enabling changes', async () => {
    const { client } = await connected((path) =>
        json(path.endsWith('/dhcpstate') ? true : { ...configuration, pendingReboot: true }),
    );
    render(<NetworkEditor client={client} locale="en" close={() => {}} updated={() => {}} />);
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be read');
    expect(screen.queryByRole('button', { name: 'Review change' })).not.toBeInTheDocument();
});
