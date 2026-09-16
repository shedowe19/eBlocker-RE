// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { SecurityPage } from './SecurityPage';

async function readyClient(
    passwordRequired: boolean,
    write?: (options?: RequestInit) => Promise<Response>,
    wait = 0,
) {
    const writes: RequestInit[] = [];
    const transport = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
        const path = String(input);
        if (path.endsWith('/authentication/wait')) return json(wait);
        if (path.includes('/authentication/token/') || path.includes('/authentication/login/'))
            return json(token(passwordRequired));
        if (options) writes.push(options);
        return write ? write(options) : new Response(null, { status: 204 });
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    if (passwordRequired) await client.login('existing password');
    return { client, transport, writes };
}

describe('administrator password settings', () => {
    it('shows the real protected state and changes a password with the existing credential contract', async () => {
        const user = userEvent.setup();
        const { client, transport, writes } = await readyClient(true);
        const logout = vi.spyOn(client, 'logout');
        const storage = vi.spyOn(Storage.prototype, 'setItem');
        render(<SecurityPage client={client} locale="en" />);
        expect(screen.getByText('Enabled')).toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Change password' }));
        await user.type(screen.getByLabelText('Current administrator password'), 'old password');
        await user.type(screen.getByLabelText('New administrator password'), ' new password ');
        await user.type(screen.getByLabelText('Repeat new password'), ' new password ');
        await user.click(screen.getByRole('button', { name: 'Save password' }));
        await screen.findByText('The change was saved. Sign in again.');
        expect(writes).toHaveLength(1);
        expect(JSON.parse(String(writes[0].body))).toEqual({
            currentPassword: 'old password',
            newPassword: ' new password ',
        });
        expect(transport).toHaveBeenCalledWith(
            '/api/adminconsole/authentication/enable',
            expect.objectContaining({ method: 'POST' }),
        );
        expect(logout).toHaveBeenCalledOnce();
        expect(storage).not.toHaveBeenCalled();
        expect(screen.queryByLabelText('New administrator password')).not.toBeInTheDocument();
    });

    it('enables password protection without requesting a nonexistent old password', async () => {
        const user = userEvent.setup();
        const { client, writes } = await readyClient(false);
        render(<SecurityPage client={client} locale="de" />);
        expect(screen.getByText('Deaktiviert')).toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Passwortschutz aktivieren' }));
        expect(screen.queryByLabelText('Aktuelles Administratorpasswort')).not.toBeInTheDocument();
        await user.type(screen.getByLabelText('Neues Administratorpasswort'), 'neues passwort');
        await user.type(screen.getByLabelText('Neues Passwort wiederholen'), 'neues passwort');
        await user.click(screen.getByRole('button', { name: 'Passwort speichern' }));
        await waitFor(() => expect(client.snapshot().phase).toBe('signedOut'));
        expect(JSON.parse(String(writes[0].body))).toEqual({
            currentPassword: '',
            newPassword: 'neues passwort',
        });
    });

    it('requires explicit confirmation and the current password before disabling protection', async () => {
        const user = userEvent.setup();
        const { client, transport, writes } = await readyClient(true);
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Disable password protection' }));
        await user.type(screen.getByLabelText('Current administrator password'), 'old password');
        await user.click(screen.getByRole('button', { name: 'Disable password protection' }));
        expect(screen.getByRole('alert')).toHaveTextContent(
            'Confirm that you want to disable password protection.',
        );
        expect(writes).toHaveLength(0);
        await user.click(
            screen.getByRole('checkbox', { name: 'I want to disable password protection.' }),
        );
        await user.click(screen.getByRole('button', { name: 'Disable password protection' }));
        await waitFor(() => expect(writes).toHaveLength(1));
        expect(JSON.parse(String(writes[0].body))).toEqual({ currentPassword: 'old password' });
        expect(transport).toHaveBeenCalledWith(
            '/api/adminconsole/authentication/disable',
            expect.objectContaining({ method: 'POST' }),
        );
    });

    it('does not send mismatched or empty new passwords', async () => {
        const user = userEvent.setup();
        const { client, writes } = await readyClient(false);
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Enable password protection' }));
        await user.click(screen.getByRole('button', { name: 'Save password' }));
        expect(screen.getByRole('alert')).toHaveTextContent(
            'Enter a new password with 1 to 50 characters.',
        );
        await user.type(screen.getByLabelText('New administrator password'), 'one');
        await user.type(screen.getByLabelText('Repeat new password'), 'another');
        await user.click(screen.getByRole('button', { name: 'Save password' }));
        expect(screen.getByRole('alert')).toHaveTextContent('The new passwords do not match.');
        expect(writes).toHaveLength(0);
    });

    it('preserves long current passwords while rejecting new passwords beyond the server limit', async () => {
        const user = userEvent.setup();
        const { client, writes } = await readyClient(true);
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Change password' }));
        const current = screen.getByLabelText('Current administrator password');
        expect(current).not.toHaveAttribute('maxLength');
        fireEvent.change(current, { target: { value: 'x'.repeat(70) } });
        fireEvent.change(screen.getByLabelText('New administrator password'), {
            target: { value: 'y'.repeat(51) },
        });
        fireEvent.change(screen.getByLabelText('Repeat new password'), {
            target: { value: 'y'.repeat(51) },
        });
        fireEvent.submit(screen.getByRole('form'));
        expect(screen.getByRole('alert')).toHaveTextContent(
            'Enter a new password with 1 to 50 characters.',
        );
        expect(writes).toHaveLength(0);
        fireEvent.change(screen.getByLabelText('New administrator password'), {
            target: { value: 'y'.repeat(50) },
        });
        fireEvent.change(screen.getByLabelText('Repeat new password'), {
            target: { value: 'y'.repeat(50) },
        });
        fireEvent.submit(screen.getByRole('form'));
        await waitFor(() => expect(writes).toHaveLength(1));
        expect(JSON.parse(String(writes[0].body))).toEqual({
            currentPassword: 'x'.repeat(70),
            newPassword: 'y'.repeat(50),
        });
    });

    it('clears credentials when the server rejects new-password validation', async () => {
        const user = userEvent.setup();
        const { client } = await readyClient(false, async () =>
            json('error.credentials.invalidNewPassword', 400),
        );
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Enable password protection' }));
        await user.type(screen.getByLabelText('New administrator password'), 'password');
        await user.type(screen.getByLabelText('Repeat new password'), 'password');
        await user.click(screen.getByRole('button', { name: 'Save password' }));
        await screen.findByRole('alert');
        expect(screen.getByLabelText('New administrator password')).toHaveValue('');
        expect(screen.getByLabelText('Repeat new password')).toHaveValue('');
        expect(client.snapshot().phase).toBe('ready');
        expect(screen.queryByText('error.credentials.invalidNewPassword')).not.toBeInTheDocument();
    });

    it('offers no password-changing action while the protection status is unknown', () => {
        render(<SecurityPage client={new ConsoleClient()} locale="en" />);
        expect(screen.getByRole('status')).toHaveTextContent(
            'The current password protection status is unavailable.',
        );
        expect(
            screen.queryByRole('button', { name: 'Enable password protection' }),
        ).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Change password' })).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Disable password protection' }),
        ).not.toBeInTheDocument();
    });

    it('clears secrets after a wrong current password without expiring the valid session', async () => {
        const user = userEvent.setup();
        const { client } = await readyClient(true, async () =>
            json('error.credentials.invalid', 401),
        );
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Change password' }));
        await user.type(screen.getByLabelText('Current administrator password'), 'wrong');
        await user.type(screen.getByLabelText('New administrator password'), 'new password');
        await user.type(screen.getByLabelText('Repeat new password'), 'new password');
        await user.click(screen.getByRole('button', { name: 'Save password' }));
        await screen.findByRole('alert');
        expect(client.snapshot().phase).toBe('ready');
        expect(screen.getByLabelText('Current administrator password')).toHaveValue('');
        expect(screen.getByLabelText('New administrator password')).toHaveValue('');
        expect(screen.getByLabelText('Repeat new password')).toHaveValue('');
    });

    it('uses the server wait period to block repeated password attempts', async () => {
        const user = userEvent.setup();
        const { client, writes } = await readyClient(true, undefined, 30);
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Change password' }));
        expect(await screen.findByText('Next attempt in 30 seconds.')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Save password' })).toBeDisabled();
        fireEvent.submit(screen.getByRole('form'));
        expect(writes).toHaveLength(0);
    });

    it('clears password fields on a language change and on cancellation', async () => {
        const user = userEvent.setup();
        const { client, writes } = await readyClient(false);
        const { rerender } = render(<SecurityPage client={client} locale="de" />);
        await user.click(screen.getByRole('button', { name: 'Passwortschutz aktivieren' }));
        await user.type(screen.getByLabelText('Neues Administratorpasswort'), 'sensitive');
        rerender(<SecurityPage client={client} locale="en" />);
        expect(screen.getByLabelText('New administrator password')).toHaveValue('');
        await user.type(screen.getByLabelText('New administrator password'), 'another secret');
        await user.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(screen.getByRole('button', { name: 'Enable password protection' })).toHaveFocus();
        await user.click(screen.getByRole('button', { name: 'Enable password protection' }));
        expect(screen.getByLabelText('New administrator password')).toHaveValue('');
        expect(writes).toHaveLength(0);
    });

    it('prevents duplicate writes, including during a language switch', async () => {
        const user = userEvent.setup();
        let complete!: (response: Response) => void;
        const { client, writes } = await readyClient(
            false,
            () =>
                new Promise((resolve) => {
                    complete = resolve;
                }),
        );
        const { rerender } = render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Enable password protection' }));
        await user.type(screen.getByLabelText('New administrator password'), 'password');
        await user.type(screen.getByLabelText('Repeat new password'), 'password');
        fireEvent.submit(screen.getByRole('form'));
        fireEvent.submit(screen.getByRole('form'));
        await waitFor(() => expect(writes).toHaveLength(1));
        rerender(<SecurityPage client={client} locale="de" />);
        fireEvent.submit(screen.getByRole('form'));
        expect(screen.getByRole('button', { name: 'Abbrechen' })).toBeDisabled();
        expect(screen.getByLabelText('Neues Administratorpasswort')).toHaveValue('');
        expect(writes).toHaveLength(1);
        await act(async () => complete(new Response(null, { status: 204 })));
        expect(client.snapshot().phase).toBe('signedOut');
    });

    it('handles an uncertain write result without claiming that the password is unchanged', async () => {
        const user = userEvent.setup();
        const { client } = await readyClient(false, async () => json('secret internal error', 500));
        render(<SecurityPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Enable password protection' }));
        await user.type(screen.getByLabelText('New administrator password'), 'password');
        await user.type(screen.getByLabelText('Repeat new password'), 'password');
        await user.click(screen.getByRole('button', { name: 'Save password' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The change could not be confirmed.',
        );
        expect(screen.queryByText('secret internal error')).not.toBeInTheDocument();
        expect(screen.getByLabelText('New administrator password')).toHaveValue('');
        expect(screen.getByLabelText('New administrator password')).toBeDisabled();
        expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Sign in again' }));
        expect(client.snapshot().phase).toBe('signedOut');
    });
});
