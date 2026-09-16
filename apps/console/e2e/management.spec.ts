// SPDX-License-Identifier: EUPL-1.2
// Browser scenarios are listed and type-checked here. Execution requires CI's
// Chromium: this workspace cannot create its required AF_UNIX sockets.
import { readFileSync } from 'node:fs';
import { expect, test } from '@playwright/test';
import { device } from '../src/test/fixtures';
import type { FamilyProfile, FamilyUser } from '../src/features/family/contracts';
import type { DnsRecord, Resolvers } from '../src/features/dns/contracts';
import {
    profileResponseSchema,
    profileStatusResponseSchema,
} from '../src/features/wireguard/managementContracts';
import type { ProfileStatus } from '../src/features/wireguard/managementContracts';
import { installApi, responses, signIn } from './support';

function golden(name: string): unknown {
    return JSON.parse(
        readFileSync(
            new URL(`../../../contracts/wireguard-control/v1/${name}.json`, import.meta.url),
            'utf8',
        ),
    );
}

test('family edits preserve unrelated rules and profile navigation keeps the current route', async ({
    page,
}) => {
    let child: FamilyUser = {
        id: 1,
        name: 'Mika',
        system: false,
        userRole: 'CHILD',
        associatedProfileId: 10,
        birthday: [2015, 6, 21],
        containsPin: false,
    };
    const profile: FamilyProfile = {
        id: 10,
        name: 'Kinderregeln',
        builtin: false,
        standard: false,
        hidden: false,
        forSingleUser: true,
        controlmodeUrls: true,
        controlmodeTime: true,
        controlmodeMaxUsage: true,
        parentalControlSettingValidated: true,
        internetBlocked: false,
        internetAccessRestrictionMode: 1,
        accessibleSitesPackages: [],
        inaccessibleSitesPackages: [],
        maxUsageTimeByDay: { MONDAY: 60, FRIDAY: 120 },
        internetAccessContingents: [
            { onDay: 8, fromMinutes: 480, tillMinutes: 1440, totalMinutes: 90 },
        ],
    };
    const api = await installApi(page, async (route, request) => {
        const path = request.path.replace('/api/adminconsole', '');
        if (request.method === 'GET' && path === '/users') {
            await route.fulfill({ json: [child] });
            return true;
        }
        if (request.method === 'GET' && path === '/userprofiles') {
            await route.fulfill({ json: [profile] });
            return true;
        }
        if (request.method === 'GET' && path === '/filterlists') {
            await route.fulfill({ json: [] });
            return true;
        }
        if (request.method === 'GET' && path === '/devices') {
            await route.fulfill({
                json: [{ ...device, assignedUser: 1, operatingUser: 1, defaultSystemUser: 99 }],
            });
            return true;
        }
        if (request.method === 'PATCH' && path === '/users/1/settings') {
            expect(request.body).toEqual({ name: 'Mika Browser' });
            child = { ...child, name: 'Mika Browser' };
            await route.fulfill({ json: child });
            return true;
        }
        if (request.method === 'PATCH' && path === '/userprofiles/10/settings') {
            expect(request.body).toEqual({ maxUsageTimeByDay: { MONDAY: 45 } });
            profile.maxUsageTimeByDay.MONDAY = 45;
            await route.fulfill({ json: profile });
            return true;
        }
        return false;
    });
    await signIn(page, '/family');
    const childCard = page
        .getByRole('article')
        .filter({ has: page.getByRole('heading', { name: 'Mika', exact: true }) });
    await childCard.getByRole('button', { name: 'Kinderregeln', exact: true }).click();
    await expect(page).toHaveURL(/#\/family$/);
    await expect(page.locator('#family-profile-10')).toBeFocused();
    const opener = childCard.getByRole('button', { name: 'Benutzer bearbeiten' });
    await opener.click();
    await expect(page.getByRole('heading', { name: 'Benutzer bearbeiten' })).toBeFocused();
    await page
        .getByRole('form', { name: 'Benutzer bearbeiten' })
        .getByRole('button', { name: 'Abbrechen' })
        .click();
    await expect(opener).toBeFocused();
    await opener.click();
    const userForm = page.getByRole('form', { name: 'Benutzer bearbeiten' });
    await userForm.getByLabel('Name', { exact: true }).fill('Mika Browser');
    await userForm.getByRole('button', { name: 'Speichern', exact: true }).click();
    await expect(userForm.getByRole('status')).toHaveText(
        'Gespeichert. Die Einstellungen wurden neu geladen.',
    );
    await expect(page.getByRole('heading', { name: 'Mika Browser', exact: true })).toBeVisible();
    await userForm.getByRole('button', { name: 'Schließen' }).click();
    await page.getByRole('button', { name: 'Schutzprofil bearbeiten', exact: true }).click();
    const profileForm = page.getByRole('form', { name: 'Schutzprofil bearbeiten' });
    await profileForm.getByLabel('Montag', { exact: true }).fill('45');
    // A concurrent edit to a different day must survive the narrow PATCH.
    profile.maxUsageTimeByDay.FRIDAY = 140;
    await profileForm.getByRole('button', { name: 'Speichern', exact: true }).click();
    await expect(profileForm.getByRole('status')).toHaveText(
        'Gespeichert. Die Einstellungen wurden neu geladen.',
    );
    expect(profile.maxUsageTimeByDay).toEqual({ MONDAY: 45, FRIDAY: 140 });
    expect(profile.internetAccessContingents).toEqual([
        { onDay: 8, fromMinutes: 480, tillMinutes: 1440, totalMinutes: 90 },
    ]);
    expect(child).toMatchObject({
        userRole: 'CHILD',
        birthday: [2015, 6, 21],
        associatedProfileId: 10,
    });
    expect(api.calls.filter((call) => call.method === 'PATCH')).toHaveLength(2);
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});

test('backup upload is verified before a separately confirmed restore and secrets are erased', async ({
    page,
}) => {
    const reference = 'eblocker-config-12345.eblcfg';
    const bytes = Buffer.from('Synthetic encrypted backup browser fixture');
    const api = await installApi(page, async (route, request) => {
        if (request.path === '/api/configbackup/upload' && request.method === 'PUT') {
            expect(request.body).toEqual(bytes);
            await route.fulfill({ json: { fileReference: reference, passwordRequired: true } });
            return true;
        }
        if (request.path === '/api/configbackup/verify' && request.method === 'POST') {
            expect(request.body).toEqual({
                fileReference: reference,
                password: 'backup-browser-secret',
            });
            await route.fulfill({ json: { warnings: [] } });
            return true;
        }
        if (request.path === '/api/configbackup/import' && request.method === 'POST') {
            expect(request.body).toEqual({
                fileReference: reference,
                clientFileName: 'browser.eblcfg',
                password: 'backup-browser-secret',
            });
            await route.fulfill({ json: { warnings: [] } });
            return true;
        }
        return false;
    });
    await signIn(page, '/backup');
    const restore = page.getByRole('region', { name: 'Konfiguration wiederherstellen' });
    await restore.getByLabel('Sicherungsdatei', { exact: true }).setInputFiles({
        name: 'browser.eblcfg',
        mimeType: 'application/octet-stream',
        buffer: bytes,
    });
    await restore.getByRole('button', { name: 'Sicherung hochladen', exact: true }).click();
    await restore.getByLabel('Sicherungspasswort', { exact: true }).fill('backup-browser-secret');
    await restore.getByRole('button', { name: 'Sicherung prüfen', exact: true }).click();
    await expect(restore.getByRole('status')).toHaveText(
        'Die Sicherung wurde geprüft. Lies die Warnungen vor der Wiederherstellung.',
    );
    await expect(restore.getByLabel('Sicherungspasswort', { exact: true })).toHaveCount(0);
    const prepare = restore.getByRole('button', { name: 'Wiederherstellung vorbereiten' });
    await prepare.click();
    const confirmation = page.getByRole('dialog', { name: 'Konfiguration ersetzen?' });
    await expect(confirmation.getByRole('button', { name: 'Abbrechen' })).toBeFocused();
    expect(api.calls.some((call) => call.path.endsWith('/import'))).toBe(false);
    await confirmation.getByRole('button', { name: 'Abbrechen' }).click();
    await expect(prepare).toBeFocused();
    await prepare.click();
    await confirmation.getByRole('button', { name: 'Jetzt wiederherstellen' }).click();
    await expect(restore.getByRole('status')).toHaveText(
        'Die Wiederherstellung wurde vom eBlocker bestätigt. Starte das System neu, damit alle Einstellungen wirksam werden.',
    );
    expect(api.calls.filter((call) => call.path.endsWith('/import'))).toHaveLength(1);
    expect(api.calls.some((call) => call.path.endsWith('/reboot'))).toBe(false);
    expect(
        await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage })),
    ).not.toContain('backup-browser-secret');
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});

