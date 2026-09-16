// SPDX-License-Identifier: EUPL-1.2
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConsoleClient } from './client';
import { z } from 'zod';
import { request } from './http';
import { device, json, token } from '../test/fixtures';

const response = (body: unknown, status = 200) => Promise.resolve(json(body, status));
afterEach(() => vi.useRealTimers());

describe('ADMINCONSOLE contract and session isolation', () => {
    it('does not use an initial password-protected token to access devices', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(token(true)));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        expect(client.snapshot().phase).toBe('login');
        await expect(client.devices()).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(transport).toHaveBeenCalledTimes(1);
        expect(client.snapshot().phase).toBe('login');
        // Refusing a premature request is not itself proof that a login token has expired.
    });

    it('accepts the actual token response without a synthetic isLoggedIn field', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockImplementation((url) =>
                response(String(url).endsWith('/devices') ? [device] : token()),
            );
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        expect(client.snapshot().phase).toBe('ready');
        expect(await client.devices()).toEqual([device]);
        const options = transport.mock.calls[1][1]!;
        expect(new Headers(options.headers).get('Authorization')).toBe('Bearer test-token');
        expect(options).toMatchObject({
            cache: 'no-store',
            redirect: 'error',
            credentials: 'same-origin',
        });
        expect(JSON.stringify(client.snapshot())).not.toContain('test-token');
    });

    it('submits only currentPassword and does not treat bad credentials as an expired session', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token(true)))
            .mockResolvedValueOnce(json('error.credentials.invalid', 401))
            .mockResolvedValueOnce(json(-2))
            .mockResolvedValueOnce(json(token(true, 'authenticated')));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client.login('bad')).rejects.toMatchObject({ code: 'credentials' });
        expect(client.snapshot().phase).toBe('login');
        expect(await client.loginWait()).toBe(0);
        expect(new Headers(transport.mock.calls[2][1]?.headers).get('Authorization')).toBe(
            'Bearer test-token',
        );
        await client.login('correct');
        expect(client.snapshot().phase).toBe('ready');
        expect(transport.mock.calls[3][1]).toMatchObject({
            method: 'POST',
            body: '{"currentPassword":"correct"}',
        });
        expect(new Headers(transport.mock.calls[3][1]?.headers).has('Authorization')).toBe(false);
    });

    it('recognizes the existing 401 login-throttling string', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token(true)))
            .mockResolvedValueOnce(json('error.credentials.too.soon', 401));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client.login('test')).rejects.toMatchObject({ code: 'wait' });
        expect(client.snapshot().phase).toBe('login');
    });

    it.each([
        { ...token(), appContext: 'CONTROLBAR' },
        { token: 'invalid', appContext: 'ADMINCONSOLE', expiresOn: 9999999999 },
        { ...token(), expiresOn: 1 },
    ])('fails closed for a malformed, wrong-context or expired token', async (value) => {
        const client = new ConsoleClient(() => response(value));
        await client.bootstrap();
        expect(client.snapshot()).toEqual({ phase: 'error', error: 'invalidResponse' });
    });

    it('renews once for simultaneous device requests, including password-free sessions', async () => {
        let finish!: (value: Response) => void;
        const transport = vi.fn<typeof fetch>().mockImplementation((url) => {
            if (String(url).includes('/renew/'))
                return new Promise((resolve) => {
                    finish = resolve;
                });
            return response(
                String(url).endsWith('/devices')
                    ? [device]
                    : token(false, 'old', Math.floor(Date.now() / 1000) + 40),
            );
        });
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const one = client.devices();
        const two = client.devices();
        finish(json(token(false, 'renewed')));
        await Promise.all([one, two]);
        expect(
            transport.mock.calls.filter(([url]) => String(url).includes('/renew/')),
        ).toHaveLength(1);
        for (const [, options] of transport.mock.calls.filter(([url]) =>
            String(url).endsWith('/devices'),
        )) {
            expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer renewed');
        }
    });

    it('does not send a token after its expiry', async () => {
        let now = Date.now();
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(token()));
        const client = new ConsoleClient(transport, () => now);
        await client.bootstrap();
        now += 3_600_001;
        await expect(client.devices()).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(transport).toHaveBeenCalledTimes(1);
        expect(client.snapshot().phase).toBe('expired');
    });

    it('does not count automatic polling as user activity', async () => {
        let now = Date.now();
        const transport = vi
            .fn<typeof fetch>()
            .mockImplementation((url) =>
                response(String(url).endsWith('/devices') ? [device] : token()),
            );
        const client = new ConsoleClient(transport, () => now);
        await client.bootstrap();
        for (let minute = 0; minute < 19; minute++) {
            now += 60_000;
            await client.devices();
        }
        now += 60_000;
        await expect(client.devices()).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(client.snapshot().phase).toBe('expired');
        expect(transport).toHaveBeenCalledTimes(20);
    });

    it('keeps active sessions available, but activity cannot resurrect an already idle session', async () => {
        let now = Date.now();
        const client = new ConsoleClient(
            () => response(token()),
            () => now,
        );
        await client.bootstrap();
        now += 19 * 60_000;
        client.markActive();
        now += 19 * 60_000;
        client.checkIdle();
        expect(client.snapshot().phase).toBe('ready');
        now += 60_000;
        client.markActive();
        expect(client.snapshot().phase).toBe('expired');
    });

    it('does not send a protected request queued immediately before logout', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(token()));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const pending = client.devices();
        client.logout();
        await expect(pending).rejects.toMatchObject({ code: 'cancelled' });
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('rejects late login results after sign-out', async () => {
        let finish!: (value: Response) => void;
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token(true)))
            .mockImplementationOnce(
                () =>
                    new Promise((resolve) => {
                        finish = resolve;
                    }),
            );
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const login = client.login('secret');
        client.logout();
        finish(json(token()));
        await expect(login).rejects.toMatchObject({ code: 'cancelled' });
        expect(client.snapshot().phase).toBe('signedOut');
    });

    it('does not let an earlier request invalidate a newly established session', async () => {
        let finish!: (value: Response) => void;
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockImplementationOnce(
                () =>
                    new Promise((resolve) => {
                        finish = resolve;
                    }),
            )
            .mockResolvedValueOnce(json(token(false, 'new-session')));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const devices = client.devices();
        await Promise.resolve();
        await client.bootstrap();
        finish(json('error.token.invalid', 401));
        await expect(devices).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(client.snapshot().phase).toBe('ready');
    });

    it('clears a rejected authenticated session but preserves permission-denied errors', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json('denied', 403))
            .mockResolvedValueOnce(json('error.token.invalid', 401));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client.devices()).rejects.toMatchObject({ code: 'forbidden' });
        expect(client.snapshot().phase).toBe('ready');
        await expect(client.devices()).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(client.snapshot().phase).toBe('expired');
    });

    it('does not turn malformed protection settings into an empty or protected device list', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json([{ ...device, enabled: 'true' }]));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client.devices()).rejects.toMatchObject({ code: 'invalidResponse' });
    });
});

