// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { WireGuardManager } from './WireGuardManager';
import {
    protectionObserved,
    profileStatusResponseSchema,
    tunnelObserved,
} from './managementContracts';
import type { ProfileStatus, WireGuardRuntime } from './managementContracts';

const root = '/api/adminconsole/wireguard/profiles';
const secret = '[Interface]\nPrivateKey = PRIVATE-KEY-NEVER-DISPLAY\n';
const plan = {
    applied: false as const,
    killSwitchActive: false as const,
    interfaceAddresses: ['10.1.0.2/32'],
    dns: [],
    defaultRouteIPv4: false,
    defaultRouteIPv6: false,
    endpointExclusions: [],
    leakRisks: [],
    warnings: [],
    peers: [
        {
            publicKey: 'peer-public-key',
            endpoint: '198.51.100.1:51820',
            allowedIPs: ['10.1.0.0/24'],
            hasPresharedKey: true,
        },
    ],
};
function profile(): ProfileStatus {
    return { profileId: 'office', phase: 'imported', plan: structuredClone(plan), runtime: null };
}
function active(): WireGuardRuntime {
    return {
        schemaVersion: 1,
        target: {
            profileId: 'office',
            interfaceName: 'ebwg1234567890',
            ownershipId: '1234567890abcdef1234567890abcdef',
        },
        phase: 'active',
        plan: structuredClone(plan),
        killSwitchActive: false,
        observation: {
            exists: true,
            owned: true,
            up: true,
            peers: [
                {
                    publicKey: 'peer-public-key',
                    lastHandshakeUnix: 1720000000,
                    receiveBytes: 512,
                    transmitBytes: 1024,
                },
            ],
        },
    };
}
function protectedRuntime(): WireGuardRuntime {
    const runtime = active();
    runtime.plan.defaultRouteIPv4 = true;
    runtime.plan.peers[0].allowedIPs = ['0.0.0.0/0'];
    runtime.killSwitchActive = true;
    const owner = {
        interfaceName: runtime.target.interfaceName,
        ownershipId: runtime.target.ownershipId,
    };
    runtime.policy = {
        version: 1,
        owner,
        digest: 'matching-digest',
        routeTable: 123,
        allowedIPs: ['0.0.0.0/0'],
        ipv4Default: true,
        ipv6Default: false,
    };
    runtime.observation.policy = {
        owner,
        digest: 'matching-digest',
        routingVerified: true,
        firewallVerified: true,
        markVerified: true,
        endpointsVerified: true,
        ipv4PoliciesVerified: true,
        ipv6PoliciesVerified: true,
        killSwitchActive: true,
    };
    return runtime;
}
async function ready(
    values: ProfileStatus[] = [profile()],
    custom?: (url: string, options?: RequestInit) => Response | Promise<Response> | undefined,
) {
    const transport = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
        const url = String(input);
        if (url.includes('/authentication/')) return json(token());
        const result = custom?.(url, options);
        if (result) return result;
        if (url === root)
            return json({
                schemaVersion: 1,
                data: { profiles: values.map(({ runtime: _runtime, ...summary }) => summary) },
            });
        const id = url.slice(root.length + 1);
        const item = values.find((value) => value.profileId === id);
        return item ? json({ schemaVersion: 1, data: item }) : json({}, 404);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
async function select(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: 'Show status' }));
    await screen.findByRole('region', { name: 'Currently observed state: office' });
}
async function confirm(user: ReturnType<typeof userEvent.setup>, action: string) {
    await user.click(screen.getByRole('button', { name: action }));
    const form = within(screen.getByRole('form', { name: `${action}: office` }));
    await user.click(form.getByRole('checkbox'));
    await user.click(form.getByRole('button', { name: action }));
}