test('DNS changes require confirmation and preserve other names and VPN addresses', async ({
    page,
}) => {
    let resolvers = structuredClone(
        responses['/api/adminconsole/dns/config/resolvers'],
    ) as Resolvers;
    const builtin: DnsRecord = {
        name: 'eblocker.box',
        builtin: true,
        hidden: false,
        ipAddress: '10.0.17.20',
    };
    const printer: DnsRecord = {
        name: 'printer.home',
        builtin: false,
        hidden: false,
        ipAddress: '10.0.17.30',
        vpnIpAddress: '10.8.0.30',
        vpnIp6Address: 'fd00::30',
    };
    const nas: DnsRecord = {
        name: 'nas.home',
        builtin: false,
        hidden: false,
        ipAddress: '10.0.17.40',
    };
    let records = [builtin, printer, nas];
    const api = await installApi(page, async (route, request) => {
        if (request.path === '/api/adminconsole/dns/config/resolvers') {
            if (request.method === 'PATCH') {
                expect(request.body).toEqual({
                    expected: resolvers,
                    value: {
                        ...resolvers,
                        defaultResolver: 'custom',
                        customResolverMode: 'round_robin',
                        customNameServers: ['1.1.1.1', 'tcp:[2001:db8::53]:853'],
                    },
                });
                resolvers = (request.body as { value: Resolvers }).value;
            } else expect(request.method).toBe('GET');
            await route.fulfill({ json: resolvers });
            return true;
        }
        if (request.path === '/api/adminconsole/dns/config/records') {
            if (request.method === 'PATCH') {
                expect(request.body).toEqual({ expected: records, value: [printer] });
                records = [builtin, printer];
            } else expect(request.method).toBe('GET');
            await route.fulfill({ json: records });
            return true;
        }
        if (request.path === '/api/adminconsole/dns/stats') {
            expect(new URL(route.request().url()).searchParams.get('hours')).toBe('4');
            await route.fulfill({ json: { nameServerStats: [] } });
            return true;
        }
        return false;
    });
    await signIn(page, '/dns');
    const panel = page.getByRole('region', { name: 'DNS-Server', exact: true });
    await panel.getByLabel('Namensauflösung verwenden').selectOption('custom');
    await panel
        .getByLabel('Eigene DNS-Server, einer pro Zeile')
        .fill('1.1.1.1\ntcp:[2001:db8::53]:853');
    await panel.getByLabel('Auswahl eigener DNS-Server').selectOption('round_robin');
    await panel.getByRole('button', { name: 'Änderung prüfen' }).click();
    expect(api.calls.some((call) => call.method === 'PATCH')).toBe(false);
    const confirm = page.getByRole('dialog', { name: 'DNS-Server ändern?' });
    await expect(confirm.getByRole('button', { name: 'Abbrechen' })).toBeFocused();
    await confirm.getByRole('button', { name: 'Änderung bestätigen' }).click();
    await expect(panel.getByText('Gespeichert und durch erneutes Lesen bestätigt.')).toBeVisible();
    await page.getByRole('button', { name: 'Löschen: nas.home', exact: true }).click();
    const deletion = page.getByRole('dialog', { name: 'Lokalen DNS-Namen löschen?' });
    await expect(deletion).toContainText('nas.home');
    expect(api.calls.filter((call) => call.method === 'PATCH')).toHaveLength(1);
    await deletion.getByRole('button', { name: 'Eintrag endgültig löschen' }).click();
    await expect(page.getByRole('rowheader', { name: 'nas.home', exact: true })).toHaveCount(0);
    const printerRow = page
        .getByRole('row')
        .filter({ has: page.getByRole('rowheader', { name: 'printer.home', exact: true }) });
    await expect(printerRow).toContainText('10.8.0.30, fd00::30');
    const builtinRow = page
        .getByRole('row')
        .filter({ has: page.getByRole('rowheader', { name: 'eblocker.box', exact: true }) });
    await expect(builtinRow.getByRole('button')).toHaveCount(0);
    expect(api.calls.filter((call) => call.method === 'PATCH')).toHaveLength(2);
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});