describe('transport failures', () => {
    it('rejects an HTML success response without displaying its content', async () => {
        await expect(
            request(async () => new Response('<script>private</script>'), '/devices'),
        ).rejects.toMatchObject({ code: 'invalidResponse' });
    });
    it('cancels hanging requests with a finite timeout', async () => {
        vi.useFakeTimers();
        const pending = request(
            (_url, options) =>
                new Promise((_resolve, reject) => {
                    options?.signal?.addEventListener('abort', () =>
                        reject(new DOMException('Aborted', 'AbortError')),
                    );
                }),
            '/devices',
        );
        const check = expect(pending).rejects.toMatchObject({ code: 'timeout' });
        await vi.advanceTimersByTimeAsync(12_000);
        await check;
    });
    it('preserves cancellation after headers while the response body is loading', async () => {
        const controller = new AbortController();
        let finish!: (reason: unknown) => void;
        const pending = request(
            async () =>
                ({
                    ok: true,
                    status: 200,
                    json: () =>
                        new Promise((_resolve, reject) => {
                            finish = reject;
                        }),
                }) as Response,
            '/devices',
            { signal: controller.signal },
        );
        await Promise.resolve();
        controller.abort();
        finish(new DOMException('Aborted', 'AbortError'));
        await expect(pending).rejects.toMatchObject({ code: 'cancelled' });
    });
    it('distinguishes caller cancellation from a connection failure', async () => {
        const controller = new AbortController();
        const pending = request(
            (_url, options) =>
                new Promise((_resolve, reject) => {
                    options?.signal?.addEventListener('abort', () =>
                        reject(new DOMException('Aborted', 'AbortError')),
                    );
                }),
            '/devices',
            { signal: controller.signal },
        );
        controller.abort();
        await expect(pending).rejects.toMatchObject({ code: 'cancelled' });
    });
});

