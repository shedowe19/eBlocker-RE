import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { appliance, device, token } from '../src/test/fixtures';

test('login, devices, language, deep link, refresh failure and logout', async ({ page }) => {
    let loggedIn = false;
    let unavailable = false;
    const calls: string[] = [];
    await page.addInitScript(() => localStorage.setItem('eblocker.console.language', 'de'));
    await page.route('**/api/adminconsole/**', async (route) => {
        const request = route.request();
        const path = new URL(request.url()).pathname;
        calls.push(path);
        if (path.endsWith('/token/ADMINCONSOLE'))
            return route.fulfill({ json: token(true, 'initial') });
        if (path.endsWith('/wait')) return route.fulfill({ json: 0 });
        if (path.endsWith('/login/ADMINCONSOLE')) {
            if (request.postDataJSON().currentPassword !== 'local-test-password')
                return route.fulfill({ status: 401, json: 'error.credentials.invalid' });
            loggedIn = true;
            return route.fulfill({ json: token(true, 'authenticated') });
        }
        if (path.endsWith('/devices')) {
            expect(loggedIn).toBe(true);
            expect(request.headers().authorization).toBe('Bearer authenticated');
            return unavailable
                ? route.fulfill({ status: 503, json: 'unavailable' })
                : route.fulfill({ json: [device, appliance] });
        }
        return route.fulfill({ status: 404, json: 'unexpected endpoint' });
    });
    const errors: string[] = [];
    page.on('pageerror', (error) => errors.push(error.message));
    await page.goto('/next/');
    await expect(page.getByLabel('Administratorpasswort')).toBeVisible();
    expect(calls.some((path) => path.endsWith('/devices'))).toBe(false);
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    await page.getByLabel('Administratorpasswort').fill('local-test-password');
    await page.getByRole('button', { name: 'Anmelden', exact: true }).click();
    await expect(page.getByRole('button', { name: /Arbeitszimmer/ })).toBeVisible();
    await page.getByLabel('Geräte suchen').fill('2001:db8::20');
    await page.getByRole('button', { name: /Arbeitszimmer/ }).click();
    await expect(page.getByRole('heading', { name: 'Arbeitszimmer' })).toBeVisible();
    await expect(page.getByText('2001:db8::20', { exact: true })).toBeVisible();
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    await page.getByLabel('Sprache').selectOption('en');
    await expect(page.getByRole('heading', { name: 'Your network at a glance' })).toBeVisible();
    await page.getByRole('button', { name: /Back to/ }).click();
    unavailable = true;
    await page.getByRole('button', { name: 'Refresh', exact: true }).click();
    await expect(page.getByRole('alert')).toContainText('could not be refreshed');
    await page.getByRole('button', { name: 'Sign out', exact: true }).click();
    await expect(page.getByText('You have signed out of this interface.')).toBeVisible();
    await expect(page.getByText('Arbeitszimmer')).toHaveCount(0);
    expect(
        await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage })),
    ).not.toMatch(/authenticated|local-test-password/);
    expect(errors).toEqual([]);
});

test('hash deep link displays the synthetic appliance without a detail API request', async ({
    page,
}) => {
    await page.addInitScript(() => localStorage.setItem('eblocker.console.language', 'de'));
    await page.route('**/api/adminconsole/**', async (route) => {
        const path = new URL(route.request().url()).pathname;
        if (path.endsWith('/token/ADMINCONSOLE')) return route.fulfill({ json: token() });
        expect(path).toBe('/api/adminconsole/devices');
        return route.fulfill({ json: [appliance] });
    });
    await page.goto(`/next/#/devices/${encodeURIComponent(appliance.id)}`);
    await expect(page.getByRole('heading', { name: 'eBlocker', exact: true })).toBeVisible();
    await expect(page.getByText('Nicht anwendbar')).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { name: 'eBlocker', exact: true })).toBeVisible();
});