describe('WireGuard management', () => {
    it('moves keyboard focus into import and confirmation and restores it when cancelled', async () => {
        const user = userEvent.setup();
        const { client } = await ready();
        render(<WireGuardManager client={client} locale="en" />);
        await screen.findByText('office');
        const opener = screen.getByRole('button', { name: 'Import profile' });
        await user.click(opener);
        expect(screen.getByLabelText('Profile identifier')).toHaveFocus();
        await user.click(
            within(screen.getByRole('form', { name: 'Import profile' })).getByRole('button', {
                name: 'Close',
            }),
        );
        expect(opener).toHaveFocus();
        await select(user);
        await user.click(screen.getByRole('button', { name: 'Connect' }));
        const form = within(screen.getByRole('form', { name: 'Connect: office' }));
        expect(form.getByRole('checkbox')).toHaveFocus();
        await user.click(form.getByRole('button', { name: 'Cancel' }));
        expect(screen.getByRole('button', { name: 'Connect' })).toHaveFocus();
    });
    it('keeps verified runtime state readable when uint64 byte counters exceed JavaScript precision', async () => {
        const user = userEvent.setup();
        const runtime = protectedRuntime();
        runtime.observation.peers[0].receiveBytes = 18446744073709551615;
        const { client } = await ready([{ ...profile(), runtime }]);
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        expect(screen.getByText('Cannot be displayed exactly')).toBeInTheDocument();
        const observed = within(
            screen.getByRole('region', { name: 'Currently observed state: office' }),
        );
        expect(observed.getByText('Kill switch').nextElementSibling).toHaveTextContent(
            'Currently verified',
        );
    });
    it('loads control reads sequentially because the local control API rejects simultaneous operations', async () => {
        const user = userEvent.setup();
        let delay = false;
        let finish!: (response: Response) => void;
        const { client, transport } = await ready([profile()], (url) =>
            url === root && delay
                ? new Promise((resolve) => {
                      finish = resolve;
                  })
                : undefined,
        );
        render(<WireGuardManager client={client} locale="en" />);
        await screen.findByText('office');
        delay = true;
        await user.click(screen.getByRole('button', { name: 'Show status' }));
        await waitFor(() => expect(finish).toBeDefined());
        expect(transport.mock.calls.some(([url]) => url === `${root}/office`)).toBe(false);
        await act(async () => finish(json({ schemaVersion: 1, data: { profiles: [profile()] } })));
        await screen.findByRole('region', { name: 'Currently observed state: office' });
        expect(transport.mock.calls.some(([url]) => url === `${root}/office`)).toBe(true);
    });

    it('formats observed handshake timestamps as Unix seconds and distinguishes an absent handshake', async () => {
        const user = userEvent.setup();
        const values = [{ ...profile(), phase: 'active', runtime: active() }];
        const { client } = await ready(values);
        const { container } = render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        expect(
            container.querySelector('time[datetime="2024-07-03T09:46:40.000Z"]'),
        ).toBeInTheDocument();
        values[0].runtime.observation.peers[0].lastHandshakeUnix = 0;
        await user.click(screen.getByRole('button', { name: 'Refresh status' }));
        expect(await screen.findByText('No handshake reported yet')).toBeInTheDocument();
    });
    it('imports only an explicit configuration and clears its secret before the response arrives', async () => {
        const user = userEvent.setup();
        const values: ProfileStatus[] = [];
        let finish!: (response: Response) => void;
        const { client, transport } = await ready(values, (url, options) =>
            url === `${root}/office` && options?.method === 'PUT'
                ? new Promise((resolve) => {
                      finish = resolve;
                  })
                : undefined,
        );
        render(<WireGuardManager client={client} locale="en" />);
        await screen.findByText('No WireGuard profiles have been saved yet.');
        await user.click(screen.getByRole('button', { name: 'Import profile' }));
        const form = within(screen.getByRole('form', { name: 'Import profile' }));
        await user.type(form.getByLabelText('Profile identifier'), 'office');
        fireEvent.change(form.getByLabelText('Configuration to import'), {
            target: { value: secret },
        });
        await user.click(form.getByRole('button', { name: 'Import profile' }));
        await waitFor(() =>
            expect(transport.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(
                true,
            ),
        );
        expect(form.getByLabelText('Configuration to import')).toHaveValue('');
        expect(document.body.textContent).not.toContain('PRIVATE-KEY-NEVER-DISPLAY');
        values.push(profile());
        await act(async () => finish(json({ schemaVersion: 1, data: profile() })));
        expect(
            await screen.findByText('Profile import confirmed by reloading.'),
        ).toBeInTheDocument();
        const write = transport.mock.calls.find(([, options]) => options?.method === 'PUT')!;
        expect(write[0]).toBe(`${root}/office`);
        expect(JSON.parse(String(write[1]?.body))).toEqual({
            schemaVersion: 1,
            configuration: secret,
        });
        expect(transport.mock.calls.some(([url]) => String(url).endsWith('/connect'))).toBe(false);
        expect(localStorage.length).toBe(0);
        expect(sessionStorage.length).toBe(0);
    });

    it('rejects invalid identifiers and oversized UTF8 input without sending a write', async () => {
        const user = userEvent.setup();
        const { client, transport } = await ready([]);
        render(<WireGuardManager client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Import profile' }));
        const form = within(screen.getByRole('form', { name: 'Import profile' }));
        fireEvent.change(form.getByLabelText('Profile identifier'), {
            target: { value: '../escape' },
        });
        fireEvent.change(form.getByLabelText('Configuration to import'), {
            target: { value: secret },
        });
        await user.click(form.getByRole('button', { name: 'Import profile' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('valid profile identifier');
        fireEvent.change(form.getByLabelText('Profile identifier'), {
            target: { value: 'new-profile' },
        });
        fireEvent.change(form.getByLabelText('Configuration to import'), {
            target: { value: 'ä'.repeat(32769) },
        });
        await user.click(form.getByRole('button', { name: 'Import profile' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('65536 bytes');
        expect(form.getByLabelText('Configuration to import')).toHaveValue('');
        expect(transport.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
    });

    it('requires confirmation before overwriting a saved profile and erases input on locale change', async () => {
        const user = userEvent.setup();
        const { client, transport } = await ready();
        const { rerender } = render(<WireGuardManager client={client} locale="en" />);
        await screen.findByText('office');
        await user.click(screen.getByRole('button', { name: 'Import profile' }));
        const form = within(screen.getByRole('form', { name: 'Import profile' }));
        await user.type(form.getByLabelText('Profile identifier'), 'office');
        fireEvent.change(form.getByLabelText('Configuration to import'), {
            target: { value: secret },
        });
        await user.click(form.getByRole('button', { name: 'Import profile' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Confirm the action');
        expect(transport.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
        rerender(<WireGuardManager client={client} locale="de" />);
        expect(screen.getByLabelText('Konfiguration für den Import')).toHaveValue('');
    });

    it('requires connect confirmation and reports only the state fetched after the action', async () => {
        const user = userEvent.setup();
        const values = [profile()];
        const { client, transport } = await ready(values, (url, options) => {
            if (url.endsWith('/connect') && options?.method === 'POST') {
                values[0].runtime = active();
                values[0].phase = 'active';
                return json({ schemaVersion: 1, data: values[0] });
            }
        });
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        await user.click(screen.getByRole('button', { name: 'Connect' }));
        const form = within(screen.getByRole('form', { name: 'Connect: office' }));
        await user.click(form.getByRole('button', { name: 'Connect' }));
        expect(transport.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
        await user.click(form.getByRole('checkbox'));
        await user.click(form.getByRole('button', { name: 'Connect' }));
        expect(await screen.findByText(/The owned tunnel interface is active/)).toBeInTheDocument();
        const runtime = within(
            screen.getByRole('region', { name: 'Currently observed state: office' }),
        );
        expect(runtime.getByText(/Observed active/)).toBeInTheDocument();
        expect(runtime.getAllByText('Not verified').length).toBeGreaterThan(0);
        const request = transport.mock.calls.find(([, options]) => options?.method === 'POST')!;
        expect(request[0]).toBe(`${root}/office/connect`);
        expect(JSON.parse(String(request[1]?.body))).toEqual({ schemaVersion: 1 });
    });

    it('does not mistake a successful write response for an observed active tunnel', async () => {
        const user = userEvent.setup();
        const { client } = await ready([profile()], (url) =>
            url.endsWith('/connect')
                ? json({
                      schemaVersion: 1,
                      data: { ...profile(), phase: 'active', runtime: active() },
                  })
                : undefined,
        );
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        await confirm(user, 'Connect');
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The action could not be confirmed',
        );
        expect(screen.queryByText(/The owned tunnel interface is active/)).not.toBeInTheDocument();
        expect(screen.queryByText(/Observed active/)).not.toBeInTheDocument();
    });

    it('requires current matching routing and firewall attestations before showing a kill switch', async () => {
        const user = userEvent.setup();
        const values = [{ ...profile(), phase: 'active', runtime: protectedRuntime() }];
        const { client } = await ready(values);
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        let runtime = within(
            screen.getByRole('region', { name: 'Currently observed state: office' }),
        );
        expect(runtime.getByText('Kill switch').nextElementSibling).toHaveTextContent(
            'Currently verified',
        );
        expect(runtime.getByText('Verified tunnel networks').nextElementSibling).toHaveTextContent(
            '0.0.0.0/0',
        );
        expect(runtime.getByText('DNS resolution').nextElementSibling).toHaveTextContent(
            'Not verified',
        );
        values[0].runtime.observation.policy!.firewallVerified = false;
        await user.click(screen.getByRole('button', { name: 'Refresh status' }));
        await screen.findByRole('region', { name: 'Currently observed state: office' });
        runtime = within(screen.getByRole('region', { name: 'Currently observed state: office' }));
        expect(runtime.getByText('Kill switch').nextElementSibling).toHaveTextContent(
            'Not verified',
        );
        expect(runtime.queryByText('Verified tunnel networks')).not.toBeInTheDocument();
    });

    it('clears stale active claims when a later readback fails', async () => {
        const user = userEvent.setup();
        let fail = false;
        const { client } = await ready(
            [{ ...profile(), phase: 'active', runtime: active() }],
            () => (fail ? json('private diagnostic', 503) : undefined),
        );
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        expect(screen.getByText(/Observed active/)).toBeInTheDocument();
        fail = true;
        await user.click(screen.getByRole('button', { name: 'Refresh status' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('state is unknown');
        expect(screen.queryByText(/Observed active/)).not.toBeInTheDocument();
        expect(screen.queryByText('private diagnostic')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Connect' })).not.toBeInTheDocument();
    });

    it('disables known unsupported DNS and hostname profiles without pretending to configure DNS', async () => {
        const user = userEvent.setup();
        const value = profile();
        value.plan.dns = ['10.1.0.1'];
        value.plan.peers[0].endpoint = 'vpn.example:51820';
        const { client } = await ready([value]);
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        expect(screen.getByRole('button', { name: 'Connect' })).toBeDisabled();
        expect(screen.getByText(/contains DNS settings/)).toBeInTheDocument();
        expect(screen.getByText(/contains a hostname endpoint/)).toBeInTheDocument();
    });

    it('disconnects with a versioned request and enables deletion only after confirmed absence', async () => {
        const user = userEvent.setup();
        const values = [{ ...profile(), phase: 'active', runtime: active() }];
        const { client, transport } = await ready(values, (url, options) => {
            if (url.endsWith('/disconnect') && options?.method === 'POST') {
                values[0].phase = 'disconnected';
                values[0].runtime = {
                    ...active(),
                    phase: 'disconnected',
                    observation: { exists: false, owned: false, up: false, peers: [] },
                };
                return json({ schemaVersion: 1, data: values[0] });
            }
            if (url === `${root}/office` && options?.method === 'DELETE') {
                values.splice(0);
                return json({ schemaVersion: 1, data: { profileId: 'office', deleted: true } });
            }
        });
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        expect(screen.getByRole('button', { name: 'Delete profile' })).toBeDisabled();
        await confirm(user, 'Disconnect');
        expect(
            await screen.findByText('Disconnection confirmed by checking again.'),
        ).toBeInTheDocument();
        await confirm(user, 'Delete profile');
        expect(await screen.findByText('Deletion confirmed by reloading.')).toBeInTheDocument();
        expect(screen.getByText('No WireGuard profiles have been saved yet.')).toBeInTheDocument();
        const remove = transport.mock.calls.find(([, options]) => options?.method === 'DELETE')!;
        expect(remove[1]?.body).toBeUndefined();
    });

    it('serializes duplicate submissions and reads back state after a write failure', async () => {
        const user = userEvent.setup();
        const values = [profile()];
        let finish!: (response: Response) => void;
        const { client, transport } = await ready(values, (url) =>
            url.endsWith('/connect')
                ? new Promise((resolve) => {
                      finish = resolve;
                  })
                : undefined,
        );
        render(<WireGuardManager client={client} locale="en" />);
        await select(user);
        await user.click(screen.getByRole('button', { name: 'Connect' }));
        const element = screen.getByRole('form', { name: 'Connect: office' });
        await user.click(within(element).getByRole('checkbox'));
        fireEvent.submit(element);
        fireEvent.submit(element);
        await waitFor(() =>
            expect(
                transport.mock.calls.filter(([, options]) => options?.method === 'POST'),
            ).toHaveLength(1),
        );
        expect(within(element).getByRole('button', { name: 'Cancel' })).toBeDisabled();
        values[0].runtime = active();
        values[0].phase = 'active';
        await act(async () => finish(json({ error: { message: secret } }, 500)));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The action could not be confirmed',
        );
        expect(screen.getByText(/Observed active/)).toBeInTheDocument();
        expect(document.body.textContent).not.toContain('PRIVATE-KEY-NEVER-DISPLAY');
    });

    it('ignores a late status read after a newer selection and aborts reads when unmounted', async () => {
        const user = userEvent.setup();
        let delay = false;
        let finish!: (response: Response) => void;
        let signal: AbortSignal | null | undefined;
        const { client } = await ready([profile()], (url, options) => {
            if (delay && url === `${root}/office`) {
                signal = options?.signal;
                return new Promise((resolve) => {
                    finish = resolve;
                });
            }
        });
        const { unmount } = render(<WireGuardManager client={client} locale="en" />);
        await screen.findByText('office');
        delay = true;
        await user.click(screen.getByRole('button', { name: 'Show status' }));
        await waitFor(() => expect(signal).toBeDefined());
        unmount();
        expect(signal?.aborted).toBe(true);
        await act(async () =>
            finish(
                json({
                    schemaVersion: 1,
                    data: { ...profile(), phase: 'active', runtime: active() },
                }),
            ),
        );
        expect(screen.queryByText(/Observed active/)).not.toBeInTheDocument();
    });
});

it('requires owned observed peers and rejects inconsistent profile identifiers', () => {
    const runtime = protectedRuntime();
    expect(tunnelObserved(runtime)).toBe(true);
    expect(protectionObserved(runtime)).toBe(true);
    runtime.observation.owned = false;
    expect(tunnelObserved(runtime)).toBe(false);
    expect(protectionObserved(runtime)).toBe(false);
    runtime.observation.owned = true;
    runtime.observation.peers = [];
    expect(tunnelObserved(runtime)).toBe(false);
    runtime.target.profileId = 'different';
    expect(
        profileStatusResponseSchema.safeParse({ schemaVersion: 1, data: { ...profile(), runtime } })
            .success,
    ).toBe(false);
});
