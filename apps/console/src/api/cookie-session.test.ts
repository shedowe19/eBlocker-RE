// SPDX-License-Identifier: EUPL-1.2
import { expect, it, vi } from 'vitest';
import { z } from 'zod';
import { ConsoleClient } from './client';
import { json } from '../test/fixtures';
const time = 1_800_000_000_000;
const csrf = 'c'.repeat(43);
const session = (extra: object = {}) => ({
    appContext: 'ADMINCONSOLE',
    authenticated: true,
    passwordRequired: true,
    expiresOn: time / 1000 + 300,
    csrfToken: csrf,
    ...extra,
});

it('recovers a different tab’s CSRF once solely to revoke its cookie family', async () => {
    const replacement = 'r'.repeat(43);
    let bootstraps = 0;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (url, options) => {
        if (String(url).endsWith('/session'))
            return json(session({ csrfToken: ++bootstraps === 1 ? csrf : replacement }));
        if (String(url).endsWith('/logout'))
            return new Headers(options?.headers).get('X-CSRF-Token') === replacement
                ? new Response(null, { status: 204 })
                : json({}, 403);
        return json(true);
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const phases: string[] = [];
    client.subscribe(() => phases.push(client.snapshot().phase));
    client.logout();
    await vi.waitFor(() =>
        expect(
            transport.mock.calls.filter(([url]) => String(url).endsWith('/logout')),
        ).toHaveLength(2),
    );
    expect(bootstraps).toBe(2);
    expect(client.snapshot().phase).toBe('signedOut');
    expect(phases).not.toContain('ready');
    await expect(client.get('/devices', z.unknown())).rejects.toMatchObject({
        code: 'sessionExpired',
    });
});

it('bounds CSRF recovery when logout continues to be forbidden', async () => {
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation(async (url) =>
            String(url).endsWith('/logout') ? json({}, 403) : json(session()),
        );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    client.logout();
    await vi.waitFor(() =>
        expect(client.snapshot()).toEqual({ phase: 'error', error: 'forbidden' }),
    );
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/session'))).toHaveLength(
        2,
    );
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/logout'))).toHaveLength(2);
});

