// SPDX-License-Identifier: EUPL-1.2
import { expect, it, vi } from 'vitest';
import { z } from 'zod';
import { ConsoleClient } from './client';
import { json, token } from '../test/fixtures';

const status = z.object({ profileId: z.number().int() });
it.each([204, 200])('represents an empty VPN device status as null (%i)', async (code) => {
    const transport = vi.fn(async (input: RequestInfo | URL) =>
        String(input).includes('/authentication/')
            ? json(token())
            : new Response(null, { status: code }),
    );
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    expect(await client.getVpnDeviceStatus('device:aa', status)).toBeNull();
    expect(transport.mock.lastCall?.[0]).toBe('/api/adminconsole/vpn/profile/status/device%3Aaa');
});
it('validates populated VPN status and rejects malformed successful data', async () => {
    let malformed = false;
    const client = new ConsoleClient(
        vi.fn(async (input) =>
            String(input).includes('/authentication/')
                ? json(token())
                : json(malformed ? { profileId: 'not a number' } : { profileId: 4 }),
        ),
    );
    await client.bootstrap();
    expect(await client.getVpnDeviceStatus('device:a', status)).toEqual({ profileId: 4 });
    malformed = true;
    await expect(client.getVpnDeviceStatus('device:a', status)).rejects.toMatchObject({
        code: 'invalidResponse',
    });
});
it('uses authenticated typed PUT with only the supplied fields', async () => {
    const transport = vi.fn(async (input: RequestInfo | URL, _options?: RequestInit) =>
        String(input).includes('/authentication/')
            ? json(token())
            : json({ sessionUseTor: true, extra: 'discarded' }),
    );
    const client = new ConsoleClient(transport);
    await client.bootstrap();
    expect(
        await client.put(
            '/tor/config/device%3Aaa',
            { sessionUseTor: true },
            z.object({ sessionUseTor: z.boolean() }),
        ),
    ).toEqual({ sessionUseTor: true });
    expect(transport.mock.lastCall?.[1]).toMatchObject({
        method: 'PUT',
        body: '{"sessionUseTor":true}',
        redirect: 'error',
    });
    expect(new Headers(transport.mock.lastCall?.[1]?.headers).get('Authorization')).toBe(
        'Bearer test-token',
    );
});
it('expires a session after a protected VPN lookup returns 401', async () => {
    const client = new ConsoleClient(
        vi.fn(async (input) =>
            String(input).includes('/authentication/') ? json(token()) : json('unauthorized', 401),
        ),
    );
    await client.bootstrap();
    await expect(client.getVpnDeviceStatus('device:a', status)).rejects.toMatchObject({
        code: 'sessionExpired',
    });
    expect(client.snapshot().phase).toBe('expired');
    await expect(client.put('/tor/config/device:a', {}, z.unknown())).rejects.toMatchObject({
        code: 'sessionExpired',
    });
});
it('does not publish a VPN status that arrived after local logout', async () => {
    let finish!: (response: Response) => void;
    const client = new ConsoleClient(
        vi.fn(async (input) =>
            String(input).includes('/authentication/')
                ? json(token())
                : new Promise<Response>((resolve) => {
                      finish = resolve;
                  }),
        ),
    );
    await client.bootstrap();
    const pending = client.getVpnDeviceStatus('device:a', status);
    await vi.waitFor(() => expect(finish).toBeDefined());
    client.logout();
    finish(json({ profileId: 4 }));
    await expect(pending).rejects.toMatchObject({ code: 'cancelled' });
});
