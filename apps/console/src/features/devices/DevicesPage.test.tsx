// SPDX-License-Identifier: EUPL-1.2
import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { device, json, token } from '../../test/fixtures';
import { DevicesPage } from './DevicesPage';

it('aborts an older discovery request, applies the confirmed patch and refreshes the list', async () => {
    const user = userEvent.setup();
    const confirmed = { ...device, name: 'Confirmed device' };
    let getCount = 0;
    let staleSignal: AbortSignal | undefined;
    let finishStale!: (value: Response) => void;
    let patchOptions: RequestInit | undefined;
    const transport = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
        if (String(input).includes('/authentication/')) return json(token());
        if (options?.method === 'PATCH') {
            patchOptions = options;
            return json(confirmed);
        }
        getCount++;
        if (getCount === 1) return json([device]);
        if (getCount === 2) {
            staleSignal = options?.signal ?? undefined;
            return new Promise<Response>((resolve) => {
                finishStale = resolve;
            });
        }
        return json([confirmed]);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    render(
        <MemoryRouter initialEntries={[`/devices/${encodeURIComponent(device.id)}`]}>
            <DevicesPage client={client} locale="en" />
        </MemoryRouter>,
    );
    await screen.findByRole('heading', { name: device.name });
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(getCount).toBe(2));
    await user.click(screen.getByRole('button', { name: 'Edit settings' }));
    await user.clear(screen.getByRole('textbox', { name: 'Device name' }));
    await user.type(screen.getByRole('textbox', { name: 'Device name' }), confirmed.name);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByRole('heading', { name: confirmed.name });
    expect(staleSignal?.aborted).toBe(true);
    expect(JSON.parse(String(patchOptions?.body))).toEqual({ name: confirmed.name });
    await waitFor(() => expect(getCount).toBe(3));
    await act(async () => finishStale(json([device])));
    expect(screen.getByRole('heading', { name: confirmed.name })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: device.name })).not.toBeInTheDocument();
});

it('reloads actual state after an ambiguous write failure while retaining the draft', async () => {
    const user = userEvent.setup();
    const persisted = { ...device, name: 'Saved despite response failure' };
    let wasPersisted = false;
    let getCount = 0;
    const client = new ConsoleClient(
        vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
            if (String(input).includes('/authentication/')) return json(token());
            if (options?.method === 'PATCH') {
                wasPersisted = true;
                return json({ sensitive: 'internal private error' }, 500);
            }
            getCount++;
            return json([wasPersisted ? persisted : device]);
        }),
    );
    await client.bootstrap();
    render(
        <MemoryRouter initialEntries={[`/devices/${encodeURIComponent(device.id)}`]}>
            <DevicesPage client={client} locale="en" />
        </MemoryRouter>,
    );
    await screen.findByRole('heading', { name: device.name });
    await user.click(screen.getByRole('button', { name: 'Edit settings' }));
    await user.clear(screen.getByRole('textbox'));
    await user.type(screen.getByRole('textbox'), persisted.name);
    await user.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toHaveTextContent(
        'The changes could not be confirmed.',
    );
    await screen.findByRole('heading', { name: persisted.name });
    expect(getCount).toBe(2);
    expect(screen.getByRole('textbox')).toHaveValue(persisted.name);
    expect(screen.queryByText(/internal private error/)).not.toBeInTheDocument();
});

it('closes an edited device that disappears on a later discovery refresh', async () => {
    const user = userEvent.setup();
    let getCount = 0;
    const client = new ConsoleClient(
        vi.fn(async (input: RequestInfo | URL) => {
            if (String(input).includes('/authentication/')) return json(token());
            return json(++getCount === 1 ? [device] : []);
        }),
    );
    await client.bootstrap();
    render(
        <MemoryRouter initialEntries={[`/devices/${encodeURIComponent(device.id)}`]}>
            <DevicesPage client={client} locale="en" />
        </MemoryRouter>,
    );
    await screen.findByRole('heading', { name: device.name });
    await user.click(screen.getByRole('button', { name: 'Edit settings' }));
    await user.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(screen.queryByRole('form')).not.toBeInTheDocument());
    expect(screen.getByText('This device is no longer in the device list.')).toBeInTheDocument();
});
