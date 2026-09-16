// SPDX-License-Identifier: EUPL-1.2
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { device, json, token } from '../../test/fixtures';
import { profilesSchema } from './contracts';
import { VpnPage } from './index';

const profile = {
    id: 42,
    name: 'Existing OpenVPN',
    description: 'Provider profile',
    enabled: true,
    nameServersEnabled: true,
    temporary: false,
    deleted: false,
    loginCredentials: { username: 'private-provider-user', password: 'MASKED_SECRET' },
};
const status = {
    profileId: 42,
    active: false,
    up: false,
    devices: [] as string[],
    errors: [],
    exitStatus: null,
};
const encodedDevice = encodeURIComponent(device.id);
type Handler = (path: string, init?: RequestInit) => Response | Promise<Response> | undefined;
async function connected(handler?: Handler) {
    const transport = vi.fn<typeof fetch>().mockImplementation(async (url, init) => {
        const path = String(url).replace('/api/adminconsole', '');
        if (path === '/authentication/token/ADMINCONSOLE') return json(token());
        const custom = handler?.(path, init);
        if (custom !== undefined) return custom;
        if (path === '/vpn/profiles') return json([profile]);
        if (path === '/vpn/profile/42') return json(profile);
        if (path === '/vpn/profile/42/status') return json(status);
        if (path === '/devices')
            return json([
                device,
                { ...device, id: 'gateway', name: 'Gateway', isGateway: true },
                { ...device, id: 'appliance', name: 'eBlocker', isEblocker: true },
            ]);
        if (path === '/tor/countries/selected') return json(['de', 'fr']);
        if (path === '/openvpn/status')
            return json({
                isRunning: true,
                isFirstStart: false,
                host: 'vpn.example',
                mappedPort: 1194,
            });
        if (path === `/tor/config/${encodedDevice}`) return json({ sessionUseTor: false });
        if (path === `/vpn/profile/status/${encodedDevice}`)
            return new Response(null, { status: 204 });
        throw new Error(`Unexpected VPN request ${init?.method} ${path}`);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
async function selectDevice(user: ReturnType<typeof userEvent.setup>, locale: 'de' | 'en' = 'en') {
    await user.selectOptions(
        await screen.findByRole('combobox', {
            name: locale === 'de' ? 'Gerät auswählen' : 'Select device',
        }),
        device.id,
    );
    await waitFor(() =>
        expect(
            screen.getByRole('button', {
                name:
                    locale === 'de'
                        ? 'Tor für dieses Gerät aktivieren'
                        : 'Enable Tor for this device',
            }),
        ).toBeEnabled(),
    );
}

describe('existing OpenVPN, Tor and Mobile overview', () => {
    it('shows API state, device assignments, Tor countries and the actual isRunning Mobile field', async () => {
        const { client, transport } = await connected((path) =>
            path === '/vpn/profile/42/status'
                ? json({
                      ...status,
                      active: true,
                      up: true,
                      devices: [device.id],
                      errors: ['private-provider-debug-line'],
                  })
                : undefined,
        );
        const { container } = render(<VpnPage client={client} locale="de" />);
        expect(await screen.findByText('Tunnel verbunden')).toBeInTheDocument();
        expect(screen.getByText('DE')).toBeInTheDocument();
        expect(screen.getByText('FR')).toBeInTheDocument();
        expect(screen.getByText('Server läuft')).toBeInTheDocument();
        expect(screen.getByText('vpn.example')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Arbeitszimmer' })).toHaveAttribute(
            'href',
            `/settings/#!/devices/list/${encodedDevice}`,
        );
        expect(container.textContent).not.toMatch(/private-provider|MASKED_SECRET/);
        expect(transport.mock.calls.slice(1).every(([, init]) => init?.method === 'GET')).toBe(
            true,
        );
    });

    it('discards profile credentials before data can enter React state', () => {
        expect(profilesSchema.parse([profile])).toEqual([
            {
                id: 42,
                name: profile.name,
                description: profile.description,
                enabled: true,
                nameServersEnabled: true,
                temporary: false,
                deleted: false,
            },
        ]);
    });

    it('keeps complete OpenVPN import, Tor country, Mobile and profile flows reachable', async () => {
        const { client } = await connected();
        render(<VpnPage client={client} locale="en" />);
        expect(screen.getByRole('link', { name: 'Import and edit profiles' })).toHaveAttribute(
            'href',
            '/settings/#!/anonymization/vpn/',
        );
        expect(
            screen.getByRole('link', { name: 'Open Tor countries and further settings' }),
        ).toHaveAttribute('href', '/settings/#!/anonymization/tor');
        expect(
            screen.getByRole('link', { name: 'Set up Mobile and manage certificates and devices' }),
        ).toHaveAttribute('href', '/settings/#!/mobile');
        expect(
            await screen.findByRole('link', { name: 'Open profile details and diagnostics' }),
        ).toHaveAttribute('href', '/settings/#!/anonymization/vpn/42');
    });

    it('handles 204 as no device VPN and prevents selecting the appliance or gateway', async () => {
        const user = userEvent.setup();
        const { client } = await connected();
        render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        expect(await screen.findByText('No OpenVPN profile or Tor assigned')).toBeInTheDocument();
        const chooser = screen.getByRole('combobox', { name: 'Select device' });
        expect(within(chooser).getAllByRole('option')).toHaveLength(2);
        expect(within(chooser).queryByRole('option', { name: 'Gateway' })).not.toBeInTheDocument();
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    it('shows unavailable sources without inventing disconnected VPN or disabled Tor states', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path) =>
            path === `/vpn/profile/status/${encodedDevice}` ? json({}, 503) : undefined,
        );
        render(<VpnPage client={client} locale="en" />);
        await user.selectOptions(
            await screen.findByRole('combobox', { name: 'Select device' }),
            device.id,
        );
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'eBlocker could not process the request.',
        );
        expect(screen.queryByText('No OpenVPN profile or Tor assigned')).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Enable Tor for this device' }),
        ).not.toBeInTheDocument();
        expect(screen.getByText('Existing OpenVPN')).toBeInTheDocument();
    });

    it('rejects a wrong Mobile shape and keeps the remaining panels useful', async () => {
        const { client } = await connected((path) =>
            path === '/openvpn/status' ? json({ running: true }) : undefined,
        );
        render(<VpnPage client={client} locale="en" />);
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The response from eBlocker could not be read.',
        );
        expect(screen.queryByText('Server not running')).not.toBeInTheDocument();
        expect(await screen.findByText('Existing OpenVPN')).toBeInTheDocument();
    });

    it('distinguishes empty profiles, devices and unrestricted Tor exit countries', async () => {
        const { client } = await connected((path) =>
            ['/vpn/profiles', '/devices', '/tor/countries/selected'].includes(path)
                ? json([])
                : undefined,
        );
        render(<VpnPage client={client} locale="de" />);
        expect(await screen.findByText('Keine OpenVPN-Profile eingerichtet.')).toBeInTheDocument();
        expect(screen.getByText('Keine geeigneten Geräte vorhanden.')).toBeInTheDocument();
        expect(screen.getByText('Keine Länderbeschränkung eingestellt.')).toBeInTheDocument();
    });
});

