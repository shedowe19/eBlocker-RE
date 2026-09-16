// SPDX-License-Identifier: EUPL-1.2
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from '../../api/client';
import { json, token } from '../../test/fixtures';
import { DiagnosticsPage } from './DiagnosticsPage';

type Handler = (path: string, init?: RequestInit) => Response | Promise<Response> | undefined;
async function connected(handler?: Handler) {
    const transport = vi.fn<typeof fetch>().mockImplementation(async (url, init) => {
        const path = String(url).replace('/api/adminconsole', '');
        if (path === '/authentication/token/ADMINCONSOLE') return json(token());
        const custom = handler?.(path, init);
        if (custom !== undefined) return custom;
        if (path === '/diagnostics/report')
            return init?.method === 'POST'
                ? new Response(null, { status: 204 })
                : json('NOT_STARTED');
        if (path === '/diagnostics/download')
            return new Response('zip fixture', {
                headers: {
                    'Content-Type': 'application/octet-stream',
                    'Content-Disposition': 'attachment; filename="eblocker-diagnostics-report.zip"',
                },
            });
        throw new Error(`Unexpected diagnostic request: ${init?.method} ${path}`);
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    return { client, transport };
}
let createUrl: ReturnType<typeof vi.fn<(file: Blob | MediaSource) => string>>;
let revokeUrl: ReturnType<typeof vi.fn<(url: string) => void>>;
beforeEach(() => {
    createUrl = vi.fn(() => 'blob:test-diagnostic');
    revokeUrl = vi.fn();
    vi.stubGlobal(
        'URL',
        class extends URL {
            static createObjectURL = createUrl;
            static revokeObjectURL = revokeUrl;
        },
    );
});
afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
});
const writes = (transport: ReturnType<typeof vi.fn<typeof fetch>>) =>
    transport.mock.calls.filter(([, init]) => init?.method === 'POST');