it('uses cookie bootstrap/login and CSRF without exposing a bearer token', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) => {
        if (String(input).endsWith('/session')) return json(session({ authenticated: false }));
        if (String(input).endsWith('/session/login')) return json(session());
        return json({ value: 1 });
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    expect(client.snapshot().phase).toBe('login');
    await client.login('correct password');
    expect(client.snapshot().phase).toBe('ready');
    await client.get('/devices', z.object({ value: z.number() }));
    for (const [, options] of transport.mock.calls) {
        expect(new Headers(options?.headers).get('Authorization')).toBeNull();
        expect(new Headers(options?.headers).get('X-Eblocker-Console')).toBe('1');
        expect(options?.credentials).toBe('same-origin');
        expect(options?.redirect).toBe('error');
    }
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('X-CSRF-Token')).toBe(csrf);
});
it('retains pre-authentication wait with cookie and CSRF', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) =>
        String(input).endsWith('/wait') ? json(4.3) : json(session({ authenticated: false })),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    expect(await client.loginWait()).toBe(5);
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('X-CSRF-Token')).toBe(csrf);
});
it('deduplicates renewal and sends the rotated CSRF value', async () => {
    let clock = time;
    const renewed = 'n'.repeat(43);
    const transport = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) => {
        if (String(input).endsWith('/session')) return json(session());
        if (String(input).endsWith('/renew'))
            return json(session({ csrfToken: renewed, expiresOn: time / 1000 + 600 }));
        return json(true);
    });
    const client = new ConsoleClient(transport, () => clock, 'cookie');
    await client.bootstrap();
    clock += 250_000;
    await Promise.all([client.get('/one', z.boolean()), client.get('/two', z.boolean())]);
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/renew'))).toHaveLength(1);
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('X-CSRF-Token')).toBe(renewed);
});
it('waits for server revocation before opening another session', async () => {
    let finish!: (response: Response) => void;
    const transport = vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/logout')
            ? new Promise<Response>((resolve) => {
                  finish = resolve;
              })
            : json(session()),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    client.logout();
    expect(client.snapshot().phase).toBe('signedOut');
    const reopening = client.bootstrap();
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/session'))).toHaveLength(
        1,
    );
    await vi.waitFor(() => expect(finish).toBeDefined());
    finish(new Response(null, { status: 204 }));
    await reopening;
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/session'))).toHaveLength(
        2,
    );
    expect(client.snapshot().phase).toBe('ready');
});
it('retries a failed revocation before allowing bootstrap', async () => {
    let failures = true;
    const transport = vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).endsWith('/logout')) {
            if (failures) throw new Error('offline');
            return new Response(null, { status: 204 });
        }
        return json(session());
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    client.logout();
    await vi.waitFor(() => expect(client.snapshot()).toEqual({ phase: 'error', error: 'network' }));
    await client.bootstrap();
    expect(client.snapshot().phase).toBe('error');
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/session'))).toHaveLength(
        1,
    );
    failures = false;
    await client.bootstrap();
    expect(client.snapshot().phase).toBe('ready');
});
it('revokes a bootstrap response that arrives after logout', async () => {
    let finish!: (response: Response) => void;
    const transport = vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/logout')
            ? new Response(null, { status: 204 })
            : new Promise<Response>((resolve) => {
                  finish = resolve;
              }),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    const loading = client.bootstrap();
    await vi.waitFor(() => expect(finish).toBeDefined());
    client.logout();
    finish(json(session()));
    await loading;
    expect(client.snapshot().phase).toBe('signedOut');
    expect(transport.mock.calls.some(([url]) => String(url).endsWith('/session/logout'))).toBe(
        true,
    );
});
it('rejects private data arriving after logout', async () => {
    let finish!: (response: Response) => void;
    const transport = vi.fn(async (input: RequestInfo | URL) =>
        String(input).endsWith('/logout')
            ? new Response(null, { status: 204 })
            : String(input).includes('/session')
              ? json(session())
              : new Promise<Response>((resolve) => {
                    finish = resolve;
                }),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const reading = client.get('/private', z.string());
    await vi.waitFor(() => expect(finish).toBeDefined());
    client.logout();
    finish(json('private data'));
    await expect(reading).rejects.toMatchObject({ code: 'cancelled' });
});
it('protects backup downloads with CSRF and sanitizes filenames', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) =>
        String(input).includes('/session')
            ? json(session())
            : new Response('backup bytes', {
                  headers: {
                      'Content-Type': 'application/octet-stream',
                      'Content-Disposition': 'attachment; filename="../../secret.backup"',
                  },
              }),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const file = await client.downloadConfigBackup('opaque-reference');
    expect(file.filename).toBe('secret.backup');
    expect(await file.blob.text()).toBe('backup bytes');
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('X-CSRF-Token')).toBe(csrf);
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('Authorization')).toBeNull();
});

it('serializes overlapping bootstraps and revokes the stale cookie before issuing another', async () => {
    let finish!: (response: Response) => void;
    let bootstraps = 0;
    const nextCsrf = 'b'.repeat(43);
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        if (String(input).endsWith('/session')) {
            if (++bootstraps === 1)
                return new Promise<Response>((resolve) => {
                    finish = resolve;
                });
            return json(session({ csrfToken: nextCsrf }));
        }
        return json(true);
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    const first = client.bootstrap();
    await vi.waitFor(() => expect(finish).toBeDefined());
    const second = client.bootstrap();
    await Promise.resolve();
    expect(bootstraps).toBe(1);
    finish(json(session()));
    await Promise.all([first, second]);
    expect(transport.mock.calls.map(([path]) => String(path).split('/').pop())).toEqual([
        'session',
        'logout',
        'session',
    ]);
    expect(client.snapshot().phase).toBe('ready');
    await client.get('/private', z.boolean());
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('X-CSRF-Token')).toBe(nextCsrf);
});

