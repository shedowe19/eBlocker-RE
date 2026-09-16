// SPDX-License-Identifier: EUPL-1.2
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, it, vi } from 'vitest';
import { App } from './App';
import { ConsoleClient } from './api/client';
import { device, json, token } from './test/fixtures';

beforeEach(() => {
    localStorage.clear();
    localStorage.setItem('eblocker.console.language', 'de');
    window.history.replaceState(null, '', '#/features');
});
const data: Record<string, unknown> = {
    '/users': [],
    '/userprofiles': [],
    '/filterlists': [],
    '/devices': [{ ...device, defaultSystemUser: 1 }],
    '/vpn/profiles': [],
    '/tor/countries/selected': [],
    '/openvpn/status': { isRunning: false, isFirstStart: true },
    '/updates/status': {
        updating: false,
        downloading: false,
        checking: false,
        recovering: false,
        disabled: false,
        updatesAvailable: false,
        automaticUpdatesActivated: false,
        automaticUpdatesAllowed: true,
        lastUpdateAttemptFailed: false,
        projectVersion: '4.0.3',
    },
};
function transportFor(passwordRequired = false, failUpdates = false) {
    return vi.fn<typeof fetch>().mockImplementation(async (url) => {
        const path = String(url).replace('/api/adminconsole', '');
        if (
            path === '/authentication/token/ADMINCONSOLE' ||
            path === '/authentication/login/ADMINCONSOLE'
        )
            return json(token(passwordRequired));
        if (path === '/authentication/wait') return json(0);
        if (failUpdates && path === '/updates/status') return json({}, 503);
        if (Object.hasOwn(data, path)) return json(data[path]);
        throw new Error(`Unexpected parity navigation request: ${path}`);
    });
}

it('opens family, existing VPN and updates from the feature catalog and retains navigation back to every full flow', async () => {
    const user = userEvent.setup();
    const transport = transportFor();
    render(<App client={new ConsoleClient(transport)} />);
    await screen.findByRole('heading', { name: 'Alle Funktionen', level: 1 });
    expect(transport).toHaveBeenCalledTimes(1);
    for (const [entry, path, heading, marker] of [
        [
            'Familie, Geräte und Zeitregeln',
            '/family',
            'Familie & Jugendschutz',
            'Noch keine Benutzer eingerichtet.',
        ],
        ['OpenVPN-Verbindungen', '/vpn', 'OpenVPN und Tor', 'Keine OpenVPN-Profile eingerichtet.'],
        ['Updates und Zeitplanung', '/updates', 'Updates', '4.0.3'],
    ]) {
        await user.click(
            screen.getByRole('link', { name: `${entry}: In dieser Oberfläche öffnen` }),
        );
        expect(await screen.findByRole('heading', { name: heading, level: 1 })).toBeInTheDocument();
        expect(await screen.findByText(marker)).toBeInTheDocument();
        expect(window.location.hash).toBe(`#${path}`);
        expect(screen.getByRole('main')).toHaveFocus();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
        const navigation = screen.getByRole('navigation', { name: 'Netzwerkverwaltung' });
        expect(navigation.querySelector('[aria-current="page"]')).toHaveAttribute(
            'href',
            `#${path}`,
        );
        await user.click(within(navigation).getByRole('link', { name: 'Alle Funktionen' }));
        expect(
            screen.getByRole('link', { name: `${entry}: Vollständige Einstellungen öffnen` }),
        ).toHaveAttribute('href', expect.stringMatching(/^\/settings\/#!\//));
    }
    for (const [url, init] of transport.mock.calls.filter(
        ([url]) => !String(url).includes('/authentication/'),
    )) {
        expect(new Headers(init?.headers).get('Authorization'), String(url)).toBe(
            'Bearer test-token',
        );
    }
});

it('protects a catalog deep link and allows navigating away from a failed update request after switching language', async () => {
    const user = userEvent.setup();
    const transport = transportFor(true, true);
    render(<App client={new ConsoleClient(transport)} />);
    await user.type(await screen.findByLabelText('Administratorpasswort'), 'integration-password');
    expect(screen.queryByRole('heading', { name: 'Alle Funktionen' })).not.toBeInTheDocument();
    expect(transport.mock.calls.every(([url]) => String(url).includes('/authentication/'))).toBe(
        true,
    );
    await user.click(screen.getByRole('button', { name: 'Anmelden' }));
    await screen.findByRole('heading', { name: 'Alle Funktionen' });
    await user.selectOptions(screen.getByRole('combobox', { name: 'Sprache' }), 'en');
    expect(screen.getByRole('heading', { name: 'All features' })).toBeInTheDocument();
    await user.click(
        screen.getByRole('link', { name: 'Updates and scheduling: Open in this interface' }),
    );
    expect(await screen.findByRole('alert')).toHaveTextContent(
        'eBlocker could not process the request.',
    );
    await user.click(
        within(screen.getByRole('navigation', { name: 'Network management' })).getByRole('link', {
            name: 'VPN and Tor',
        }),
    );
    expect(await screen.findByRole('heading', { name: 'OpenVPN and Tor' })).toBeInTheDocument();
    expect(await screen.findByText('No OpenVPN profiles configured.')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByRole('main')).toHaveFocus();
    expect(JSON.stringify(localStorage)).not.toMatch(/integration-password|test-token/);
});
