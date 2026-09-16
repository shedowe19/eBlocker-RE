// SPDX-License-Identifier: EUPL-1.2
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { UpdatesPage } from './UpdatesPage';

// Matches UpdatingStatus, not a synthetic frontend status shape.
const status = {
    updating: false,
    downloading: false,
    checking: false,
    recovering: false,
    disabled: false,
    updatesAvailable: true,
    automaticUpdatesActivated: false,
    automaticUpdatesAllowed: true,
    lastUpdateAttemptFailed: false,
    projectVersion: '4.0.3',
    listsPacketVersion: '20260916',
    lastAutomaticUpdate: null,
    nextAutomaticUpdate: null,
    beginHour: 3,
    beginMin: 0,
    endHour: 5,
    endMin: 30,
    updateProgress: [],
    updateablePackages: ['eblocker-icapserver'],
};
type Handler = (path: string, init?: RequestInit) => Response | Promise<Response> | undefined;
async function connected(handler?: Handler) {
    const transport = vi.fn<typeof fetch>().mockImplementation(async (url, init) => {
        const path = String(url).replace('/api/adminconsole', '');
        if (path === '/authentication/token/ADMINCONSOLE') return json(token());
        const custom = handler?.(path, init);
        if (custom !== undefined) return custom;
        if (path === '/updates/status' && init?.method === 'GET') return json(status);
        throw new Error(`Unexpected update request: ${init?.method} ${path}`);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
const writes = (transport: ReturnType<typeof vi.fn<typeof fetch>>) =>
    transport.mock.calls.filter(([, init]) => init?.method === 'POST');

describe('update status and operations', () => {
    it('shows actual server metadata and retains exact update and backup settings links', async () => {
        const { client } = await connected();
        render(<UpdatesPage client={client} locale="de" />);
        expect(await screen.findByText('4.0.3')).toBeInTheDocument();
        expect(screen.getByText('20260916')).toBeInTheDocument();
        await waitFor(() => expect(screen.getByLabelText('Von')).toHaveValue('03:00'));
        expect(screen.getByLabelText('Bis')).toHaveValue('05:30');
        expect(screen.getAllByText('Nicht gemeldet')).toHaveLength(2);
        expect(screen.getByRole('link', { name: 'Vollständige Updateverwaltung' })).toHaveAttribute(
            'href',
            '/settings/#!/home/update',
        );
        expect(screen.getByRole('link', { name: 'Sicherung öffnen' })).toHaveAttribute(
            'href',
            '/settings/#!/system/backup',
        );
    });

    it('checks through the real GET endpoint after re-reading status, then refreshes actual state', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path === '/updates/check' ? json({ ...status, checking: true }) : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Check for updates' }));
        await screen.findByText('Request acknowledged. Reloading the actual server status.');
        await waitFor(() =>
            expect(
                transport.mock.calls.filter(([path]) => String(path).endsWith('/updates/status')),
            ).toHaveLength(3),
        );
        expect(transport.mock.calls.slice(1).map(([path]) => path)).toEqual([
            '/api/adminconsole/updates/status',
            '/api/adminconsole/updates/status',
            '/api/adminconsole/updates/check',
            '/api/adminconsole/updates/status',
        ]);
        expect(writes(transport)).toHaveLength(0);
    });

    it.each(['de', 'en'] as const)(
        'requires confirmation and submits only the install flag in %s',
        async (locale) => {
            const user = userEvent.setup();
            let started = false;
            const { client, transport } = await connected((path, init) => {
                if (path === '/updates/status' && init?.method === 'POST') {
                    started = true;
                    // Java setUpdatingStatus echoes a newly deserialized DTO with defaults.
                    return json({
                        ...status,
                        updating: true,
                        updatesAvailable: false,
                        automaticUpdatesAllowed: false,
                        projectVersion: null,
                    });
                }
                if (path === '/updates/status' && started)
                    return json({ ...status, updating: true });
                return undefined;
            });
            render(<UpdatesPage client={client} locale={locale} />);
            await user.click(
                await screen.findByRole('button', {
                    name: locale === 'de' ? 'Updates installieren' : 'Install updates',
                }),
            );
            expect(screen.getByRole('dialog')).toHaveTextContent(
                locale === 'de' ? 'Verbindung unterbrechen' : 'interrupt your connection',
            );
            expect(writes(transport)).toHaveLength(0);
            await user.click(
                screen.getByRole('button', { name: locale === 'de' ? 'Bestätigen' : 'Confirm' }),
            );
            expect(
                await screen.findByText(
                    locale === 'de' ? 'Ein Vorgang läuft' : 'An operation is running',
                ),
            ).toBeInTheDocument();
            expect(writes(transport)).toHaveLength(1);
            expect(JSON.parse(String(writes(transport)[0][1]?.body))).toEqual({ updating: true });
            expect(new Headers(writes(transport)[0][1]?.headers).get('Authorization')).toBe(
                'Bearer test-token',
            );
        },
    );

    it('requests recovery with the existing recovering flag only after a failed attempt', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path, init) => {
            if (path === '/updates/status')
                return json({
                    ...status,
                    lastUpdateAttemptFailed: true,
                    recovering: init?.method === 'POST',
                });
            return undefined;
        });
        render(<UpdatesPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Request update recovery' }));
        expect(screen.getByRole('dialog')).toHaveTextContent('Services may restart');
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        await screen.findByText('Request acknowledged. Reloading the actual server status.');
        expect(JSON.parse(String(writes(transport)[0][1]?.body))).toEqual({ recovering: true });
    });

    it.each(['updating', 'checking', 'downloading', 'recovering'] as const)(
        'locks mutation controls while %s is active',
        async (flag) => {
            const { client, transport } = await connected(() => json({ ...status, [flag]: true }));
            render(<UpdatesPage client={client} locale="en" />);
            await screen.findByText('An operation is running');
            expect(screen.getByRole('button', { name: 'Check for updates' })).toBeDisabled();
            expect(screen.getByRole('button', { name: 'Install updates' })).toBeDisabled();
            expect(screen.getByRole('button', { name: 'Enable automatic updates' })).toBeDisabled();
            expect(screen.getByRole('button', { name: 'Save time window' })).toBeDisabled();
            expect(writes(transport)).toHaveLength(0);
        },
    );

    it('refuses installation if another operation started while confirmation was open', async () => {
        const user = userEvent.setup();
        let reads = 0;
        const { client, transport } = await connected((path) =>
            path === '/updates/status' ? json({ ...status, checking: ++reads > 1 }) : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Install updates' }));
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('could not be confirmed');
        expect(writes(transport)).toHaveLength(0);
        expect(await screen.findByText('An operation is running')).toBeInTheDocument();
    });

    it('cancels an installation without writing', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected();
        render(<UpdatesPage client={client} locale="de" />);
        await user.click(await screen.findByRole('button', { name: 'Updates installieren' }));
        await user.click(screen.getByRole('button', { name: 'Abbrechen' }));
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(writes(transport)).toHaveLength(0);
    });

    it('changes automatic updates with the single real property and refreshes its confirmed server value', async () => {
        const user = userEvent.setup();
        let enabled = false;
        const { client, transport } = await connected((path) => {
            if (path === '/updates/automaticUpdatesStatus') enabled = true;
            return json({ ...status, automaticUpdatesActivated: enabled });
        });
        render(<UpdatesPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Enable automatic updates' }));
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        expect(
            await screen.findByRole('button', { name: 'Disable automatic updates' }),
        ).toBeInTheDocument();
        expect(writes(transport)[0][0]).toBe('/api/adminconsole/updates/automaticUpdatesStatus');
        expect(JSON.parse(String(writes(transport)[0][1]?.body))).toEqual({
            automaticUpdatesActivated: true,
        });
    });

    it('does not overwrite a concurrently changed automatic update setting', async () => {
        const user = userEvent.setup();
        let reads = 0;
        const { client, transport } = await connected((path) =>
            path === '/updates/status'
                ? json({ ...status, automaticUpdatesActivated: ++reads > 1 })
                : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Enable automatic updates' }));
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        await screen.findByRole('alert');
        expect(writes(transport)).toHaveLength(0);
        expect(
            await screen.findByRole('button', { name: 'Disable automatic updates' }),
        ).toBeInTheDocument();
    });

    it('rechecks the entitlement before an automatic update change', async () => {
        const user = userEvent.setup();
        let reads = 0;
        const { client, transport } = await connected((path) =>
            path === '/updates/status'
                ? json({ ...status, automaticUpdatesAllowed: ++reads === 1 })
                : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Enable automatic updates' }));
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        await screen.findByRole('alert');
        expect(writes(transport)).toHaveLength(0);
        expect(
            await screen.findByText(
                'The server reports that automatic updates are unavailable for this device.',
            ),
        ).toBeInTheDocument();
    });

    it('submits an overnight window as the four controller fields', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path, init) =>
            path === '/updates/automaticUpdatesConfig'
                ? json({ ...status, ...JSON.parse(String(init?.body)) })
                : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        fireEvent.change(await screen.findByLabelText('From'), { target: { value: '23:50' } });
        fireEvent.change(screen.getByLabelText('Until'), { target: { value: '00:10' } });
        await user.click(screen.getByRole('button', { name: 'Save time window' }));
        expect(writes(transport)).toHaveLength(0);
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        await screen.findByText('Request acknowledged. Reloading the actual server status.');
        expect(writes(transport)[0][0]).toBe('/api/adminconsole/updates/automaticUpdatesConfig');
        expect(JSON.parse(String(writes(transport)[0][1]?.body))).toEqual({
            beginHour: 23,
            beginMin: 50,
            endHour: 0,
            endMin: 10,
        });
    });

    it('refuses to overwrite a window changed by another administrator since editing started', async () => {
        const user = userEvent.setup();
        let reads = 0;
        const { client, transport } = await connected((path) =>
            path === '/updates/status'
                ? json({ ...status, beginHour: ++reads === 1 ? 3 : 21 })
                : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        fireEvent.change(await screen.findByLabelText('From'), { target: { value: '23:50' } });
        fireEvent.change(screen.getByLabelText('Until'), { target: { value: '00:10' } });
        await user.click(screen.getByRole('button', { name: 'Save time window' }));
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        await screen.findByRole('alert');
        expect(writes(transport)).toHaveLength(0);
        await waitFor(() => expect(screen.getByLabelText('From')).toHaveValue('21:00'));
    });

    it('rejects equal times before confirmation or a write', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected();
        render(<UpdatesPage client={client} locale="de" />);
        fireEvent.change(await screen.findByLabelText('Von'), { target: { value: '05:30' } });
        await user.click(screen.getByRole('button', { name: 'Zeitfenster speichern' }));
        expect(screen.getByRole('alert')).toHaveTextContent('unterschiedliche Uhrzeiten');
        expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
        expect(writes(transport)).toHaveLength(0);
    });

    it('treats the legacy null validation response as unconfirmed and reloads status', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path === '/updates/automaticUpdatesConfig' ? json(null) : undefined,
        );
        render(<UpdatesPage client={client} locale="en" />);
        fireEvent.change(await screen.findByLabelText('From'), { target: { value: '02:30' } });
        await user.click(screen.getByRole('button', { name: 'Save time window' }));
        await user.click(screen.getByRole('button', { name: 'Confirm' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('could not be confirmed');
        expect(
            screen.queryByText('Request acknowledged. Reloading the actual server status.'),
        ).not.toBeInTheDocument();
        await waitFor(() =>
            expect(
                transport.mock.calls.filter(([url]) => String(url).endsWith('/updates/status')),
            ).toHaveLength(3),
        );
        expect(screen.getByText('4.0.3')).toBeInTheDocument();
    });

    it('fails closed on malformed status without manufacturing update actions', async () => {
        const { client } = await connected(() => json({ ...status, updating: 'false' }));
        render(<UpdatesPage client={client} locale="en" />);
        expect(await screen.findByRole('alert')).toHaveTextContent('could not be read');
        expect(screen.queryByRole('button', { name: 'Install updates' })).not.toBeInTheDocument();
    });
});
