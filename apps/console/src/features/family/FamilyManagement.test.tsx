// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { json } from '../../test/fixtures';
import { FamilyPage } from './FamilyPage';
import { childProfile, fixture, readyClient } from './testSupport';
import { weekDays } from './contracts';
import { parseDomains } from './FilterEditor';

function card(name: string) {
    return screen.getByRole('heading', { name }).closest('article')!;
}
function form(name: string | RegExp) {
    return within(screen.getByRole('form', { name }));
}
const patchCalls = (transport: Awaited<ReturnType<typeof readyClient>>['transport']) =>
    transport.mock.calls.filter(([, options]) => options?.method === 'PATCH');

describe('family management', () => {
    it('jumps to profile rules without changing the hash route and restores focus after cancelling an editor', async () => {
        const user = userEvent.setup();
        const { client } = await readyClient();
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        const previousHash = window.location.hash;
        window.location.hash = '#/family';
        try {
            await user.click(within(card('Mika')).getByRole('button', { name: 'Child rules' }));
            expect(window.location.hash).toBe('#/family');
            expect(document.activeElement).toHaveAttribute('id', 'family-profile-10');
            const opener = within(card('Mika')).getByRole('button', { name: 'Edit user' });
            await user.click(opener);
            expect(screen.getByRole('heading', { name: 'Edit user' })).toHaveFocus();
            await user.click(form('Edit user').getByRole('button', { name: 'Cancel' }));
            expect(opener).toHaveFocus();
        } finally {
            window.location.hash = previousHash;
        }
    });
    it('creates an enabled allowlist with validated domains through the existing filter API', async () => {
        const user = userEvent.setup();
        const { client, transport } = await readyClient(fixture(), (path, options) =>
            path === '/filterlists?filterType=whitelist' && options?.method === 'POST'
                ? json({ ...JSON.parse(String(options.body)), id: 300 })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(screen.getByRole('button', { name: 'Add filter list' }));
        const editor = form('Add filter list');
        await user.type(editor.getByLabelText('Name'), 'Learning');
        await user.type(editor.getByLabelText('Description'), 'School sites');
        await user.selectOptions(editor.getByLabelText('Filter mode'), 'whitelist');
        fireEvent.change(editor.getByLabelText('Domains'), {
            target: { value: 'school.example\nbooks.example' },
        });
        await user.click(editor.getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('status')).toHaveTextContent('Saved.');
        const write = transport.mock.calls.find(([, options]) => options?.method === 'POST')!;
        expect(JSON.parse(String(write[1]?.body))).toEqual({
            customerCreatedName: 'Learning',
            customerCreatedDescription: 'School sites',
            filterType: 'whitelist',
            domains: ['school.example', 'books.example'],
            disabled: false,
            builtin: false,
        });
    });

    it('updates custom list domains while retaining fresh server metadata', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/filterlists/200/domains') return json(['school.example']);
            if (
                path === '/filterlists/200/update?filterType=whitelist' &&
                options?.method === 'PUT'
            )
                return json(JSON.parse(String(options.body)));
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(
            within(screen.getByRole('heading', { name: 'School' }).closest('li')!).getByRole(
                'button',
                { name: 'Edit filter list' },
            ),
        );
        const domains = await screen.findByLabelText('Domains');
        Object.assign(values['/filterlists'][1], {
            lastUpdate: 1234,
            category: 'PARENTAL_CONTROL',
            version: 'new-metadata',
        });
        fireEvent.change(domains, { target: { value: 'changed.example' } });
        await user.click(form('Edit filter list: School').getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        const write = transport.mock.calls.find(([, options]) => options?.method === 'PUT')!;
        expect(JSON.parse(String(write[1]?.body))).toMatchObject({
            id: 200,
            builtin: false,
            domains: ['changed.example'],
            lastUpdate: 1234,
            version: 'new-metadata',
            category: 'PARENTAL_CONTROL',
        });
    });

    it('deletes an unused custom filter only after explicit confirmation', async () => {
        const user = userEvent.setup();
        const values = fixture();
        values['/userprofiles'][0].accessibleSitesPackages = [];
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/filterlists/200' && options?.method === 'DELETE') {
                values['/filterlists'] = values['/filterlists'].filter(
                    (filter) => filter.id !== 200,
                );
                return new Response(null, { status: 204 });
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(
            within(screen.getByRole('heading', { name: 'School' }).closest('li')!).getByRole(
                'button',
                { name: 'Delete filter list' },
            ),
        );
        const editor = form('Delete filter list: School');
        await user.click(editor.getByRole('checkbox'));
        await user.click(editor.getByRole('button', { name: 'Delete filter list' }));
        await screen.findByRole('status');
        expect(screen.queryByRole('heading', { name: 'School' })).not.toBeInTheDocument();
        expect(transport.mock.calls.find(([, options]) => options?.method === 'DELETE')?.[0]).toBe(
            '/api/adminconsole/filterlists/200',
        );
    });

    it('deletes an unassigned managed profile and verifies the deletion before reporting success', async () => {
        const user = userEvent.setup();
        const values = fixture();
        values['/userprofiles'].push({
            ...structuredClone(childProfile),
            id: 30,
            name: 'Unused',
            forSingleUser: false,
        });
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/userprofiles/30' && options?.method === 'DELETE') {
                values['/userprofiles'] = values['/userprofiles'].filter(
                    (profile) => profile.id !== 30,
                );
                return new Response(null, { status: 204 });
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        const profile = await screen.findByRole('article', { name: 'Unused' });
        await user.click(
            within(profile).getByRole('button', { name: 'Delete protection profile' }),
        );
        const editor = form('Delete protection profile: Unused');
        await user.click(editor.getByRole('checkbox'));
        await user.click(editor.getByRole('button', { name: 'Delete protection profile' }));
        await screen.findByRole('status');
        expect(screen.queryByRole('article', { name: 'Unused' })).not.toBeInTheDocument();
        expect(transport.mock.calls.find(([, options]) => options?.method === 'DELETE')?.[0]).toBe(
            '/api/adminconsole/userprofiles/30',
        );
    });

    it('edits default system profile rules without renaming it or writing null legacy window values', async () => {
        const user = userEvent.setup();
        const values = fixture();
        Object.assign(values['/userprofiles'][0], {
            name: null,
            nameKey: 'PARENTAL_CONTROL_DEFAULT_PROFILE_NAME',
            builtin: true,
            standard: true,
            internetAccessContingents: [
                { onDay: 8, fromMinutes: 480, tillMinutes: 1440, totalMinutes: null },
            ],
        });
        const { client, transport } = await readyClient(values, (path, options) =>
            path === '/userprofiles/10/settings' && options?.method === 'PATCH'
                ? json({ ...values['/userprofiles'][0], ...JSON.parse(String(options.body)) })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Edit protection profile' }));
        const editor = form('Edit protection profile');
        expect(editor.queryByLabelText('Name')).not.toBeInTheDocument();
        await user.click(editor.getByRole('checkbox', { name: 'Content filters' }));
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        expect(JSON.parse(String(patchCalls(transport)[0][1]?.body))).toEqual({
            controlmodeUrls: false,
        });
    });

    it('merges only an edited daily limit even if another day changes remotely', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/userprofiles/10/settings' && options?.method === 'PATCH') {
                Object.assign(
                    values['/userprofiles'][0].maxUsageTimeByDay,
                    JSON.parse(String(options.body)).maxUsageTimeByDay,
                );
                return json(values['/userprofiles'][0]);
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Edit protection profile' }));
        const editor = form('Edit protection profile');
        fireEvent.change(editor.getByLabelText('Monday'), { target: { value: '45' } });
        values['/userprofiles'][0].maxUsageTimeByDay.FRIDAY = 140;
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        expect(JSON.parse(String(patchCalls(transport)[0][1]?.body))).toEqual({
            maxUsageTimeByDay: { MONDAY: 45 },
        });
        expect(values['/userprofiles'][0].maxUsageTimeByDay.FRIDAY).toBe(140);
    });

    it('aborts an earlier overview read before saving so late data cannot replace the confirmed state', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const oldUsers = structuredClone(values['/users']);
        let delayNextUsers = false;
        let oldResponse!: (response: Response) => void;
        let oldSignal: AbortSignal | null | undefined;
        const { client } = await readyClient(values, (path, options) => {
            if (path === '/users' && delayNextUsers) {
                delayNextUsers = false;
                oldSignal = options?.signal;
                return new Promise((resolve) => {
                    oldResponse = resolve;
                });
            }
            if (path === '/users/1/settings' && options?.method === 'PATCH') {
                Object.assign(values['/users'][0], JSON.parse(String(options.body)));
                return json(values['/users'][0]);
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Edit user' }));
        fireEvent.change(form('Edit user').getByLabelText('Name'), {
            target: { value: 'Current name' },
        });
        delayNextUsers = true;
        await user.click(screen.getByRole('button', { name: 'Refresh' }));
        await waitFor(() => expect(oldSignal).toBeDefined());
        await user.click(form('Edit user').getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        expect(oldSignal?.aborted).toBe(true);
        await act(async () => oldResponse(json(oldUsers)));
        expect(screen.getByRole('heading', { name: 'Current name' })).toBeInTheDocument();
        expect(screen.queryByRole('heading', { name: 'Mika' })).not.toBeInTheDocument();
    });

    it('reports confirmed save separately from a failed readback and retains the previous overview', async () => {
        const user = userEvent.setup();
        let written = false;
        const values = fixture();
        const { client } = await readyClient(values, (path, options) => {
            if (path === '/users/1/settings' && options?.method === 'PATCH') {
                written = true;
                return json({ ...values['/users'][0], name: 'Saved name' });
            }
            if (written) return json('private readback failure', 500);
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Edit user' }));
        fireEvent.change(form('Edit user').getByLabelText('Name'), {
            target: { value: 'Saved name' },
        });
        await user.click(form('Edit user').getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('status')).toHaveTextContent(
            'The current overview could not yet be loaded',
        );
        expect(screen.getByRole('heading', { name: 'Mika' })).toBeInTheDocument();
        expect(screen.queryByText('private readback failure')).not.toBeInTheDocument();
    });
    it('creates a user with an explicit role, date and profile, without PIN or system metadata', async () => {
        const user = userEvent.setup();
        const values = fixture();
        values['/userprofiles'].push({
            ...structuredClone(childProfile),
            id: 30,
            name: 'Available rules',
        });
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/users' && options?.method === 'POST') {
                const body = JSON.parse(String(options.body));
                const created = { ...body, id: 3, system: false };
                values['/users'].push(created);
                return json(created);
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(screen.getByRole('button', { name: 'Add user' }));
        const editor = form('Add user');
        await user.type(editor.getByLabelText('Name'), '  Jo  ');
        await user.selectOptions(editor.getByLabelText('Role'), 'PARENT');
        fireEvent.change(editor.getByLabelText('Birthday'), { target: { value: '1988-02-29' } });
        await user.selectOptions(editor.getByLabelText('Protection profile'), '30');
        await user.click(editor.getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('status')).toHaveTextContent('Saved.');
        const write = transport.mock.calls.find(([, options]) => options?.method === 'POST')!;
        expect(JSON.parse(String(write[1]?.body))).toEqual({
            name: 'Jo',
            birthday: [1988, 2, 29],
            userRole: 'PARENT',
            associatedProfileId: 30,
            containsPin: false,
        });
        expect(screen.getByRole('heading', { name: 'Jo' })).toBeInTheDocument();
    });

    it('patches only changed user fields and preserves independent server changes', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/users/1/settings' && options?.method === 'PATCH') {
                Object.assign(values['/users'][0], JSON.parse(String(options.body)));
                return json(values['/users'][0]);
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Edit user' }));
        const editor = form('Edit user');
        await user.clear(editor.getByLabelText('Name'));
        await user.type(editor.getByLabelText('Name'), 'Mika new');
        values['/users'][0].userRole = 'OTHER';
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        expect(JSON.parse(String(patchCalls(transport)[0][1]?.body))).toEqual({ name: 'Mika new' });
        expect(values['/users'][0].userRole).toBe('OTHER');
    });

    it('rejects a concurrent edit to the same user field before writing and supports cancel', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values);
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Edit user' }));
        const editor = form('Edit user');
        await user.clear(editor.getByLabelText('Name'));
        await user.type(editor.getByLabelText('Name'), 'New name');
        values['/users'][0].name = 'Changed elsewhere';
        await user.click(editor.getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('The settings have changed');
        expect(patchCalls(transport)).toHaveLength(0);
        await user.click(screen.getByRole('button', { name: 'Close' }));
        await user.click(
            within(card('Changed elsewhere')).getByRole('button', { name: 'Edit user' }),
        );
        await user.click(form('Edit user').getByRole('button', { name: 'Cancel' }));
        expect(patchCalls(transport)).toHaveLength(0);
    });

    it('requires deletion confirmation, resets via the existing delete API and protects system users', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/users/1' && options?.method === 'DELETE') {
                values['/users'] = values['/users'].filter((item) => item.id !== 1);
                return new Response(null, { status: 204 });
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        expect(
            within(card('Other devices')).queryByRole('button', { name: 'Delete user' }),
        ).not.toBeInTheDocument();
        await user.click(within(card('Mika')).getByRole('button', { name: 'Delete user' }));
        const editor = form('Delete user: Mika');
        await user.click(editor.getByRole('button', { name: 'Delete user' }));
        expect(
            transport.mock.calls.filter(([, options]) => options?.method === 'DELETE'),
        ).toHaveLength(0);
        await user.click(editor.getByRole('checkbox'));
        await user.click(editor.getByRole('button', { name: 'Delete user' }));
        await screen.findByRole('status');
        expect(screen.queryByRole('heading', { name: 'Mika' })).not.toBeInTheDocument();
    });

    it('sends a PIN only to its dedicated endpoint, erases it on failure and on locale change', async () => {
        const user = userEvent.setup();
        const { client, transport } = await readyClient(fixture(), (path) =>
            path === '/users/1/pin' ? json('sensitive detail', 500) : undefined,
        );
        const { rerender } = render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Manage PIN' }));
        await user.type(screen.getByLabelText('New PIN'), 'A-p4ss');
        await user.type(screen.getByLabelText('Repeat PIN'), 'A-p4ss');
        rerender(<FamilyPage client={client} locale="de" />);
        expect(screen.getByLabelText('Neue PIN')).toHaveValue('');
        expect(screen.getByLabelText('PIN wiederholen')).toHaveValue('');
        await user.type(screen.getByLabelText('Neue PIN'), 'n3w-pin');
        await user.type(screen.getByLabelText('PIN wiederholen'), 'n3w-pin');
        await user.click(screen.getByRole('button', { name: 'PIN speichern' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'Die Änderung konnte nicht bestätigt werden',
        );
        expect(screen.getByLabelText('Neue PIN')).toHaveValue('');
        expect(screen.queryByText('sensitive detail')).not.toBeInTheDocument();
        const writes = transport.mock.calls.filter(([, options]) => options?.method === 'POST');
        expect(writes).toHaveLength(1);
        expect(writes[0][0]).toBe('/api/adminconsole/users/1/pin');
        expect(JSON.parse(String(writes[0][1]?.body))).toEqual({ newPin: 'n3w-pin' });
    });

    it('requires explicit confirmation to remove a PIN', async () => {
        const user = userEvent.setup();
        const { client, transport } = await readyClient(fixture(), (path, options) =>
            path === '/users/1/pin' && options?.method === 'DELETE'
                ? new Response(null, { status: 204 })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Manage PIN' }));
        await user.click(screen.getByLabelText('I want to remove PIN protection for this user.'));
        await user.click(screen.getByRole('button', { name: 'Remove PIN' }));
        await screen.findByRole('status');
        expect(transport.mock.calls.find(([, options]) => options?.method === 'DELETE')?.[0]).toBe(
            '/api/adminconsole/users/1/pin',
        );
    });

    it('confirms assignment changes and returns a device to its own default user via null', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path.includes('/assignment') && options?.method === 'PATCH') {
                values['/devices'][0].assignedUser = 99;
                values['/devices'][0].operatingUser = 99;
                return json(values['/devices'][0]);
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(screen.getAllByRole('button', { name: 'Change assignment' })[0]);
        const editor = form('Change assignment: Child tablet');
        await user.selectOptions(editor.getByLabelText('Assigned to'), '');
        await user.click(editor.getByRole('button', { name: 'Save' }));
        expect(patchCalls(transport)).toHaveLength(0);
        await user.click(editor.getByRole('checkbox'));
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        expect(JSON.parse(String(patchCalls(transport)[0][1]?.body))).toEqual({ userId: null });
    });

    it('patches time rules and list selections without bonus or profile ownership metadata', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) => {
            if (path === '/userprofiles/10/settings' && options?.method === 'PATCH') {
                Object.assign(values['/userprofiles'][0], JSON.parse(String(options.body)));
                return json(values['/userprofiles'][0]);
            }
        });
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Edit protection profile' }));
        const editor = form('Edit protection profile');
        fireEvent.change(editor.getByLabelText('Monday'), { target: { value: '75' } });
        await user.click(editor.getByRole('checkbox', { name: 'Games' }));
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        expect(JSON.parse(String(patchCalls(transport)[0][1]?.body))).toEqual({
            maxUsageTimeByDay: { MONDAY: 75 },
            inaccessibleSitesPackages: [],
        });
        expect(values['/userprofiles'][0].bonusTimeUsage?.bonusMinutes).toBe(15);
    });

    it('creates profiles from real templates and preserves end-of-day and weekday rules', async () => {
        const user = userEvent.setup();
        const values = fixture();
        values['/userprofiles'].push({
            ...structuredClone(childProfile),
            id: 20,
            name: 'Template',
            builtin: true,
            maxUsageTimeByDay: Object.fromEntries(weekDays.map((day) => [day, 90])),
        });
        const { client, transport } = await readyClient(values, (path, options) =>
            path === '/userprofiles/managed' && options?.method === 'POST'
                ? json({
                      ...JSON.parse(String(options.body)),
                      id: 30,
                      builtin: false,
                      standard: false,
                      hidden: false,
                      forSingleUser: true,
                  })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        const template = screen.getByRole('article', { name: 'Template' });
        expect(
            within(template).queryByRole('button', { name: 'Edit protection profile' }),
        ).not.toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Add protection profile' }));
        const editor = form('Add protection profile');
        await user.type(editor.getByLabelText('Name'), 'New rules');
        await user.selectOptions(editor.getByLabelText('Apply template'), '20');
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        const body = JSON.parse(
            String(
                transport.mock.calls.find(([, options]) => options?.method === 'POST')?.[1]?.body,
            ),
        );
        expect(body.name).toBe('New rules');
        expect(body.internetAccessContingents).toEqual([
            { onDay: 8, fromMinutes: 480, tillMinutes: 1440, totalMinutes: 90 },
        ]);
        expect(body.maxUsageTimeByDay.SUNDAY).toBe(90);
        expect(body.parentalControlSettingValidated).toBe(false);
        for (const key of ['id', 'builtin', 'forSingleUser', 'bonusTimeUsage', 'internetBlocked'])
            expect(body).not.toHaveProperty(key);
    });

    it('blocks invalid quotas, backwards time windows and deletion of assigned profiles', async () => {
        const user = userEvent.setup();
        const { client, transport } = await readyClient();
        render(<FamilyPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Edit protection profile' }));
        const editor = form('Edit protection profile');
        fireEvent.change(editor.getByLabelText('Monday'), { target: { value: '1441' } });
        await user.click(editor.getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Daily limits');
        fireEvent.change(editor.getByLabelText('Monday'), { target: { value: '60' } });
        await user.click(editor.getByLabelText('End of day (24:00)'));
        fireEvent.change(editor.getByLabelText('Until'), { target: { value: '07:00' } });
        await user.click(editor.getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('Time windows need');
        expect(patchCalls(transport)).toHaveLength(0);
        await user.click(editor.getByRole('button', { name: 'Cancel' }));
        await user.click(screen.getByRole('button', { name: 'Delete protection profile' }));
        expect(form('Delete protection profile: Child rules').getByRole('checkbox')).toBeDisabled();
    });

    it('loads custom domains and saves current metadata without overwriting a stale domain edit', async () => {
        const user = userEvent.setup();
        const values = fixture();
        let domains = ['school.example'];
        const { client, transport } = await readyClient(values, (path) =>
            path === '/filterlists/200/domains' ? json(domains) : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        const list = screen.getByRole('heading', { name: 'School' }).closest('li')!;
        await user.click(within(list).getByRole('button', { name: 'Edit filter list' }));
        const input = await screen.findByLabelText('Domains');
        expect(input).toHaveValue('school.example');
        fireEvent.change(input, { target: { value: 'edited.example' } });
        domains = ['remote.example'];
        await user.click(form('Edit filter list: School').getByRole('button', { name: 'Save' }));
        expect(await screen.findByRole('alert')).toHaveTextContent('The settings have changed');
        expect(
            transport.mock.calls.filter(([, options]) => options?.method === 'PUT'),
        ).toHaveLength(0);
    });

    it('lets built-in lists change only their active state, without editing domains or deleting them', async () => {
        const user = userEvent.setup();
        const values = fixture();
        const { client, transport } = await readyClient(values, (path, options) =>
            path === '/filterlists/100/update?filterType=blacklist' && options?.method === 'PUT'
                ? json({ ...values['/filterlists'][0], disabled: true })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        const list = screen.getByRole('heading', { name: 'Games' }).closest('li')!;
        expect(
            within(list).queryByRole('button', { name: 'Delete filter list' }),
        ).not.toBeInTheDocument();
        await user.click(within(list).getByRole('button', { name: 'Edit filter list' }));
        const editor = form('Edit filter list: Games');
        expect(editor.queryByLabelText('Domains')).not.toBeInTheDocument();
        await user.click(editor.getByRole('checkbox', { name: 'List enabled' }));
        await user.click(editor.getByRole('button', { name: 'Save' }));
        await screen.findByRole('status');
        const body = JSON.parse(
            String(
                transport.mock.calls.find(([, options]) => options?.method === 'PUT')?.[1]?.body,
            ),
        );
        expect(body.disabled).toBe(true);
        expect(body.builtin).toBe(true);
        expect(body).not.toHaveProperty('domains');
    });

    it('keeps an ambiguous write pending once, refetches actual state, and never exposes raw server errors', async () => {
        const user = userEvent.setup();
        const values = fixture();
        let complete!: (response: Response) => void;
        const { client, transport } = await readyClient(values, (path, options) =>
            path === '/users/1/settings' && options?.method === 'PATCH'
                ? new Promise((resolve) => {
                      complete = resolve;
                  })
                : undefined,
        );
        render(<FamilyPage client={client} locale="en" />);
        await screen.findByRole('heading', { name: 'Mika' });
        await user.click(within(card('Mika')).getByRole('button', { name: 'Edit user' }));
        const editor = form('Edit user');
        fireEvent.change(editor.getByLabelText('Name'), { target: { value: 'Committed' } });
        fireEvent.submit(screen.getByRole('form', { name: 'Edit user' }));
        fireEvent.submit(screen.getByRole('form', { name: 'Edit user' }));
        await waitFor(() => expect(patchCalls(transport)).toHaveLength(1));
        expect(editor.getByRole('button', { name: 'Cancel' })).toBeDisabled();
        values['/users'][0].name = 'Committed';
        await act(async () => complete(json('private failure', 500)));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The change could not be confirmed',
        );
        expect(screen.getByRole('heading', { name: 'Committed' })).toBeInTheDocument();
        expect(screen.queryByText('private failure')).not.toBeInTheDocument();
        expect(editor.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
    });
});

it('validates domain input and normalizes international names without treating paths as domains', () => {
    expect(parseDomains(' Example.org\n\nbücher.de\nexample.org ')).toEqual([
        'example.org',
        'xn--bcher-kva.de',
    ]);
    for (const value of [
        'https://example.org/path',
        'example.org/path',
        'user@example.org',
        '',
        '*.example.org',
        'white space.org',
    ])
        expect(() => parseDomains(value)).toThrow();
});
