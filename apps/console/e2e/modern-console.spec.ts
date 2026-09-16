// SPDX-License-Identifier: EUPL-1.2
// CI browser scenarios: authored and type-checked locally, not executed here because
// Chromium cannot create the required AF_UNIX sockets in this workspace.
import { expect, test } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { device } from '../src/test/fixtures';
import { installApi, signIn } from './support';

test('new navigation displays real API payloads, preserves focus and switches language', async ({
    page,
}) => {
    const api = await installApi(page);
    await signIn(page);
    const pages = [
        ['System', 'Systemstatus', '4.0.3'],
        ['Netzwerk', 'Netzwerk', 'fe80::20'],
        ['Schutz', 'Schutz und Ausnahmen', 'Globale Werbeliste'],
        ['HTTPS', 'HTTPS-Analyse', 'Browser Test CA'],
        [
            'Sicherheit',
            'Anmeldesicherheit',
            'Für die Verwaltung wird ein Administratorpasswort benötigt.',
        ],
        ['WireGuard', 'WireGuard', 'eth0'],
    ];
    for (const [link, heading, marker] of pages) {
        const navigation = page.getByRole('navigation');
        await navigation.getByRole('link', { name: link, exact: true }).click();
        await expect(page.getByRole('heading', { name: heading, level: 1 })).toBeVisible();
        await expect(page.getByText(marker)).toBeVisible();
        await expect(navigation.getByRole('link', { name: link, exact: true })).toHaveAttribute(
            'aria-current',
            'page',
        );
        await expect(page.getByRole('main')).toBeFocused();
    }
    await page.getByLabel('Sprache').selectOption('en');
    await expect(
        page.getByText('Manage profiles, control connections and review network paths.'),
    ).toBeVisible();
    await page
        .getByRole('navigation')
        .getByRole('link', { name: 'Protection', exact: true })
        .click();
    await expect(page.getByRole('heading', { name: 'Protection and exceptions' })).toBeVisible();
    await expect(page.getByText('Global advertising list')).toBeVisible();
    await page.getByRole('navigation').getByRole('link', { name: 'Devices', exact: true }).click();
    await expect(
        page.getByRole('button', { name: 'View details for Arbeitszimmer' }),
    ).toBeVisible();
    await expect(page.getByRole('alert')).toHaveCount(0);
    expect(api.calls.every((call) => call.method === 'GET')).toBe(true);
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});

test('device settings PATCH sends only changed fields and the confirmed values survive refetch', async ({
    page,
}) => {
    let currentDevice = { ...device };
    let listsReadAfterWrite = 0;
    let written = false;
    const api = await installApi(page, async (route, request) => {
        if (request.path === '/api/adminconsole/devices' && request.method === 'GET') {
            if (written) ++listsReadAfterWrite;
            await route.fulfill({ json: [currentDevice] });
            return true;
        }
        if (
            request.path ===
                `/api/adminconsole/devices/${encodeURIComponent(device.id)}/settings` &&
            request.method === 'PATCH'
        ) {
            expect(request.body).toEqual({ name: 'Browser Laptop', filterAdsEnabled: false });
            currentDevice = { ...currentDevice, name: 'Browser Laptop', filterAdsEnabled: false };
            written = true;
            await route.fulfill({ json: currentDevice });
            return true;
        }
        return false;
    });
    await signIn(page);
    await page.getByRole('button', { name: 'Details zu Arbeitszimmer anzeigen' }).click();
    await page.getByRole('button', { name: 'Einstellungen bearbeiten' }).click();
    const form = page.getByRole('form', { name: 'Geräteeinstellungen bearbeiten' });
    await form.getByLabel('Gerätename', { exact: true }).fill('Browser Laptop');
    await form.getByRole('checkbox', { name: 'Werbung', exact: true }).uncheck();
    expect((await new AxeBuilder({ page }).include('.device-editor').analyze()).violations).toEqual(
        [],
    );
    await form.getByRole('button', { name: 'Änderungen speichern' }).click();
    await expect(page.getByText('Die Geräteeinstellungen wurden gespeichert.')).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Browser Laptop' })).toBeVisible();
    await expect.poll(() => listsReadAfterWrite).toBeGreaterThan(0);
    await page.getByRole('button', { name: 'Einstellungen bearbeiten' }).click();
    await expect(page.getByRole('checkbox', { name: 'Werbung', exact: true })).not.toBeChecked();
    await expect(page.getByLabel('Gerätename', { exact: true })).toHaveValue('Browser Laptop');
    expect(api.calls.filter((call) => call.method === 'PATCH')).toHaveLength(1);
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});

test('incorrect password changes keep the session while a confirmed change signs out and erases fields', async ({
    page,
}) => {
    let attempts = 0;
    const api = await installApi(page, async (route, request) => {
        if (request.path !== '/api/adminconsole/authentication/enable' || request.method !== 'POST')
            return false;
        ++attempts;
        expect(request.body).toEqual({
            currentPassword: attempts === 1 ? 'incorrect-password' : 'browser-test-password',
            newPassword: 'replacement-password',
        });
        await route.fulfill(
            attempts === 1
                ? { status: 401, json: 'error.credentials.invalid' }
                : { status: 204, body: '' },
        );
        return true;
    });
    await signIn(page, '/security');
    await page.getByRole('button', { name: 'Passwort ändern', exact: true }).click();
    await page.getByLabel('Aktuelles Administratorpasswort').fill('incorrect-password');
    await page
        .getByLabel('Neues Administratorpasswort', { exact: true })
        .fill('replacement-password');
    await page.getByLabel('Neues Passwort wiederholen').fill('replacement-password');
    expect((await new AxeBuilder({ page }).include('.security-form').analyze()).violations).toEqual(
        [],
    );
    await page.getByRole('button', { name: 'Passwort speichern', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('Das Passwort ist nicht korrekt.');
    await expect(page.getByRole('button', { name: 'Abmelden', exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'Anmeldesicherheit' })).toBeVisible();
    await expect(page.getByLabel('Aktuelles Administratorpasswort')).toHaveValue('');
    await expect(page.getByLabel('Neues Administratorpasswort', { exact: true })).toHaveValue('');
    await expect(page.getByLabel('Neues Passwort wiederholen')).toHaveValue('');
    await page.getByLabel('Aktuelles Administratorpasswort').fill('browser-test-password');
    await page
        .getByLabel('Neues Administratorpasswort', { exact: true })
        .fill('replacement-password');
    await page.getByLabel('Neues Passwort wiederholen').fill('replacement-password');
    await page.getByRole('button', { name: 'Passwort speichern', exact: true }).click();
    await expect(
        page.getByRole('heading', { name: 'Du bist auf dieser Oberfläche abgemeldet.' }),
    ).toBeVisible();
    await expect(page.getByLabel('Aktuelles Administratorpasswort')).toHaveCount(0);
    await expect(page.getByRole('button', { name: 'Abmelden', exact: true })).toHaveCount(0);
    expect(attempts).toBe(2);
    expect(
        await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage })),
    ).not.toMatch(/browser-token|browser-test-password|replacement-password/);
    expect(api.unexpected).toEqual([]);
    expect(api.browserErrors).toEqual([]);
});
