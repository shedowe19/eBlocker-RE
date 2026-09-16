// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json } from '../../test/fixtures';
import { FamilyPage } from './FamilyPage';
import { familyDevicesSchema, familyProfilesSchema } from './contracts';
import { childProfile, fixture, readyClient } from './testSupport';

describe('family settings', () => {
    it('loads real contracts and shows users, assignments, full rules and exact legacy links', async () => {
        const user = userEvent.setup();
        const { client, transport } = await readyClient();
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        expect(
            screen.queryByRole('heading', { name: 'internal device user' }),
        ).not.toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'Other devices' })).toBeInTheDocument();
        const assignments = screen.getByRole('table', { name: 'Device assignments' });
        const childRow = within(assignments).getByRole('row', {
            name: 'Child tablet Mika Alex Change assignment',
        });
        expect(childRow).toBeInTheDocument();
        expect(
            within(assignments).getByRole('row', {
                name: 'Shared tablet Other devices Other devices Change assignment',
            }),
        ).toBeInTheDocument();
        expect(within(assignments).queryByText('Router')).not.toBeInTheDocument();
        const profile = screen.getByRole('article', { name: 'Child rules' });
        await user.click(within(profile).getByText('Saved rules'));
        expect(within(profile).getByText('24:00')).toBeInTheDocument();
        expect(within(profile).getByText('60 minutes')).toBeInTheDocument();
        expect(within(profile).getAllByText('Not specified')).toHaveLength(5);
        expect(within(profile).getByRole('link', { name: 'Games' })).toHaveAttribute(
            'href',
            '/settings/#!/parentalcontrol/blacklists/100',
        );
        expect(within(profile).getByRole('link', { name: 'School' })).toHaveAttribute(
            'href',
            '/settings/#!/parentalcontrol/whitelists/200',
        );
        const childCard = screen.getByRole('heading', { name: 'Mika' }).closest('article')!;
        expect(
            within(childCard).getByRole('link', { name: 'Edit user, PIN and devices' }),
        ).toHaveAttribute('href', '/settings/#!/parentalcontrol/users/1');
        const endpoints = transport.mock.calls.slice(1).map(([url]) => url);
        expect(endpoints).toEqual(
            expect.arrayContaining([
                '/api/adminconsole/users',
                '/api/adminconsole/userprofiles',
                '/api/adminconsole/devices',
                '/api/adminconsole/filterlists',
            ]),
        );
        for (const [, options] of transport.mock.calls.slice(1))
            expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer test-token');
    });

    it('switches locale without refetching or losing the assignment mapping', async () => {
        const { client, transport } = await readyClient();
        const { rerender } = render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        rerender(<FamilyPage client={client} locale="de" />);
        expect(screen.getByRole('heading', { name: 'Familie & Jugendschutz' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'Andere Geräte' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'Spieleseiten' })).toBeInTheDocument();
        expect(transport).toHaveBeenCalledTimes(5);
    });

    it('shows meaningful empty states only after successful empty responses', async () => {
        const { client } = await readyClient({
            '/users': [],
            '/userprofiles': [],
            '/devices': [],
            '/filterlists': [],
        });
        render(<FamilyPage client={client} locale="en" />);
        expect(await screen.findByText('No users have been set up yet.')).toBeInTheDocument();
        expect(screen.getByText('No protection profiles available.')).toBeInTheDocument();
        expect(screen.getByText('No assignable devices available.')).toBeInTheDocument();
        expect(screen.getByText('No filter lists available.')).toBeInTheDocument();
        expect(
            screen.getByRole('link', { name: 'Manage users and child profiles' }),
        ).toHaveAttribute('href', '/settings/#!/parentalcontrol/users/');
    });

    it('fails closed on malformed rule flags instead of displaying an empty family', async () => {
        const values = fixture();
        const { client } = await readyClient(values, (path) =>
            path === '/userprofiles'
                ? json([{ ...childProfile, controlmodeTime: 'false' }])
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('alert');
        expect(screen.queryByText('No users have been set up yet.')).not.toBeInTheDocument();
        expect(screen.queryByRole('heading', { name: 'Mika' })).not.toBeInTheDocument();
    });

    it('preserves the last known family data when a refresh fails', async () => {
        const user = userEvent.setup();
        let fail = false;
        const { client } = await readyClient(fixture(), () =>
            fail ? json('private error', 500) : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        fail = true;
        await user.click(screen.getByRole('button', { name: 'Refresh' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The displayed settings could not be refreshed.',
        );
        expect(screen.getByRole('heading', { name: 'Mika' })).toBeInTheDocument();
        expect(screen.queryByText('private error')).not.toBeInTheDocument();
    });

    it('adds only scalar bonus minutes and refreshes the confirmed profile without replacing other settings', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const writes: RequestInit[] = [];
        const { client } = await readyClient(values, (path, options) => {
            if (path === '/userprofile/bonustime/10' && options?.method === 'POST') {
                writes.push(options);
                values['/userprofiles'][0].bonusTimeUsage = {
                    dateTime: [2026, 9, 16, 12, 45],
                    bonusMinutes: 45,
                };
                return new Response(null, { status: 204 });
            }
            return undefined;
        });
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Add bonus time' }));
        await user.type(screen.getByRole('spinbutton', { name: 'Extra minutes today' }), '30');
        await user.click(screen.getByRole('button', { name: 'Confirm extra time' }));
        expect(await screen.findByRole('status')).toHaveTextContent('Bonus time was added.');
        expect(writes).toHaveLength(1);
        expect(writes[0].body).toBe('30');
        expect(screen.getByText('45 minutes')).toBeInTheDocument();
    });

    it('blocks invalid bonus values and supports cancellation without making a write', async () => {
        const user = userEvent.setup();
        const { client, transport } = await readyClient();
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Add bonus time' }));
        fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '-10' } });
        fireEvent.submit(screen.getByRole('form', { name: 'Add bonus time' }));
        expect(screen.getByRole('alert')).toHaveTextContent(
            'Enter a whole number from 1 to 1440 minutes.',
        );
        await user.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
        expect(
            transport.mock.calls.filter(([, options]) => options?.method === 'POST'),
        ).toHaveLength(0);
    });

    it('requires checking the reloaded state after an uncertain bonus write and prevents duplicates', async () => {
        const user = userEvent.setup();
        const values = fixture();
        let complete!: (response: Response) => void;
        const { client, transport } = await readyClient(values, (path) =>
            path === '/userprofile/bonustime/10'
                ? new Promise((resolve) => {
                      complete = resolve;
                  })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Add bonus time' }));
        fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '15' } });
        fireEvent.submit(screen.getByRole('form'));
        fireEvent.submit(screen.getByRole('form'));
        await waitFor(() =>
            expect(
                transport.mock.calls.filter(([, options]) => options?.method === 'POST'),
            ).toHaveLength(1),
        );
        expect(screen.getByRole('button', { name: 'Refresh' })).toBeDisabled();
        values['/userprofiles'][0].bonusTimeUsage = { dateTime: [2026, 9, 16], bonusMinutes: 30 };
        await act(async () => complete(json('internal error after save', 500)));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The bonus time could not be confirmed.',
        );
        expect(screen.getByText('30 minutes')).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Confirm extra time' }),
        ).not.toBeInTheDocument();
    });

    it('aborts all family reads on unmount', async () => {
        const signals: AbortSignal[] = [];
        const { client } = await readyClient({}, (_path, options) => {
            if (options?.signal) signals.push(options.signal);
            return new Promise<Response>((_resolve, reject) => {
                options?.signal?.addEventListener('abort', () =>
                    reject(new DOMException('Aborted', 'AbortError')),
                );
            });
        });
        const { unmount } = render(<FamilyPage client={client} locale="en" />);
        await waitFor(() => expect(signals).toHaveLength(4));
        unmount();
        expect(signals.every((signal) => signal.aborted)).toBe(true);
    });
});

it('retains assignment fields and missing daily limits in the schema adapter', () => {
    const data = fixture();
    expect(familyDevicesSchema.parse(data['/devices'])[0].assignedUser).toBe(1);
    const parsed = familyProfilesSchema.parse(data['/userprofiles'])[0];
    expect(parsed.maxUsageTimeByDay.TUESDAY).toBeUndefined();
    expect(parsed.internetAccessContingents[0].tillMinutes).toBe(1440);
});