it('retains the known CSRF for logout immediately after starting a new bootstrap', async () => {
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation(async (input) =>
            String(input).endsWith('/logout')
                ? new Response(null, { status: 204 })
                : json(session()),
        );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const reloading = client.bootstrap();
    client.logout();
    await reloading;
    await vi.waitFor(() =>
        expect(transport.mock.calls.some(([url]) => String(url).endsWith('/logout'))).toBe(true),
    );
    const logout = transport.mock.calls.find(([url]) => String(url).endsWith('/logout'))!;
    expect(new Headers(logout[1]?.headers).get('X-CSRF-Token')).toBe(csrf);
    expect(client.snapshot().phase).toBe('signedOut');
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/session'))).toHaveLength(
        1,
    );
});

it('orders a late login, logout and re-bootstrap without resurrecting authenticated state', async () => {
    let finish!: (response: Response) => void;
    let bootstraps = 0;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/session')) {
            bootstraps++;
            return json(session({ authenticated: false }));
        }
        if (String(input).endsWith('/login'))
            return new Promise<Response>((resolve) => {
                finish = resolve;
            });
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        throw new Error('Unexpected request');
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const login = client.login('private password');
    const rejected = expect(login).rejects.toMatchObject({ code: 'cancelled' });
    await vi.waitFor(() => expect(finish).toBeDefined());
    client.logout();
    const reloading = client.bootstrap();
    expect(bootstraps).toBe(1);
    finish(json(session()));
    await rejected;
    await reloading;
    expect(transport.mock.calls.map(([path]) => String(path).split('/').pop())).toEqual([
        'session',
        'login',
        'logout',
        'session',
    ]);
    expect(client.snapshot()).toEqual({ phase: 'login', passwordProtected: true });
    expect(JSON.stringify(client.snapshot())).not.toMatch(/private password|cccccccc/);
    expect(
        transport.mock.calls.some(([path]) =>
            /private.password|csrf|Authorization/.test(String(path)),
        ),
    ).toBe(false);
});

it('does not send a second queued login after the first one authenticated', async () => {
    let finish!: (response: Response) => void;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) =>
        String(input).endsWith('/login')
            ? new Promise<Response>((resolve) => {
                  finish = resolve;
              })
            : json(session({ authenticated: false })),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const first = client.login('correct');
    const second = client.login('duplicate');
    const rejected = expect(second).rejects.toMatchObject({ code: 'cancelled' });
    await vi.waitFor(() => expect(finish).toBeDefined());
    finish(json(session()));
    await first;
    await rejected;
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/login'))).toHaveLength(1);
    expect(client.snapshot().phase).toBe('ready');
});

it('revokes a renewal finishing after logout and never issues the queued data request', async () => {
    let finish!: (response: Response) => void;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/session'))
            return json(session({ expiresOn: time / 1000 + 30 }));
        if (String(input).endsWith('/renew'))
            return new Promise<Response>((resolve) => {
                finish = resolve;
            });
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        throw new Error('A private read must not be issued');
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const reading = client.get('/private', z.boolean());
    const rejected = expect(reading).rejects.toMatchObject({ code: 'cancelled' });
    await vi.waitFor(() => expect(finish).toBeDefined());
    client.logout();
    finish(json(session()));
    await rejected;
    expect(transport.mock.calls.filter(([url]) => String(url).endsWith('/logout'))).toHaveLength(1);
    expect(client.snapshot().phase).toBe('signedOut');
});

it('expires a rejected renewal and prevents protected requests until a new session is opened', async () => {
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/session'))
            return json(session({ expiresOn: time / 1000 + 30 }));
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        return json('error.token.invalid', 401);
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    await expect(client.get('/private', z.boolean())).rejects.toMatchObject({
        code: 'sessionExpired',
    });
    expect(client.snapshot().phase).toBe('expired');
    await expect(client.get('/private', z.boolean())).rejects.toMatchObject({
        code: 'sessionExpired',
    });
    expect(transport.mock.calls.some(([path]) => String(path).endsWith('/private'))).toBe(false);
});

