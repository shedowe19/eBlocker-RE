// SPDX-License-Identifier: EUPL-1.2
import { expect } from '@playwright/test';
import type { Page, Route } from '@playwright/test';
import { device, token } from '../src/test/fixtures';

export const responses: Record<string, unknown> = {
    '/api/adminconsole/devices': [device],
    '/api/adminconsole/systemstatus': {
        executionState: 'RUNNING',
        projectVersion: '4.0.3',
        warnings: [],
        subSystemDetails: [],
    },
    '/api/adminconsole/network': {
        automatic: true,
        dhcp: false,
        dnsServer: true,
        ipAddress: '10.0.17.20',
        gateway: '10.0.17.254',
        expertMode: false,
        networkMask: '255.255.255.0',
        nameServerPrimary: '10.0.17.254',
        dhcpRangeFirst: '10.0.17.30',
        dhcpRangeLast: '10.0.17.200',
        dhcpLeaseTime: 600,
        ipFixedByDefault: false,
        revision: 'browser-configuration-1',
        pendingReboot: false,
        rebootNecessary: false,
    },
    '/api/adminconsole/network/ip6': {
        routerAdvertisementsEnabled: true,
        privacyExtensionsEnabled: true,
        localAddresses: ['fe80::20'],
        globalAddresses: ['2001:db8::20'],
    },
    '/api/adminconsole/dns/status': true,
    '/api/adminconsole/dns/config/resolvers': {
        defaultResolver: 'dhcp',
        customResolverMode: 'default',
        dhcpNameServers: ['10.0.17.254'],
        customNameServers: [],
    },
    '/api/adminconsole/dns/config/records': [],
    '/api/adminconsole/dns/stats': { nameServerStats: [] },
    '/api/adminconsole/wireguard/profiles': { schemaVersion: 1, data: { profiles: [] } },
    '/api/blockers/': [
        {
            id: 1,
            name: { de: 'Globale Werbeliste', en: 'Global advertising list' },
            enabled: true,
            type: 'DOMAIN',
            category: 'ADS',
            providedByEblocker: true,
        },
    ],
    '/api/adminconsole/trustedapps/all': [],
    '/api/adminconsole/ssl/status': true,
    '/api/adminconsole/ssl/rootca': {
        distinguishedName: { commonName: 'Browser Test CA' },
        fingerprintSha256: 'AA:BB:CC',
        notBefore: Date.UTC(2020, 0, 1),
        notAfter: Date.UTC(2099, 0, 1),
    },
    '/api/adminconsole/network-agent/status': {
        schemaVersion: 1,
        data: {
            readOnly: true,
            capabilities: {
                readOnly: true,
                wireguard: { kernelFamilyRegistered: true, state: 'registered', management: false },
            },
            interfaces: [
                {
                    index: 2,
                    name: 'eth0',
                    mtu: 1500,
                    up: true,
                    running: true,
                    loopback: false,
                    addresses: [{ prefix: '10.0.17.20/24', family: 'ipv4', scope: 'global' }],
                },
            ],
            routes: [],
        },
    },
};
type RecordedRequest = { path: string; method: string; body: unknown };
type Override = (route: Route, request: RecordedRequest) => Promise<boolean>;

export async function installApi(page: Page, override?: Override) {
    let authenticated = false;
    const calls: RecordedRequest[] = [];
    const unexpected: string[] = [];
    const browserErrors: string[] = [];
    page.on('pageerror', (error) => browserErrors.push(error.message));
    await page.addInitScript(() => localStorage.setItem('eblocker.console.language', 'de'));
    await page.route('**/api/**', async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        if (path === '/api/adminconsole/authentication/token/ADMINCONSOLE') {
            await route.fulfill({ json: token(true, 'login-token') });
            return;
        }
        if (path === '/api/adminconsole/authentication/wait') {
            await route.fulfill({ json: 0 });
            return;
        }
        if (path === '/api/adminconsole/authentication/login/ADMINCONSOLE') {
            expect(request.postDataJSON()).toEqual({ currentPassword: 'browser-test-password' });
            authenticated = true;
            await route.fulfill({ json: token(true, 'browser-token') });
            return;
        }
        expect(authenticated).toBe(true);
        expect(request.headers().authorization).toBe('Bearer browser-token');
        const recorded = {
            path,
            method: request.method(),
            body: request.postDataBuffer()
                ? request.headers()['content-type']?.includes('application/json')
                    ? (request.postDataJSON() as unknown)
                    : request.postDataBuffer()
                : undefined,
        };
        calls.push(recorded);
        if (override && (await override(route, recorded))) return;
        if (request.method() === 'GET' && Object.hasOwn(responses, path)) {
            await route.fulfill({ json: responses[path] });
            return;
        }
        unexpected.push(`${request.method()} ${path}`);
        await route.fulfill({ status: 404, json: 'unexpected endpoint' });
    });
    return { calls, unexpected, browserErrors };
}

export async function signIn(page: Page, path = '/devices') {
    await page.goto(`/next/#${path}`);
    await page.getByLabel('Administratorpasswort', { exact: true }).fill('browser-test-password');
    await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await expect(page.getByRole('button', { name: 'Abmelden', exact: true })).toBeVisible();
}
