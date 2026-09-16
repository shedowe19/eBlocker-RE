// SPDX-License-Identifier: EUPL-1.2
import { afterEach, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { ConsoleClient } from './client';
import { json, token } from '../test/fixtures';
afterEach(() => vi.useRealTimers());

it.each([
    ['/wireguard/profiles', 'GET'],
    ['/wireguard/profiles/home', 'GET'],
    ['/wireguard/profiles/home', 'PUT'],
    ['/wireguard/profiles/home', 'DELETE'],
    ['/wireguard/profiles/home/connect', 'POST'],
    ['/wireguard/profiles/home/disconnect', 'POST'],
    ['/wireguard/profiles/home/cancel', 'POST'],
] as const)(
    'authenticates the exact control route %s %s and validates its response',
    async (path, method) => {
        const transport = vi
            .fn<typeof fetch>()
            .mockImplementation(async (url) =>
                String(url).includes('/authentication/')
                    ? json(token())
                    : json({ schemaVersion: 1, data: true }),
            );
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const body = method === 'POST' || method === 'PUT' ? { confirmed: true } : undefined;
        expect(
            await client.wireGuardControl(
                path,
                method,
                body,
                z.object({ schemaVersion: z.literal(1), data: z.boolean() }),
            ),
        ).toEqual({ schemaVersion: 1, data: true });
        const [url, options] = transport.mock.lastCall!;
        expect(url).toBe(`/api/adminconsole${path}`);
        expect(options?.method).toBe(method);
        expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer test-token');
        expect(options?.body).toBe(body ? JSON.stringify(body) : undefined);
    },
);

it.each([
    ['/wireguard/validate', 'POST'],
    ['/wireguard/profiles?all=true', 'GET'],
    ['/wireguard/profiles/../devices', 'GET'],
    ['/wireguard/profiles/Home', 'GET'],
    ['/wireguard/profiles/home/reboot', 'POST'],
    ['/wireguard/profiles/home/connect', 'GET'],
    ['/wireguard/profiles', 'DELETE'],
    ['/wireguard/profiles/home', 'POST'],
] as const)('rejects control routing outside the contract: %s %s', async (path, method) => {
    const transport = vi.fn<typeof fetch>();
    const client = new ConsoleClient(transport);
    await expect(
        client.wireGuardControl(path, method, undefined, z.unknown()),
    ).rejects.toMatchObject({ code: 'validation' });
    expect(transport).not.toHaveBeenCalled();
});

it('allows the backend deadline to complete and times out after 45 seconds', async () => {
    vi.useFakeTimers();
    const transport = vi.fn<typeof fetch>().mockImplementation(async (url, options) => {
        if (String(url).includes('/authentication/')) return json(token());
        return new Promise<Response>((_resolve, reject) =>
            options?.signal?.addEventListener('abort', () =>
                reject(new DOMException('Aborted', 'AbortError')),
            ),
        );
    });
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    const pending = client.wireGuardControl(
        '/wireguard/profiles/home/connect',
        'POST',
        {},
        z.unknown(),
    );
    const rejected = expect(pending).rejects.toMatchObject({ code: 'timeout' });
    await vi.advanceTimersByTimeAsync(40_000);
    expect(transport.mock.lastCall?.[1]?.signal?.aborted).toBe(false);
    await vi.advanceTimersByTimeAsync(5_000);
    await rejected;
});