it('keeps revocation pending when logout returns unrelated successful JSON', async () => {
    let malformed = true;
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation(async (input) =>
            String(input).endsWith('/logout')
                ? malformed
                    ? json({ error: 'still logged in' })
                    : new Response(null, { status: 204 })
                : json(session()),
        );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    client.logout();
    await vi.waitFor(() =>
        expect(client.snapshot()).toEqual({ phase: 'error', error: 'invalidResponse' }),
    );
    await client.bootstrap();
    expect(transport.mock.calls.filter(([path]) => String(path).endsWith('/session'))).toHaveLength(
        1,
    );
    malformed = false;
    await client.bootstrap();
    expect(client.snapshot().phase).toBe('ready');
});

it.each(['text/html', 'application/json', 'text/plain'])(
    'rejects a successful %s document as a binary report',
    async (contentType) => {
        const transport = vi.fn<typeof fetch>().mockImplementation(async (input) =>
            String(input).endsWith('/session')
                ? json(session())
                : new Response('private error document', {
                      headers: { 'Content-Type': contentType },
                  }),
        );
        const client = new ConsoleClient(transport, () => time, 'cookie');
        await client.bootstrap();
        await expect(client.downloadDiagnostics()).rejects.toMatchObject({
            code: 'invalidResponse',
        });
    },
);

it('uses the real archive extensions when the download filename header is absent', async () => {
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) =>
        String(input).endsWith('/session')
            ? json(session())
            : new Response('bytes', {
                  headers: { 'Content-Type': 'application/octet-stream' },
              }),
    );
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    expect((await client.downloadConfigBackup('eblocker-config-123.eblcfg')).filename).toBe(
        'eblocker-config.eblcfg',
    );
    expect((await client.downloadDiagnostics()).filename).toBe('eblocker-diagnostics-report.zip');
});

it('routes raw uploads and binary responses through the same cookie credentials without writing storage', async () => {
    const storage = vi.spyOn(Storage.prototype, 'setItem');
    try {
        const file = new File(['synthetic backup'], 'private.eblcfg');
        const transport = vi.fn<typeof fetch>().mockImplementation(async (input) =>
            String(input).endsWith('/session')
                ? json(session())
                : String(input).endsWith('/upload')
                  ? json({ fileReference: 'eblocker-config-123.eblcfg' })
                  : new Response('report', {
                        headers: { 'Content-Type': 'application/octet-stream' },
                    }),
        );
        const client = new ConsoleClient(transport, () => time, 'cookie');
        await client.bootstrap();
        await client.uploadConfigBackup(file, z.object({ fileReference: z.string() }));
        await client.downloadDiagnostics();
        expect(transport.mock.calls[1][1]?.body).toBe(file);
        expect(new Headers(transport.mock.calls[1][1]?.headers).get('Content-Type')).toBe(
            'application/octet-stream',
        );
        for (const [path, options] of transport.mock.calls.slice(1)) {
            expect(new Headers(options?.headers).get('X-CSRF-Token')).toBe(csrf);
            expect(new Headers(options?.headers).get('Authorization')).toBeNull();
            expect(options?.credentials).toBe('same-origin');
            expect(options?.redirect).toBe('error');
            expect(String(path)).not.toContain('?');
        }
        expect(storage).not.toHaveBeenCalled();
    } finally {
        storage.mockRestore();
    }
});

it.each(['c'.repeat(32), '+'.repeat(43), `${'c'.repeat(42)}=`])(
    'fails closed for CSRF values outside the server contract',
    async (csrfToken) => {
        const client = new ConsoleClient(
            async () => json(session({ csrfToken })),
            () => time,
            'cookie',
        );
        await client.bootstrap();
        expect(client.snapshot()).toEqual({ phase: 'error', error: 'invalidResponse' });
    },
);

