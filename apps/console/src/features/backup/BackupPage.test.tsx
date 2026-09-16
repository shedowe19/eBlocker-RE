// SPDX-License-Identifier: EUPL-1.2
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { BackupPage } from './BackupPage';
import { backupReferenceSchema, MAX_BACKUP_BYTES } from './contracts';

const reference = { fileReference: 'eblocker-config-12345.eblcfg', passwordRequired: true };
const result = { warnings: [] };
type Handler = (path: string, init?: RequestInit) => Response | Promise<Response> | undefined;
async function connected(handler?: Handler) {
    const transport = vi.fn<typeof fetch>().mockImplementation(async (url, init) => {
        const path = String(url);
        if (path.endsWith('/authentication/token/ADMINCONSOLE')) return json(token());
        const custom = handler?.(path, init);
        if (custom !== undefined) return custom;
        if (path === '/api/configbackup/upload') return json(reference);
        if (path === '/api/configbackup/verify' || path === '/api/configbackup/import')
            return json(result);
        if (path === '/api/configbackup/export')
            return json({ ...result, configBackupReference: reference });
        if (path === `/api/configbackup/download/${reference.fileReference}`)
            return new Response('archive', {
                headers: {
                    'Content-Type': 'application/octet-stream',
                    'Content-Disposition':
                        'attachment; filename="eblocker-config-2026-09-16.eblcfg"',
                },
            });
        if (path === '/api/adminconsole/systemstatus/reboot')
            return new Response(null, { status: 204 });
        throw new Error(`Unexpected backup request: ${init?.method} ${path}`);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
const archive = () =>
    new File(['synthetic archive'], 'personal.eblcfg', { type: 'application/octet-stream' });
const callsTo = (transport: ReturnType<typeof vi.fn<typeof fetch>>, path: string) =>
    transport.mock.calls.filter(([url]) => String(url) === `/api/configbackup/${path}`);
let createUrl: ReturnType<typeof vi.fn<(file: Blob | MediaSource) => string>>;
let revokeUrl: ReturnType<typeof vi.fn<(url: string) => void>>;
beforeEach(() => {
    createUrl = vi.fn(() => 'blob:test-backup');
    revokeUrl = vi.fn();
    vi.stubGlobal(
        'URL',
        class extends URL {
            static createObjectURL = createUrl;
            static revokeObjectURL = revokeUrl;
        },
    );
});
afterEach(() => vi.unstubAllGlobals());

async function upload(user: ReturnType<typeof userEvent.setup>, locale: 'de' | 'en' = 'en') {
    await user.upload(
        screen.getByLabelText(locale === 'de' ? 'Sicherungsdatei' : 'Backup file'),
        archive(),
    );
    await user.click(
        screen.getByRole('button', {
            name: locale === 'de' ? 'Sicherung hochladen' : 'Upload backup',
        }),
    );
    await screen.findByText('personal.eblcfg');
}

describe('configuration backup transfers', () => {
    it.each(['de', 'en'] as const)(
        'exports encrypted keys in %s, clears inputs and downloads with a safe filename',
        async (locale) => {
            const user = userEvent.setup();
            const { client, transport } = await connected();
            const view = render(<BackupPage client={client} locale={locale} />);
            await user.type(
                screen.getByLabelText(locale === 'de' ? 'Sicherungspasswort' : 'Backup password'),
                'private-pass',
            );
            await user.type(
                screen.getByLabelText(
                    locale === 'de' ? 'Sicherungspasswort wiederholen' : 'Repeat backup password',
                ),
                'private-pass',
            );
            await user.click(
                screen.getByRole('button', {
                    name: locale === 'de' ? 'Sicherung erstellen' : 'Create backup',
                }),
            );
            await user.click(
                await screen.findByRole('button', {
                    name: locale === 'de' ? 'Download bereitstellen' : 'Prepare download',
                }),
            );
            const link = await screen.findByRole('link', {
                name: /eblocker-config-2026-09-16.eblcfg/,
            });
            expect(link).toHaveAttribute('href', 'blob:test-backup');
            expect(link).toHaveAttribute('download', 'eblocker-config-2026-09-16.eblcfg');
            expect(JSON.parse(String(callsTo(transport, 'export')[0][1]?.body))).toEqual({
                passwordRequired: true,
                password: 'private-pass',
            });
            expect(
                screen.getByLabelText(locale === 'de' ? 'Sicherungspasswort' : 'Backup password'),
            ).toHaveValue('');
            expect(
                screen.getByLabelText(
                    locale === 'de' ? 'Sicherungspasswort wiederholen' : 'Repeat backup password',
                ),
            ).toHaveValue('');
            for (const [url, init] of transport.mock.calls.slice(1)) {
                expect(String(url)).not.toMatch(/private-pass|Authorization|token=/);
                expect(new Headers(init?.headers).get('Authorization')).toBe('Bearer test-token');
            }
            view.unmount();
            expect(revokeUrl).toHaveBeenCalledWith('blob:test-backup');
        },
    );

    it('validates matching passwords and deliberately omits the secret when keys are excluded', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected();
        render(<BackupPage client={client} locale="en" />);
        await user.click(screen.getByRole('button', { name: 'Create backup' }));
        expect(screen.getByRole('alert')).toHaveTextContent('1 to 50');
        expect(callsTo(transport, 'export')).toHaveLength(0);
        await user.type(screen.getByLabelText('Backup password'), 'one');
        await user.type(screen.getByLabelText('Repeat backup password'), 'two');
        await user.click(screen.getByRole('button', { name: 'Create backup' }));
        expect(screen.getByRole('alert')).toHaveTextContent('do not match');
        await user.click(screen.getByLabelText('Include sensitive keys protected by a password'));
        await user.click(screen.getByRole('button', { name: 'Create backup' }));
        await screen.findByRole('button', { name: 'Prepare download' });
        expect(JSON.parse(String(callsTo(transport, 'export')[0][1]?.body))).toEqual({
            passwordRequired: false,
        });
    });

    it('rejects empty, oversized and wrong-extension files before uploading', async () => {
        const { client, transport } = await connected();
        render(<BackupPage client={client} locale="en" />);
        for (const file of [
            new File([], 'empty.eblcfg'),
            new File([new Uint8Array(MAX_BACKUP_BYTES + 1)], 'large.eblcfg'),
            new File(['data'], 'private.txt'),
        ]) {
            fireEvent.change(screen.getByLabelText('Backup file'), { target: { files: [file] } });
            fireEvent.click(screen.getByRole('button', { name: 'Upload backup' }));
            expect(screen.getByRole('alert')).toHaveTextContent(
                'non-empty .eblcfg file no larger than 1 MiB',
            );
        }
        expect(callsTo(transport, 'upload')).toHaveLength(0);
    });

    it.each(['de', 'en'] as const)(
        'verifies raw uploads, confirms restore and separately confirms restart in %s',
        async (locale) => {
            const user = userEvent.setup();
            const { client, transport } = await connected();
            render(<BackupPage client={client} locale={locale} />);
            await upload(user, locale);
            const uploadCall = callsTo(transport, 'upload')[0];
            expect(uploadCall[1]?.method).toBe('PUT');
            expect(uploadCall[1]?.body).toBeInstanceOf(File);
            expect(new Headers(uploadCall[1]?.headers).get('Content-Type')).toBe(
                'application/octet-stream',
            );
            expect(
                screen.queryByLabelText(locale === 'de' ? 'Sicherungsdatei' : 'Backup file'),
            ).not.toBeInTheDocument();
            // There are two password inputs across export and restore; scope the restore region.
            const region = screen.getByRole('region', {
                name: locale === 'de' ? 'Konfiguration wiederherstellen' : 'Restore configuration',
            });
            await user.type(
                within(region).getByLabelText(
                    locale === 'de' ? 'Sicherungspasswort' : 'Backup password',
                ),
                'restore-secret',
            );
            await user.click(
                within(region).getByRole('button', {
                    name: locale === 'de' ? 'Sicherung prüfen' : 'Verify backup',
                }),
            );
            await user.click(
                await screen.findByRole('button', {
                    name: locale === 'de' ? 'Wiederherstellung vorbereiten' : 'Prepare restore',
                }),
            );
            expect(screen.getByRole('dialog')).toHaveTextContent(
                locale === 'de' ? 'überschreibt' : 'overwrites',
            );
            expect(callsTo(transport, 'import')).toHaveLength(0);
            await user.click(
                screen.getByRole('button', {
                    name: locale === 'de' ? 'Jetzt wiederherstellen' : 'Restore now',
                }),
            );
            const restart = await screen.findByRole('button', {
                name: locale === 'de' ? 'eBlocker neu starten' : 'Restart eBlocker',
            });
            expect(JSON.parse(String(callsTo(transport, 'verify')[0][1]?.body))).toEqual({
                fileReference: reference.fileReference,
                password: 'restore-secret',
            });
            expect(JSON.parse(String(callsTo(transport, 'import')[0][1]?.body))).toEqual({
                fileReference: reference.fileReference,
                password: 'restore-secret',
                clientFileName: 'personal.eblcfg',
            });
            expect(transport.mock.calls.some(([url]) => String(url).endsWith('/reboot'))).toBe(
                false,
            );
            await user.click(restart);
            expect(screen.getByRole('dialog')).toHaveTextContent(
                locale === 'de' ? 'unterbricht' : 'interrupts',
            );
            await user.click(
                screen.getByRole('button', {
                    name: locale === 'de' ? 'Jetzt neu starten' : 'Restart now',
                }),
            );
            await waitFor(() =>
                expect(
                    transport.mock.calls.filter(([url]) =>
                        String(url).endsWith('/systemstatus/reboot'),
                    ),
                ).toHaveLength(1),
            );
            expect(within(region).queryByDisplayValue('restore-secret')).not.toBeInTheDocument();
        },
    );

    it('shows verification warnings when encrypted keys are skipped and cancellation sends no import', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path.endsWith('/verify')
                ? json({
                      warnings: [
                          { id: 'NO_PASSWORD_HTTPS_CA_NOT_IMPORTED', itemId: null, itemName: null },
                          { id: 'ITEM_NOT_IMPORTED', itemId: 'VPN_PROFILE', itemName: 'Work VPN' },
                      ],
                  })
                : undefined,
        );
        render(<BackupPage client={client} locale="en" />);
        await upload(user);
        await user.click(screen.getByLabelText('Restore sensitive keys from the backup'));
        await user.click(screen.getByRole('button', { name: 'Verify backup' }));
        expect(
            await screen.findByText(
                'Without a password, the HTTPS certificate authority was not restored.',
            ),
        ).toBeInTheDocument();
        expect(screen.getByText('An item could not be restored. (Work VPN)')).toBeInTheDocument();
        expect(JSON.parse(String(callsTo(transport, 'verify')[0][1]?.body))).toEqual({
            fileReference: reference.fileReference,
        });
        const opener = screen.getByRole('button', { name: 'Prepare restore' });
        // Simulate the browser blurring a disabled opener before the dialog effect.
        (document.activeElement as HTMLElement).blur();
        fireEvent.click(opener);
        expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
        await user.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(opener).toHaveFocus();
        expect(callsTo(transport, 'import')).toHaveLength(0);
    });

    it('clears failed verification passwords and never permits an unverified restore', async () => {
        const user = userEvent.setup();
        const { client } = await connected((path) =>
            path.endsWith('/verify')
                ? json('adminconsole.config_backup.error.invalid_password', 400)
                : undefined,
        );
        render(<BackupPage client={client} locale="en" />);
        await upload(user);
        const region = screen.getByRole('region', { name: 'Restore configuration' });
        await user.type(within(region).getByLabelText('Backup password'), 'bad-password');
        await user.click(screen.getByRole('button', { name: 'Verify backup' }));
        await screen.findByRole('alert');
        expect(within(region).getByLabelText('Backup password')).toHaveValue('');
        expect(screen.queryByRole('button', { name: 'Prepare restore' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Restore now' })).not.toBeInTheDocument();
    });

    it('treats failed imports as uncertain and requires a fresh upload instead of blind replay', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path.endsWith('/import')
                ? json({}, 503)
                : path.endsWith('/upload')
                  ? json({ ...reference, passwordRequired: false })
                  : undefined,
        );
        render(<BackupPage client={client} locale="en" />);
        await upload(user);
        await user.click(screen.getByRole('button', { name: 'Verify backup' }));
        await user.click(await screen.findByRole('button', { name: 'Prepare restore' }));
        await user.click(screen.getByRole('button', { name: 'Restore now' }));
        await screen.findByText(/may already have changed settings/);
        expect(callsTo(transport, 'import')).toHaveLength(1);
        expect(screen.queryByRole('button', { name: 'Restore now' })).not.toBeInTheDocument();
        expect(screen.getByRole('link', { name: 'Open system and restart' })).toHaveAttribute(
            'href',
            '#/system',
        );
    });

    it('aborts reset uploads and ignores late completion, retaining neither filename nor reference', async () => {
        let resolve!: (response: Response) => void;
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path.endsWith('/upload')
                ? new Promise<Response>((done) => {
                      resolve = done;
                  })
                : undefined,
        );
        render(<BackupPage client={client} locale="en" />);
        await user.upload(screen.getByLabelText('Backup file'), archive());
        await user.click(screen.getByRole('button', { name: 'Upload backup' }));
        const region = screen.getByRole('region', { name: 'Restore configuration' });
        await user.click(
            within(region).getByRole('button', { name: 'Reset selection and sensitive inputs' }),
        );
        expect(callsTo(transport, 'upload')[0][1]?.signal?.aborted).toBe(true);
        resolve(json(reference));
        await waitFor(() =>
            expect(screen.getByRole('button', { name: 'Upload backup' })).toBeEnabled(),
        );
        expect(screen.queryByText('personal.eblcfg')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: 'Verify backup' })).not.toBeInTheDocument();
    });

    it('strips echoed secrets and refuses unsafe backup references', () => {
        expect(backupReferenceSchema.parse({ ...reference, password: 'server-echo' })).toEqual(
            reference,
        );
        expect(
            backupReferenceSchema.safeParse({ ...reference, fileReference: '../private.eblcfg' })
                .success,
        ).toBe(false);
        expect(
            backupReferenceSchema.safeParse({ ...reference, passwordRequired: 'true' }).success,
        ).toBe(false);
    });
});