test('WireGuard imports, observes connection protection, disconnects and deletes with explicit confirmation', async ({
    page,
}) => {
    const imported = profileResponseSchema.parse(golden('import-result'));
    const active = profileStatusResponseSchema.parse(golden('profile-status'));
    let current: ProfileStatus | undefined;
    let readbackStarted = false;
    let holdReadback = false;
    let releaseReadback!: () => void;
    const readbackGate = new Promise<void>((resolve) => {
        releaseReadback = resolve;
    });
    const configuration =
        '[Interface]\nPrivateKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=\nAddress = 10.20.0.2/32\n\n[Peer]\nPublicKey = AwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwM=\nAllowedIPs = 0.0.0.0/0, ::/0\nEndpoint = 198.51.100.1:51820\n';
    const root = '/api/adminconsole/wireguard/profiles';
    const api = await installApi(page, async (route, request) => {
        if (!request.path.startsWith(root)) return false;
        if (request.path === root && request.method === 'GET') {
            await route.fulfill({
                json: {
                    schemaVersion: 1,
                    data: {
                        profiles: current
                            ? [
                                  {
                                      profileId: current.profileId,
                                      phase: current.phase,
                                      plan: current.plan,
                                  },
                              ]
                            : [],
                    },
                },
            });
            return true;
        }
        if (request.path === `${root}/home` && request.method === 'PUT') {
            expect(request.body).toEqual({ schemaVersion: 1, configuration });
            current = { ...structuredClone(imported.data), runtime: null };
            await route.fulfill({ json: golden('import-result') });
            return true;
        }
        if (request.path === `${root}/home` && request.method === 'GET') {
            if (holdReadback) {
                readbackStarted = true;
                await readbackGate;
            }
            await route.fulfill({ json: { schemaVersion: 1, data: current } });
            return true;
        }
        if (request.path === `${root}/home/connect` && request.method === 'POST') {
            expect(request.body).toEqual({ schemaVersion: 1 });
            current = structuredClone(active.data);
            holdReadback = true;
            await route.fulfill({ json: golden('profile-status') });
            return true;
        }
        if (request.path === `${root}/home/disconnect` && request.method === 'POST') {
            expect(request.body).toEqual({ schemaVersion: 1 });
            current = {
                ...active.data,
                phase: 'disconnected',
                runtime: {
                    ...active.data.runtime!,
                    phase: 'disconnected',
                    killSwitchActive: false,
                    observation: { exists: false, owned: false, up: false, peers: [] },
                },
            };
            await route.fulfill({ json: { schemaVersion: 1, data: current } });
            return true;
        }
        if (request.path === `${root}/home` && request.method === 'DELETE') {
            expect(request.body).toBeUndefined();
            expect(current?.runtime?.phase).toBe('disconnected');
            current = undefined;
            await route.fulfill({ json: golden('delete-result') });
            return true;
        }
        return false;
    });
    await signIn(page, '/wireguard');
    await expect(page.getByText('Noch keine WireGuard-Profile gespeichert.')).toBeVisible();
    const importButton = page.getByRole('button', { name: 'Profil importieren', exact: true });
    await importButton.click();
    const importer = page.getByRole('form', { name: 'Profil importieren' });
    await expect(importer.getByLabel('Profilkennung', { exact: true })).toBeFocused();
    await importer.getByLabel('Profilkennung', { exact: true }).fill('home');
    await importer.getByLabel('Konfiguration für den Import').fill(configuration);
    await importer.getByRole('button', { name: 'Profil importieren', exact: true }).click();
    await expect(page.getByText('Profilimport durch erneutes Laden bestätigt.')).toBeVisible();
    await expect(importer.getByLabel('Konfiguration für den Import')).toHaveValue('');
    await importer.getByRole('button', { name: 'Schließen' }).click();
    await expect(importButton).toBeFocused();
    expect(api.calls.some((call) => call.path.endsWith('/connect'))).toBe(false);
    await page.getByRole('button', { name: 'Verbinden', exact: true }).click();
    const connect = page.getByRole('form', { name: 'Verbinden: home' });
    await expect(connect.getByRole('checkbox')).toBeFocused();
    await connect.getByRole('checkbox').check();
    await connect.getByRole('button', { name: 'Verbinden', exact: true }).click();
    await expect.poll(() => readbackStarted).toBe(true);
    await expect(page.getByText('Aktuell nachgewiesen', { exact: true })).toHaveCount(0);
    await expect(
        page.getByText('Die eigene Tunnelschnittstelle ist aktiv.', { exact: false }),
    ).toHaveCount(0);
    holdReadback = false;
    releaseReadback();
    const observed = page.getByRole('region', { name: 'Aktuell beobachteter Zustand: home' });
    await expect(
        observed
            .getByText('Kill Switch', { exact: true })
            .locator('..')
            .getByText('Aktuell nachgewiesen', { exact: true }),
    ).toBeVisible();
    await expect(
        observed.getByText(
            'Nicht nachgewiesen; bestehende DNS-Server außerhalb des Tunnels können durch den Kill Switch gesperrt sein.',
        ),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: 'Profil löschen', exact: true })).toBeDisabled();
    for (const action of ['Trennen', 'Profil löschen']) {
        await page.getByRole('button', { name: action, exact: true }).click();
        const confirmation = page.getByRole('form', { name: `${action}: home` });
        await confirmation.getByRole('checkbox').check();
        await confirmation.getByRole('button', { name: action, exact: true }).click();
        await expect(
            page.getByText(
                action === 'Trennen'
                    ? 'Die Trennung wurde durch erneute Prüfung bestätigt.'
                    : 'Die Löschung wurde durch erneutes Laden bestätigt.',
            ),
        ).toBeVisible();
    }
    await expect(page.getByText('Noch keine WireGuard-Profile gespeichert.')).toBeVisible();
    expect(
        api.calls
            .filter((call) => call.method !== 'GET')
            .map(({ method, path }) => `${method} ${path}`),
    ).toEqual([
        `PUT ${root}/home`,
        `POST ${root}/home/connect`,
        `POST ${root}/home/disconnect`,
        `DELETE ${root}/home`,
    ]);
    expect(
        await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage })),
    ).not.toContain('AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=');
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});