describe('diagnostic reports', () => {
    it.each(['de', 'en'] as const)(
        'requires a privacy confirmation, reads status and downloads via authenticated blob in %s',
        async (locale) => {
            let started = false;
            const user = userEvent.setup();
            const { client, transport } = await connected((path, init) => {
                if (path !== '/diagnostics/report') return undefined;
                if (init?.method === 'POST') {
                    started = true;
                    return new Response(null, { status: 204 });
                }
                return json(started ? 'FINISHED' : 'NOT_STARTED');
            });
            const view = render(<DiagnosticsPage client={client} locale={locale} />);
            await user.click(
                await screen.findByRole('button', {
                    name:
                        locale === 'de' ? 'Diagnosebericht erstellen' : 'Create diagnostic report',
                }),
            );
            expect(screen.getByRole('dialog')).toHaveTextContent(
                locale === 'de' ? 'persönliche Daten' : 'personal information',
            );
            expect(writes(transport)).toHaveLength(0);
            await user.click(
                screen.getByRole('button', {
                    name: locale === 'de' ? 'Bericht jetzt erstellen' : 'Create report now',
                }),
            );
            await user.click(
                await screen.findByRole('button', {
                    name: locale === 'de' ? 'Download bereitstellen' : 'Prepare download',
                }),
            );
            const link = await screen.findByRole('link', {
                name: /eblocker-diagnostics-report.zip/,
            });
            expect(link).toHaveAttribute('href', 'blob:test-diagnostic');
            expect(link).toHaveAttribute('download', 'eblocker-diagnostics-report.zip');
            expect(writes(transport)).toHaveLength(1);
            expect(JSON.parse(String(writes(transport)[0][1]?.body))).toEqual({});
            const request = transport.mock.calls.find(([url]) =>
                String(url).endsWith('/diagnostics/download'),
            )!;
            expect(new Headers(request[1]?.headers).get('Authorization')).toBe('Bearer test-token');
            expect(String(request[0])).not.toContain('?');
            await user.click(
                screen.getByRole('button', {
                    name:
                        locale === 'de'
                            ? 'Lokale Downloadkopie freigeben'
                            : 'Release local download copy',
                }),
            );
            expect(revokeUrl).toHaveBeenCalledWith('blob:test-diagnostic');
            expect(screen.queryByRole('link')).not.toBeInTheDocument();
            expect(transport.mock.calls.some(([, init]) => init?.method === 'DELETE')).toBe(false);
            view.unmount();
        },
    );

    it('does not restart a report created by another administrator while confirmation was open', async () => {
        let reads = 0;
        const user = userEvent.setup();
        const { client, transport } = await connected((path, init) =>
            path === '/diagnostics/report' && init?.method === 'GET'
                ? json(++reads === 1 ? 'NOT_STARTED' : 'PENDING')
                : undefined,
        );
        render(<DiagnosticsPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Create diagnostic report' }));
        await user.click(screen.getByRole('button', { name: 'Create report now' }));
        await screen.findByText('Generation in progress');
        expect(writes(transport)).toHaveLength(0);
        expect(screen.getByRole('button', { name: 'Creating report …' })).toBeDisabled();
    });

    it('polls only pending reports and stops after completion', async () => {
        let reads = 0;
        const { client } = await connected((path) =>
            path === '/diagnostics/report'
                ? json(++reads === 1 ? 'PENDING' : 'FINISHED')
                : undefined,
        );
        vi.useFakeTimers();
        render(<DiagnosticsPage client={client} locale="en" />);
        await act(async () => {
            await Promise.resolve();
        });
        expect(screen.getByText('Generation in progress')).toBeInTheDocument();
        await act(async () => {
            await vi.advanceTimersByTimeAsync(2000);
        });
        expect(screen.getByText('Report available')).toBeInTheDocument();
        await act(async () => {
            await vi.advanceTimersByTimeAsync(6000);
        });
        expect(reads).toBe(2);
    });

    it.each([json({ state: 'FINISHED' }), json('FINISHED', 503)])(
        'locks actions for malformed or failed status loads',
        async (response) => {
            const { client, transport } = await connected((path) =>
                path === '/diagnostics/report' ? response : undefined,
            );
            render(<DiagnosticsPage client={client} locale="en" />);
            await screen.findByRole('alert');
            expect(
                screen.queryByRole('button', { name: 'Create diagnostic report' }),
            ).not.toBeInTheDocument();
            expect(
                screen.queryByRole('button', { name: 'Prepare download' }),
            ).not.toBeInTheDocument();
            expect(writes(transport)).toHaveLength(0);
        },
    );

    it('shows generation failure and preserves uncertain POST outcomes for manual refresh', async () => {
        const user = userEvent.setup();
        const { client, transport } = await connected((path, init) =>
            path === '/diagnostics/report'
                ? init?.method === 'POST'
                    ? json({}, 503)
                    : json('ERROR')
                : undefined,
        );
        render(<DiagnosticsPage client={client} locale="en" />);
        await screen.findByText('Report generation failed. You can request it again.');
        await user.click(screen.getByRole('button', { name: 'Create diagnostic report' }));
        await user.click(screen.getByRole('button', { name: 'Create report now' }));
        await screen.findByText(/The request could not be confirmed/);
        expect(writes(transport)).toHaveLength(1);
        expect(screen.getByRole('button', { name: 'Create diagnostic report' })).toBeDisabled();
    });

    it('does not create a download after logout while the binary response is in flight', async () => {
        let resolve!: (response: Response) => void;
        const user = userEvent.setup();
        const { client } = await connected((path) =>
            path === '/diagnostics/report'
                ? json('FINISHED')
                : path === '/diagnostics/download'
                  ? new Promise<Response>((done) => {
                        resolve = done;
                    })
                  : undefined,
        );
        render(<DiagnosticsPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Prepare download' }));
        act(() => client.logout());
        await act(async () => {
            resolve(
                new Response('private report', {
                    headers: { 'Content-Type': 'application/octet-stream' },
                }),
            );
        });
        await screen.findByRole('alert');
        expect(createUrl).not.toHaveBeenCalled();
        expect(screen.queryByRole('link')).not.toBeInTheDocument();
    });

    it('aborts a pending download on unmount and never publishes its late response', async () => {
        let resolve!: (response: Response) => void;
        const user = userEvent.setup();
        const { client, transport } = await connected((path) =>
            path === '/diagnostics/report'
                ? json('FINISHED')
                : path === '/diagnostics/download'
                  ? new Promise<Response>((done) => {
                        resolve = done;
                    })
                  : undefined,
        );
        const view = render(<DiagnosticsPage client={client} locale="en" />);
        await user.click(await screen.findByRole('button', { name: 'Prepare download' }));
        const request = transport.mock.calls.find(([url]) =>
            String(url).endsWith('/diagnostics/download'),
        )!;
        view.unmount();
        expect(request[1]?.signal?.aborted).toBe(true);
        await act(async () => {
            resolve(
                new Response('private report', {
                    headers: { 'Content-Type': 'application/octet-stream' },
                }),
            );
        });
        expect(createUrl).not.toHaveBeenCalled();
    });
});
