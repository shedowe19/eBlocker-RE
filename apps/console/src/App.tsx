// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useState, useSyncExternalStore } from 'react';
import { HashRouter, Link, NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { ConsoleClient } from './api/client';
import { Login } from './features/auth/Login';
import { DevicesPage } from './features/devices/DevicesPage';
import { SystemPage, NetworkPage } from './features/system';
import { ProtectionPage, HttpsPage } from './features/protection';
import { SecurityPage } from './features/security';
import { WireGuardPage } from './features/wireguard/WireGuardPage';
import { FamilyPage } from './features/family';
import { VpnPage } from './features/vpn';
import { UpdatesPage } from './features/updates/UpdatesPage';
import { FeatureCatalog } from './features/catalog/FeatureCatalog';
import { BackupPage } from './features/backup';
import { DiagnosticsPage } from './features/diagnostics';
import { DnsPage } from './features/dns';
import { errors, initialLocale, texts } from './i18n';
import type { Locale } from './i18n';

const defaultClient = new ConsoleClient();
export function App({ client = defaultClient }: { client?: ConsoleClient }) {
    const [locale, setLocale] = useState<Locale>(initialLocale);
    const session = useSyncExternalStore(client.subscribe, client.snapshot);
    const t = texts[locale];
    useEffect(() => {
        void client.bootstrap();
        return () => client.logout();
    }, [client]);
    useEffect(() => {
        if (session.phase !== 'ready') return;
        const active = () => client.markActive();
        const idle = () => client.checkIdle();
        const events = ['pointerdown', 'keydown', 'wheel'] as const;
        events.forEach((event) => window.addEventListener(event, active, { passive: true }));
        document.addEventListener('visibilitychange', idle);
        const timer = setInterval(idle, 1000);
        return () => {
            events.forEach((event) => window.removeEventListener(event, active));
            document.removeEventListener('visibilitychange', idle);
            clearInterval(timer);
        };
    }, [client, session.phase]);
    useEffect(() => {
        document.documentElement.lang = locale;
        try {
            localStorage.setItem('eblocker.console.language', locale);
        } catch {
            /* Storage is optional. */
        }
    }, [locale]);
    return (
        <HashRouter>
            <RouteFocus />
            <div className="app-shell">
                <a
                    className="skip-link"
                    href="#main-content"
                    onClick={(event) => {
                        event.preventDefault();
                        document.getElementById('main-content')?.focus();
                    }}
                >
                    {t.skip}
                </a>
                <aside className="sidebar">
                    <Link to="/devices" className="brand" aria-label="eBlocker">
                        <span className="brand-mark" aria-hidden="true">
                            e
                        </span>
                        eBlocker
                    </Link>
                    <p className="brand-description">{t.app}</p>
                    <nav aria-label={t.app}>
                        {session.phase === 'ready' && (
                            <>
                                {[
                                    ['/devices', t.devices],
                                    ['/protection', t.protection],
                                    ['/https', t.https],
                                    ['/network', t.network],
                                    ['/dns', t.dns],
                                    ['/family', t.family],
                                    ['/vpn', t.vpn],
                                    ['/wireguard', t.wireguard],
                                    ['/security', t.security],
                                    ['/system', t.system],
                                    ['/updates', t.updates],
                                    ['/backup', t.backup],
                                    ['/diagnostics', t.diagnostics],
                                    ['/features', t.features],
                                ].map(([path, label]) => (
                                    <NavLink
                                        key={path}
                                        className={({ isActive }) =>
                                            `nav-link${isActive ? ' active' : ''}`
                                        }
                                        to={path}
                                    >
                                        {label}
                                    </NavLink>
                                ))}
                            </>
                        )}
                        <a className="nav-link" href="/settings/">
                            {t.allSettings}
                            <span aria-hidden="true">↗</span>
                        </a>
                    </nav>
                    <div className="sidebar-footer">
                        <span className="local-dot" aria-hidden="true" />
                        {t.local}
                    </div>
                </aside>
                <div className="workspace">
                    <header className="topbar">
                        <span className="topbar-label">{t.app}</span>
                        <div className="topbar-actions">
                            <label className="language-picker">
                                {t.language}
                                <select
                                    value={locale}
                                    onChange={(event) => setLocale(event.target.value as Locale)}
                                >
                                    <option value="de">Deutsch</option>
                                    <option value="en">English</option>
                                </select>
                            </label>
                            {session.phase === 'ready' && (
                                <button className="button subtle" onClick={() => client.logout()}>
                                    {t.signOut}
                                </button>
                            )}
                        </div>
                    </header>
                    <main id="main-content" tabIndex={-1}>
                        {session.phase === 'loading' && (
                            <p className="state-panel" role="status">
                                {t.loading}
                            </p>
                        )}
                        {session.phase === 'login' && <Login client={client} locale={locale} />}
                        {session.phase === 'error' && (
                            <div className="state-panel">
                                <p role="alert">{errors[locale][session.error]}</p>
                                <button
                                    className="button primary"
                                    onClick={() => void client.bootstrap()}
                                >
                                    {t.retry}
                                </button>
                            </div>
                        )}
                        {(session.phase === 'expired' || session.phase === 'signedOut') && (
                            <div className="state-panel">
                                <h1>{session.phase === 'expired' ? t.expired : t.signedOut}</h1>
                                <button
                                    className="button primary"
                                    onClick={() => void client.bootstrap()}
                                >
                                    {t.continue}
                                </button>
                            </div>
                        )}
                        {session.phase === 'ready' && (
                            <Routes>
                                <Route
                                    path="/dns"
                                    element={<DnsPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/backup"
                                    element={<BackupPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/diagnostics"
                                    element={<DiagnosticsPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/features"
                                    element={<FeatureCatalog locale={locale} />}
                                />
                                <Route
                                    path="/updates"
                                    element={<UpdatesPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/family"
                                    element={<FamilyPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/vpn"
                                    element={<VpnPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/system"
                                    element={<SystemPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/network"
                                    element={<NetworkPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/protection"
                                    element={<ProtectionPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/https"
                                    element={<HttpsPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/security"
                                    element={<SecurityPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="/wireguard"
                                    element={<WireGuardPage client={client} locale={locale} />}
                                />
                                <Route
                                    path="*"
                                    element={<DevicesPage client={client} locale={locale} />}
                                />
                            </Routes>
                        )}
                    </main>
                    <footer className="legal-footer">
                        <a href="/next/THIRD_PARTY_NOTICES.txt">{t.licenses}</a>
                    </footer>
                </div>
            </div>
        </HashRouter>
    );
}

function RouteFocus() {
    const location = useLocation();
    useEffect(() => {
        document.getElementById('main-content')?.focus();
    }, [location.pathname]);
    return null;
}