describe('typed reads and narrow device settings writes', () => {
    it('validates authenticated feature responses at the boundary', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json({ enabled: true }))
            .mockResolvedValueOnce(json({ enabled: 'yes' }));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const schema = z.object({ enabled: z.boolean() });
        expect(await client.get('/dns/example', schema)).toEqual({ enabled: true });
        await expect(client.get('/dns/example', schema)).rejects.toMatchObject({
            code: 'invalidResponse',
        });
    });

    it('sends only the intended settings with encoded device ID', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json({ ...device, name: 'New name' }));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const result = await client.updateDevice(device.id, { name: ' New name ' });
        expect(result.name).toBe('New name');
        const [url, options] = transport.mock.calls[1];
        expect(url).toBe('/api/adminconsole/devices/device%3Aaabbccddeeff/settings');
        expect(options?.method).toBe('PATCH');
        expect(JSON.parse(String(options?.body))).toEqual({ name: 'New name' });
        expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer test-token');
        expect(new Headers(options?.headers).get('Content-Type')).toBe('application/json');
    });

    it('rejects empty and unknown settings instead of submitting a full-device update', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(token()));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client.updateDevice(device.id, {})).rejects.toMatchObject({
            code: 'validation',
        });
        await expect(client.updateDevice(device.id, device)).rejects.toMatchObject({
            code: 'validation',
        });
        await expect(client.updateDevice(device.id, { name: ' ' })).rejects.toMatchObject({
            code: 'validation',
        });
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it('never writes without an authenticated session or after logout', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(token()));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const pending = client.updateDevice(device.id, { enabled: false });
        client.logout();
        await expect(pending).rejects.toMatchObject({ code: 'cancelled' });
        await expect(client.updateDevice(device.id, { enabled: true })).rejects.toMatchObject({
            code: 'sessionExpired',
        });
        expect(transport).toHaveBeenCalledTimes(1);
    });

    it.each([
        [400, 'validation'],
        [409, 'conflict'],
        [403, 'forbidden'],
    ])('keeps write error %s separate from session expiry', async (status, code) => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json('private server details', status as number));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client.updateDevice(device.id, { enabled: false })).rejects.toMatchObject({
            code,
        });
        expect(client.snapshot().phase).toBe('ready');
    });

    it('rejects noncanonical API paths before transmission', async () => {
        const transport = vi.fn<typeof fetch>();
        await expect(request(transport, 'https://other.example/api')).rejects.toMatchObject({
            code: 'validation',
        });
        await expect(request(transport, '/../private')).rejects.toMatchObject({
            code: 'validation',
        });
        expect(transport).not.toHaveBeenCalled();
    });
});