describe('targeted device connection changes', () => {
    it.each(['de', 'en'] as const)(
        'confirms and verifies an OpenVPN assignment in %s without claiming the tunnel is up',
        async (locale) => {
            const user = userEvent.setup();
            let assigned = false;
            const { client, transport } = await connected((path, init) => {
                if (path === `/vpn/profile/42/status/${encodedDevice}` && init?.method === 'PUT') {
                    assigned = true;
                    return new Response(null, { status: 204 });
                }
                if (
                    assigned &&
                    ['/vpn/profile/42/status', `/vpn/profile/status/${encodedDevice}`].includes(
                        path,
                    )
                )
                    return json({ ...status, active: true, devices: [device.id] });
                return undefined;
            });
            render(<VpnPage client={client} locale={locale} />);
            await selectDevice(user, locale);
            await user.selectOptions(
                screen.getByRole('combobox', {
                    name: locale === 'de' ? 'OpenVPN-Profil' : 'OpenVPN profile',
                }),
                '42',
            );
            await user.click(
                screen.getByRole('button', {
                    name:
                        locale === 'de'
                            ? 'Über dieses Profil verbinden'
                            : 'Connect through this profile',
                }),
            );
            expect(screen.getByRole('dialog')).toHaveTextContent(
                locale === 'de'
                    ? 'Tor- oder andere VPN-Zuordnung wird ersetzt'
                    : 'Tor or other VPN assignment will be replaced',
            );
            expect(transport.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
            await user.click(
                screen.getByRole('button', {
                    name: locale === 'de' ? 'Verbindung ändern' : 'Change connection',
                }),
            );
            expect(
                await screen.findByText(
                    locale === 'de'
                        ? 'Die Gerätezuordnung wurde neu gelesen und bestätigt. Den Tunnelzustand siehst du separat.'
                        : 'The device assignment was read again and confirmed. Tunnel status is shown separately.',
                ),
            ).toBeInTheDocument();
            expect(
                screen.queryByText(locale === 'de' ? 'Tunnel verbunden' : 'Tunnel connected'),
            ).not.toBeInTheDocument();
            const write = transport.mock.calls.find(([, init]) => init?.method === 'PUT');
            expect(write?.[0]).toBe(`/api/adminconsole/vpn/profile/42/status/${encodedDevice}`);
            expect(write?.[1]?.body).toBe('true');
            expect(new Headers(write?.[1]?.headers).get('Authorization')).toBe('Bearer test-token');
        },
    );

    it('can switch an assigned VPN device to Tor with only the targeted Tor field', async () => {
        const user = userEvent.setup();
        let tor = false;
        const { client, transport } = await connected((path, init) => {
            if (path === `/tor/config/${encodedDevice}`) {
                if (init?.method === 'PUT') tor = true;
                return json({ sessionUseTor: tor });
            }
            if (path === `/vpn/profile/status/${encodedDevice}` && !tor)
                return json({ ...status, active: true, up: true, devices: [device.id] });
            return undefined;
        });
        render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        await user.click(screen.getByRole('button', { name: 'Enable Tor for this device' }));
        expect(screen.getByRole('dialog')).toHaveTextContent(
            'An existing OpenVPN assignment will be replaced',
        );
        await user.click(screen.getByRole('button', { name: 'Change connection' }));
        expect(await screen.findByText('Tor assigned')).toBeInTheDocument();
        const write = transport.mock.calls.find(([, init]) => init?.method === 'PUT');
        expect(write?.[0]).toBe(`/api/adminconsole/tor/config/${encodedDevice}`);
        expect(JSON.parse(String(write?.[1]?.body))).toEqual({ sessionUseTor: true });
        expect(transport.mock.calls.filter(([, init]) => init?.method === 'PUT')).toHaveLength(1);
    });

    it('disconnects only after explaining the return to normal routing', async () => {
        const user = userEvent.setup();
        let assigned = true;
        const { client, transport } = await connected((path, init) => {
            if (path === `/vpn/profile/42/status/${encodedDevice}` && init?.method === 'PUT') {
                assigned = false;
                return new Response(null, { status: 204 });
            }
            if (path === `/vpn/profile/status/${encodedDevice}` && assigned)
                return json({ ...status, active: true, up: true, devices: [device.id] });
            return undefined;
        });
        render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        await user.click(screen.getByRole('button', { name: 'Remove OpenVPN assignment' }));
        expect(screen.getByRole('dialog')).toHaveTextContent(
            'its public IP address may become visible',
        );
        await user.click(screen.getByRole('button', { name: 'Change connection' }));
        expect(await screen.findByText('No OpenVPN profile or Tor assigned')).toBeInTheDocument();
        expect(transport.mock.calls.find(([, init]) => init?.method === 'PUT')?.[1]?.body).toBe(
            'false',
        );
    });

    it('refuses a stale disconnect when the device has moved to another profile', async () => {
        const user = userEvent.setup();
        let reads = 0;
        const { client, transport } = await connected((path) => {
            if (path === `/vpn/profile/status/${encodedDevice}`)
                return json({
                    ...status,
                    profileId: ++reads === 1 ? 42 : 43,
                    active: true,
                    devices: [device.id],
                });
            return undefined;
        });
        render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        await user.click(screen.getByRole('button', { name: 'Remove OpenVPN assignment' }));
        await user.click(screen.getByRole('button', { name: 'Change connection' }));
        await screen.findByRole('alert');
        expect(transport.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
    });

    it('does not report success when a successful legacy write silently failed to apply', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path, init) =>
            path === `/vpn/profile/42/status/${encodedDevice}` && init?.method === 'PUT'
                ? new Response(null, { status: 204 })
                : undefined,
        );
        render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        await user.selectOptions(screen.getByRole('combobox', { name: 'OpenVPN profile' }), '42');
        await user.click(screen.getByRole('button', { name: 'Connect through this profile' }));
        await user.click(screen.getByRole('button', { name: 'Change connection' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('its new state is unconfirmed');
        expect(
            screen.queryByText(
                'The device assignment was read again and confirmed. Tunnel status is shown separately.',
            ),
        ).not.toBeInTheDocument();
    });

    it('reports failed writes while retaining the last known routing state', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path, init) =>
            path === `/tor/config/${encodedDevice}` && init?.method === 'PUT'
                ? json({}, 503)
                : undefined,
        );
        render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        await user.click(screen.getByRole('button', { name: 'Enable Tor for this device' }));
        await user.click(screen.getByRole('button', { name: 'Change connection' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('its new state is unconfirmed');
        expect(screen.queryByText('Tor assigned')).not.toBeInTheDocument();
    });

    it('cancels without changing network state and aborts in-flight reads when leaving', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected();
        const { unmount } = render(<VpnPage client={client} locale="en" />);
        await selectDevice(user);
        await user.click(screen.getByRole('button', { name: 'Enable Tor for this device' }));
        await user.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(transport.mock.calls.some(([, init]) => init?.method === 'PUT')).toBe(false);
        unmount();
        const pending: { signal?: AbortSignal | null; finish?: (response: Response) => void }[] =
            [];
        const waiting = await connected((path, init) =>
            path === '/vpn/profiles'
                ? new Promise((finish) => pending.push({ signal: init?.signal, finish }))
                : undefined,
        );
        const mounted = render(<VpnPage client={waiting.client} locale="en" />);
        await waitFor(() => expect(pending).toHaveLength(1));
        mounted.unmount();
        expect(pending[0].signal?.aborted).toBe(true);
        await act(async () => pending[0].finish?.(json([])));
    });
});