it('recovers an unknown CSRF only to revoke a cookie after a lost initial response', async () => {
    let reject!: (reason: Error) => void;
    let bootstraps = 0;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        if (++bootstraps === 1)
            return new Promise<Response>((_resolve, fail) => {
                reject = fail;
            });
        return json(session());
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    const published: string[] = [];
    client.subscribe(() => published.push(client.snapshot().phase));
    const opening = client.bootstrap();
    await vi.waitFor(() => expect(reject).toBeDefined());
    client.logout();
    reject(new Error('Cookie was installed, but its response body was lost'));
    await opening;
    await vi.waitFor(() =>
        expect(transport.mock.calls.some(([path]) => String(path).endsWith('/logout'))).toBe(true),
    );
    expect(transport.mock.calls.map(([path]) => String(path).split('/').pop())).toEqual([
        'session',
        'session',
        'logout',
    ]);
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('X-CSRF-Token')).toBe(csrf);
    expect(published).not.toContain('ready');
    expect(client.snapshot().phase).toBe('signedOut');
});

it('rejects a binary response arriving after cookie logout', async () => {
    let finish!: (response: Response) => void;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/session')) return json(session());
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        return new Promise<Response>((resolve) => {
            finish = resolve;
        });
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const downloading = client.downloadDiagnostics();
    const rejected = expect(downloading).rejects.toMatchObject({ code: 'cancelled' });
    await vi.waitFor(() => expect(finish).toBeDefined());
    client.logout();
    finish(
        new Response('sensitive report', {
            headers: { 'Content-Type': 'application/octet-stream' },
        }),
    );
    await rejected;
});

it('does not transmit an already-aborted raw upload', async () => {
    const transport = vi.fn<typeof fetch>().mockResolvedValue(json(session()));
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    const controller = new AbortController();
    controller.abort();
    await expect(
        client.uploadConfigBackup(
            new File(['backup'], 'backup.eblcfg'),
            z.unknown(),
            controller.signal,
        ),
    ).rejects.toMatchObject({ code: 'cancelled' });
    expect(transport).toHaveBeenCalledOnce();
});

it('keeps wrong-password retries available but expires a genuinely invalid pre-authentication cookie', async () => {
    let invalidCookie = false;
    const transport = vi.fn<typeof fetch>().mockImplementation(async (input) => {
        if (String(input).endsWith('/session')) return json(session({ authenticated: false }));
        if (String(input).endsWith('/logout')) return new Response(null, { status: 204 });
        return json(invalidCookie ? 'error.token.invalid' : 'error.credentials.invalid', 401);
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    await expect(client.login('wrong')).rejects.toMatchObject({ code: 'credentials' });
    expect(client.snapshot().phase).toBe('login');
    invalidCookie = true;
    await expect(client.login('correct')).rejects.toMatchObject({ code: 'sessionExpired' });
    expect(client.snapshot().phase).toBe('expired');
});

it('validates versioned DELETE responses with cookie authentication and no request body', async () => {
    let invalid = false;
    const transport = vi
        .fn<typeof fetch>()
        .mockImplementation(async (input) =>
            String(input).endsWith('/session')
                ? json(session())
                : json(
                      invalid
                          ? { schemaVersion: 2, data: { deleted: true } }
                          : { schemaVersion: 1, data: { profileId: 'work', deleted: true } },
                  ),
        );
    const schema = z.object({
        schemaVersion: z.literal(1),
        data: z.object({ profileId: z.string(), deleted: z.literal(true) }),
    });
    const client = new ConsoleClient(transport, () => time, 'cookie');
    await client.bootstrap();
    expect(await client.delete('/wireguard/profiles/work', schema)).toEqual({
        schemaVersion: 1,
        data: { profileId: 'work', deleted: true },
    });
    const options = transport.mock.lastCall?.[1];
    expect(options?.method).toBe('DELETE');
    expect(options?.body).toBeUndefined();
    expect(new Headers(options?.headers).get('X-CSRF-Token')).toBe(csrf);
    invalid = true;
    await expect(client.delete('/wireguard/profiles/work', schema)).rejects.toMatchObject({
        code: 'invalidResponse',
    });
});