describe('shared feature writes and password-protection metadata', () => {
    it.each([
        ['postVoid', 'POST', 204, undefined],
        ['postVoid', 'POST', 200, undefined],
        ['postVoid', 'POST', 200, null],
        ['putVoid', 'PUT', 204, undefined],
        ['putVoid', 'PUT', 200, undefined],
        ['putVoid', 'PUT', 200, null],
    ] as const)(
        'accepts %s %s with status %s and empty value %s',
        async (method, verb, status, body) => {
            const transport = vi
                .fn<typeof fetch>()
                .mockResolvedValueOnce(json(token()))
                .mockResolvedValueOnce(
                    body === null ? json(null, status) : new Response(null, { status }),
                );
            const client = new ConsoleClient(transport);
            await client.bootstrap();
            await expect(
                client[method]('/feature/settings', { enabled: true }),
            ).resolves.toBeUndefined();
            const [url, options] = transport.mock.calls[1];
            expect(url).toBe('/api/adminconsole/feature/settings');
            expect(options).toMatchObject({
                method: verb,
                body: '{"enabled":true}',
                credentials: 'same-origin',
                cache: 'no-store',
                redirect: 'error',
            });
            expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer test-token');
            expect(new Headers(options?.headers).get('Content-Type')).toBe('application/json');
        },
    );

    it.each(['postVoid', 'putVoid'] as const)(
        'rejects unexpected success content from %s',
        async (method) => {
            const transport = vi
                .fn<typeof fetch>()
                .mockResolvedValueOnce(json(token()))
                .mockResolvedValueOnce(json({ ok: true }))
                .mockResolvedValueOnce(new Response('<html>internal details</html>'));
            const client = new ConsoleClient(transport);
            await client.bootstrap();
            await expect(client[method]('/feature/settings', {})).rejects.toMatchObject({
                code: 'invalidResponse',
            });
            await expect(client[method]('/feature/settings', {})).rejects.toMatchObject({
                code: 'invalidResponse',
            });
            expect(client.snapshot().phase).toBe('ready');
        },
    );

    it.each([
        ['/authentication/enable', 'error.credentials.invalid', 'credentials'],
        ['/authentication/enable', 'error.credentials.too.soon', 'wait'],
        ['/authentication/disable', 'error.credentials.invalid', 'credentials'],
        ['/authentication/disable', 'error.credentials.too.soon', 'wait'],
    ] as const)(
        'preserves the authenticated session for %s returning %s',
        async (path, body, code) => {
            const transport = vi
                .fn<typeof fetch>()
                .mockResolvedValueOnce(json(token(true)))
                .mockResolvedValueOnce(json(token(true, 'logged-in')))
                .mockResolvedValueOnce(json(body, 401))
                .mockResolvedValueOnce(json([device]));
            const client = new ConsoleClient(transport);
            await client.bootstrap();
            await client.login('current');
            await expect(
                client.postVoid(path, { currentPassword: 'wrong', newPassword: 'replacement' }),
            ).rejects.toMatchObject({ code });
            expect(client.snapshot()).toEqual({ phase: 'ready', passwordProtected: true });
            expect(await client.devices()).toEqual([device]);
            expect(new Headers(transport.mock.calls[3][1]?.headers).get('Authorization')).toBe(
                'Bearer logged-in',
            );
        },
    );

    it.each([
        ['postVoid', '/feature/settings', 'error.credentials.invalid'],
        ['putVoid', '/authentication/enable', 'error.credentials.invalid'],
        ['postVoid', '/authentication/enable', 'error.token.invalid'],
        ['postVoid', '/authentication/disable', 'error.token.expired'],
    ] as const)('expires a genuine 401 from %s %s', async (method, path, body) => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json(body, 401));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        await expect(client[method](path, {})).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(client.snapshot()).toEqual({ phase: 'expired' });
        await expect(client.devices()).rejects.toMatchObject({ code: 'sessionExpired' });
        expect(transport).toHaveBeenCalledTimes(2);
    });

    it('exposes only password-protection metadata from the initial and login tokens', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token(true, 'initial-private-token')))
            .mockResolvedValueOnce(json(token(false, 'authenticated-private-token')));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        expect(client.snapshot()).toEqual({ phase: 'login', passwordProtected: true });
        await client.login('current');
        expect(client.snapshot()).toEqual({ phase: 'ready', passwordProtected: false });
        expect(JSON.stringify(client.snapshot())).not.toContain('private-token');
        client.logout();
        expect(client.snapshot()).toEqual({ phase: 'signedOut' });
    });

    it('publishes renewed password-protection metadata and authenticates with the renewed token', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(
                json(token(false, 'before-renewal', Math.floor(Date.now() / 1000) + 40)),
            )
            .mockResolvedValueOnce(json(token(true, 'after-renewal')))
            .mockResolvedValueOnce(json([device]));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        expect(client.snapshot()).toEqual({ phase: 'ready', passwordProtected: false });
        const listener = vi.fn();
        const unsubscribe = client.subscribe(listener);
        await client.devices();
        expect(client.snapshot()).toEqual({ phase: 'ready', passwordProtected: true });
        expect(listener).toHaveBeenCalledOnce();
        expect(transport.mock.calls[1][0]).toBe(
            '/api/adminconsole/authentication/renew/ADMINCONSOLE',
        );
        expect(new Headers(transport.mock.calls[1][1]?.headers).get('Authorization')).toBe(
            'Bearer before-renewal',
        );
        expect(new Headers(transport.mock.calls[2][1]?.headers).get('Authorization')).toBe(
            'Bearer after-renewal',
        );
        unsubscribe();
    });

    it('validates typed POST responses without accepting an empty response', async () => {
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json({ id: 'created' }))
            .mockResolvedValueOnce(new Response(null, { status: 204 }));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        const schema = z.object({ id: z.string() });
        expect(await client.post('/feature/items', { name: 'new item' }, schema)).toEqual({
            id: 'created',
        });
        expect(transport.mock.calls[1][1]?.method).toBe('POST');
        await expect(client.post('/feature/items', {}, schema)).rejects.toMatchObject({
            code: 'invalidResponse',
        });
    });

    it('fetches blockers from the exact API root and retains authorization', async () => {
        const schema = z.array(z.object({ id: z.string() }));
        const blockers = [{ id: 'ads' }];
        const transport = vi
            .fn<typeof fetch>()
            .mockResolvedValueOnce(json(token()))
            .mockResolvedValueOnce(json(blockers))
            .mockResolvedValueOnce(json([{ id: false }]));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        expect(await client.getBlockers(schema)).toEqual(blockers);
        const [url, options] = transport.mock.calls[1];
        expect(url).toBe('/api/blockers/');
        expect(options?.method).toBe('GET');
        expect(new Headers(options?.headers).get('Authorization')).toBe('Bearer test-token');
        await expect(client.getBlockers(schema)).rejects.toMatchObject({ code: 'invalidResponse' });
    });

    it('does not issue shared writes or blockers reads after logout', async () => {
        const transport = vi.fn<typeof fetch>().mockResolvedValue(json(token()));
        const client = new ConsoleClient(transport);
        await client.bootstrap();
        client.logout();
        await expect(client.postVoid('/feature/settings', {})).rejects.toMatchObject({
            code: 'sessionExpired',
        });
        await expect(client.putVoid('/feature/settings', {})).rejects.toMatchObject({
            code: 'sessionExpired',
        });
        await expect(client.getBlockers(z.array(z.string()))).rejects.toMatchObject({
            code: 'sessionExpired',
        });
        expect(transport).toHaveBeenCalledTimes(1);
    });
});
