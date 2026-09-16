// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { ConsoleClient } from './api/client';
import { appliance, device, json, token } from './test/fixtures';

beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('eblocker.console.language', 'de');
    window.location.hash = '#/devices';
});
function setup(transport: typeof fetch) {
    const client = new ConsoleClient(transport);
    render(<App client={client} />);
    return client;
}

it('requires login before reading private devices and removes their data on sign-out', async () => {
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation((url) =>
            Promise.resolve(
                json(
                    String(url).endsWith('/wait')
                        ? 0
                        : String(url).endsWith('/devices')
                          ? [device]
                          : token(true),
                ),
            ),
        );
    setup(transport);
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText('Administratorpasswort'), 'private-password');
    expect(transport.mock.calls.some(([url]) => String(url).endsWith('/devices'))).toBe(false);
    await user.click(screen.getByRole('button', { name: /^Anmelden$/ }));
    await screen.findByRole('button', { name: /Arbeitszimmer/ });
    await user.click(screen.getByRole('button', { name: 'Abmelden' }));
    expect(screen.queryByText('Arbeitszimmer')).not.toBeInTheDocument();
    expect(screen.getByText('Du bist auf dieser Oberfläche abgemeldet.')).toBeInTheDocument();
    expect(JSON.stringify(localStorage)).not.toMatch(/private-password|test-token/);
    expect(sessionStorage.length).toBe(0);
});

it('opens the synthetic appliance from a deep link without a failing detail request', async () => {
    window.location.hash = `#/devices/${encodeURIComponent(appliance.id)}`;
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation((url) =>
            Promise.resolve(json(String(url).endsWith('/devices') ? [appliance] : token())),
        );
    setup(transport);
    await screen.findByRole('heading', { name: 'eBlocker', level: 2 });
    expect(transport.mock.calls.some(([url]) => String(url).includes('/devices/'))).toBe(false);
    expect(screen.getByText('2001:db8::20')).toBeInTheDocument();
});

it('changes the whole interface language and handles an empty device list', async () => {
    setup(async (url) => json(String(url).endsWith('/devices') ? [] : token()));
    await screen.findByRole('heading', { name: 'Geräte im Netzwerk', level: 2 });
    fireEvent.change(screen.getByLabelText('Sprache'), { target: { value: 'en' } });
    expect(screen.getByRole('heading', { name: 'Your network at a glance' })).toBeInTheDocument();
    expect(document.documentElement.lang).toBe('en');
    expect(localStorage.getItem('eblocker.console.language')).toBe('en');
});

it('keeps last-known data visibly stale after a refresh failure and supports retry', async () => {
    let failed = false;
    setup(async (url) => {
        if (!String(url).endsWith('/devices')) return json(token());
        if (failed) throw new TypeError('Network failed');
        return json([device]);
    });
    await screen.findByRole('button', { name: /Arbeitszimmer/ });
    failed = true;
    await userEvent.click(screen.getByRole('button', { name: /^Aktualisieren$/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('nicht aktualisiert');
    expect(screen.getByRole('button', { name: /Arbeitszimmer/ })).toBeInTheDocument();
    failed = false;
    await userEvent.click(screen.getByRole('button', { name: 'Erneut versuchen' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
});

it('hides previous device data when the server expires the session', async () => {
    let expired = false;
    setup(async (url) =>
        !String(url).endsWith('/devices')
            ? json(token())
            : expired
              ? json('error.token.invalid', 401)
              : json([device]),
    );
    await screen.findByRole('button', { name: /Arbeitszimmer/ });
    expired = true;
    await userEvent.click(screen.getByRole('button', { name: /^Aktualisieren$/ }));
    await screen.findByText('Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.');
    expect(screen.queryByText('Arbeitszimmer')).not.toBeInTheDocument();
});

it('shows the server login wait and disables repeated attempts', async () => {
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation((url) =>
            Promise.resolve(json(String(url).endsWith('/wait') ? 20 : token(true))),
        );
    setup(transport);
    await screen.findByText('Nächster Anmeldeversuch in 20 Sekunden.');
    await act(async () => {
        fireEvent.change(screen.getByLabelText('Administratorpasswort'), {
            target: { value: 'test' },
        });
    });
    expect(screen.getByRole('button', { name: /^Anmelden$/ })).toBeDisabled();
    expect(transport.mock.calls.some(([url]) => String(url).includes('/login/'))).toBe(false);
});
