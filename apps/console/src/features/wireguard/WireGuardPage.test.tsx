// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { WireGuardPage } from './WireGuardPage';

const status = {
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
                addresses: [{ prefix: '192.0.2.4/24', family: 'ipv4', scope: 'global' }],
            },
        ],
        routes: [],
    },
};
const plan = {
    schemaVersion: 1,
    data: {
        applied: false,
        killSwitchActive: false,
        interfaceAddresses: ['10.20.0.2/32'],
        dns: ['10.20.0.1'],
        defaultRouteIPv4: true,
        defaultRouteIPv6: false,
        endpointExclusions: ['198.51.100.5'],
        leakRisks: ['no_kill_switch', 'ipv6_not_fully_tunneled'],
        warnings: [],
        peers: [
            {
                publicKey: 'public-key-only',
                allowedIPs: ['0.0.0.0/0'],
                endpoint: '198.51.100.5:51820',
                hasPresharedKey: true,
            },
        ],
    },
};
const secretConfig = '[Interface]\nPrivateKey = PRIVATE-TEST-SECRET\n';
async function ready(
    validate: (options?: RequestInit) => Promise<Response> = async () => json(plan),
) {
    const transport = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
        if (String(input).includes('/authentication/')) return json(token());
        if (String(input).endsWith('/wireguard/profiles'))
            return json({ schemaVersion: 1, data: { profiles: [] } });
        if (String(input).endsWith('/wireguard/validate')) return validate(options);
        return json(status);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}

it('shows real interface state and a redacted plan, then clears the submitted secret', async () => {
    const user = userEvent.setup();
    const { client, transport } = await ready();
    render(<WireGuardPage client={client} locale="en" />);
    expect(await screen.findByText('eth0')).toBeInTheDocument();
    expect(screen.getByText('192.0.2.4/24')).toBeInTheDocument();
    fireEvent.change(screen.getByRole('textbox'), { target: { value: secretConfig } });
    await user.click(screen.getByRole('button', { name: 'Validate configuration' }));
    expect(await screen.findByRole('region', { name: 'Validation result' })).toHaveTextContent(
        'public-key-only',
    );
    expect(screen.getByRole('textbox')).toHaveValue('');
    expect(screen.getByText('Validation does not activate a kill switch.')).toBeInTheDocument();
    expect(screen.getByText(/This check did not establish a connection/)).toBeInTheDocument();
    expect(document.body.textContent).not.toContain('PRIVATE-TEST-SECRET');
    const request = transport.mock.calls.find(([url]) =>
        String(url).endsWith('/wireguard/validate'),
    );
    expect(request?.[0]).toBe('/api/adminconsole/wireguard/validate');
    expect(request?.[1]).toMatchObject({
        method: 'POST',
        body: JSON.stringify({ config: secretConfig }),
    });
    expect(new Headers(request?.[1]?.headers).get('Authorization')).toBe('Bearer test-token');
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
});

it('rejects oversized UTF-8 input before sending it', async () => {
    const user = userEvent.setup();
    const { client, transport } = await ready();
    render(<WireGuardPage client={client} locale="de" />);
    await screen.findByText('eth0');
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'ä'.repeat(32769) } });
    await user.click(screen.getByRole('button', { name: 'Konfiguration prüfen' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('leer oder zu groß');
    expect(transport.mock.calls.some(([url]) => String(url).endsWith('/wireguard/validate'))).toBe(
        false,
    );
});

it('clears secrets and never renders parser details returned with an invalid profile', async () => {
    const user = userEvent.setup();
    const { client } = await ready(async () => json({ error: { message: secretConfig } }, 422));
    render(<WireGuardPage client={client} locale="en" />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: secretConfig } });
    await user.click(screen.getByRole('button', { name: 'Validate configuration' }));
    await screen.findByRole('alert');
    expect(screen.getByRole('textbox')).toHaveValue('');
    expect(document.body.textContent).not.toContain('PRIVATE-TEST-SECRET');
    expect(screen.queryByRole('region', { name: 'Validation result' })).not.toBeInTheDocument();
});

it('rejects a response that claims the validation applied network changes', async () => {
    const user = userEvent.setup();
    const { client } = await ready(async () =>
        json({ ...plan, data: { ...plan.data, applied: true } }),
    );
    render(<WireGuardPage client={client} locale="en" />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: secretConfig } });
    await user.click(screen.getByRole('button', { name: 'Validate configuration' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('could not be read');
    expect(screen.queryByText('public-key-only')).not.toBeInTheDocument();
    expect(screen.getByRole('textbox')).toHaveValue('');
});

it('aborts a cancelled validation and ignores a late response', async () => {
    const user = userEvent.setup();
    let finish!: (response: Response) => void;
    let signal: AbortSignal | null | undefined;
    const { client } = await ready((options) => {
        signal = options?.signal;
        return new Promise((resolve) => {
            finish = resolve;
        });
    });
    render(<WireGuardPage client={client} locale="en" />);
    fireEvent.change(screen.getByRole('textbox'), { target: { value: secretConfig } });
    await user.click(screen.getByRole('button', { name: 'Validate configuration' }));
    await waitFor(() => expect(signal).toBeDefined());
    await user.click(screen.getByRole('button', { name: 'Clear input' }));
    expect(signal?.aborted).toBe(true);
    await act(async () => finish(json(plan)));
    expect(screen.getByRole('textbox')).toHaveValue('');
    expect(screen.queryByRole('region', { name: 'Validation result' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Validate configuration' })).toBeDisabled();
});

it('reports an unavailable local agent and retries its actual status', async () => {
    const user = userEvent.setup();
    let calls = 0;
    const client = new ConsoleClient(
        vi.fn(async (input) => {
            if (String(input).includes('/authentication/')) return json(token());
            if (String(input).endsWith('/wireguard/profiles'))
                return json({ schemaVersion: 1, data: { profiles: [] } });
            return ++calls === 1 ? json({ error: 'internal details' }, 503) : json(status);
        }),
    );
    await client.bootstrap();
    render(<WireGuardPage client={client} locale="de" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(
        'Netzwerkdienst ist nicht verfügbar',
    );
    expect(document.body.textContent).not.toContain('internal details');
    await user.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
    expect(await screen.findByText('eth0')).toBeInTheDocument();
    expect(calls).toBe(2);
});
