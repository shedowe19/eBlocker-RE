// SPDX-License-Identifier: EUPL-1.2
import { useEffect, useRef, useState } from 'react';
import {
    Link,
    Navigate,
    Route,
    Routes,
    useLocation,
    useNavigate,
    useParams,
} from 'react-router-dom';
import type { ConsoleClient } from '../../api/client';
import { errorCode } from '../../api/http';
import type { ErrorCode } from '../../api/http';
import type { Device, DeviceSettingsPatch } from '../../api/contracts';
import { DeviceList } from './DeviceList';
import { DeviceDetails } from './DeviceDetails';
import { errors, texts } from '../../i18n';
import type { Locale } from '../../i18n';

export function DevicesPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = texts[locale];
    const [devices, setDevices] = useState<Device[]>();
    const [failure, setFailure] = useState<ErrorCode>();
    const [loading, setLoading] = useState(false);
    const [updated, setUpdated] = useState<Date>();
    const [revision, setRevision] = useState(0);
    const [saving, setSaving] = useState(false);
    const savingRef = useRef(false);
    const requestRef = useRef<AbortController | undefined>(undefined);
    const mountedRef = useRef(true);
    const navigate = useNavigate();
    const location = useLocation();
    useEffect(() => {
        mountedRef.current = true;
        return () => {
            mountedRef.current = false;
        };
    }, []);
    useEffect(() => {
        document.getElementById('main-content')?.focus();
    }, [location.pathname]);
    useEffect(() => {
        if (savingRef.current) return;
        const controller = new AbortController();
        requestRef.current = controller;
        setLoading(true);
        void client
            .devices(controller.signal)
            .then((result) => {
                if (!controller.signal.aborted) {
                    setDevices(result);
                    setUpdated(new Date());
                    setFailure(undefined);
                }
            })
            .catch((error) => {
                if (!controller.signal.aborted) setFailure(errorCode(error));
            })
            .finally(() => {
                if (!controller.signal.aborted) setLoading(false);
            });
        return () => controller.abort();
    }, [client, revision]);
    useEffect(() => {
        const timer = setInterval(() => {
            if (document.visibilityState === 'visible' && !savingRef.current)
                setRevision((value) => value + 1);
        }, 30_000);
        return () => clearInterval(timer);
    }, []);
    async function saveDevice(id: string, patch: DeviceSettingsPatch): Promise<Device> {
        if (savingRef.current) throw new Error('A device update is already pending');
        savingRef.current = true;
        setSaving(true);
        // Discovery responses started before a write must not replace its confirmed result.
        requestRef.current?.abort();
        setLoading(false);
        try {
            const updatedDevice = await client.updateDevice(id, patch);
            if (mountedRef.current) {
                setDevices((current) =>
                    current?.map((device) => (device.id === id ? updatedDevice : device)),
                );
                setUpdated(new Date());
                setFailure(undefined);
            }
            return updatedDevice;
        } finally {
            savingRef.current = false;
            if (mountedRef.current) {
                setSaving(false);
                setRevision((value) => value + 1);
            }
        }
    }
    const clients = devices?.filter((device) => !device.isEblocker);
    return (
        <>
            <div className="page-heading">
                <div>
                    <span className="eyebrow">eBlocker</span>
                    <h1>{t.welcome}</h1>
                    <p>{t.introduction}</p>
                </div>
                <button
                    className="button secondary"
                    onClick={() => setRevision((value) => value + 1)}
                    disabled={loading || saving}
                >
                    {loading ? t.refreshing : t.refresh}
                </button>
            </div>
            <p className="freshness">
                {updated
                    ? `${t.lastUpdate}: ${new Intl.DateTimeFormat(locale, { timeStyle: 'medium' }).format(updated)}`
                    : t.neverUpdated}
            </p>
            {failure && (
                <div className="error-banner" role="alert">
                    <p>
                        {devices && `${t.stale} `}
                        {errors[locale][failure]}
                    </p>
                    <button
                        className="button secondary"
                        disabled={loading || saving}
                        onClick={() => setRevision((value) => value + 1)}
                    >
                        {t.retry}
                    </button>
                </div>
            )}
            {!devices && loading && (
                <p className="state-panel" role="status">
                    {t.loadingDevices}
                </p>
            )}
            {devices && clients && (
                <>
                    <div className="summary-grid" aria-label={t.devices}>
                        <Summary label={t.deviceCount} value={clients.length} />
                        <Summary
                            label={t.onlineCount}
                            value={clients.filter((device) => device.isOnline).length}
                        />
                        <Summary
                            label={t.configuredCount}
                            value={
                                clients.filter((device) => device.enabled && !device.paused).length
                            }
                            note={t.settingNote}
                        />
                    </div>
                    <Routes>
                        <Route path="/" element={<Navigate to="/devices" replace />} />
                        <Route
                            path="/devices"
                            element={
                                <DeviceList
                                    devices={devices}
                                    locale={locale}
                                    onSelect={(id) =>
                                        navigate(`/devices/${encodeURIComponent(id)}`)
                                    }
                                />
                            }
                        />
                        <Route
                            path="/devices/:id"
                            element={
                                <Details devices={devices} locale={locale} onSave={saveDevice} />
                            }
                        />
                        <Route
                            path="*"
                            element={
                                <div className="state-panel">
                                    <p>{t.missingPage}</p>
                                    <Link to="/devices">{t.back}</Link>
                                </div>
                            }
                        />
                    </Routes>
                </>
            )}
        </>
    );
}
function Summary({ label, value, note }: { label: string; value: number; note?: string }) {
    return (
        <div className="summary-card">
            <span>{label}</span>
            <strong>{value}</strong>
            {note && <small>{note}</small>}
        </div>
    );
}
function Details({
    devices,
    locale,
    onSave,
}: {
    devices: Device[];
    locale: Locale;
    onSave: (id: string, patch: DeviceSettingsPatch) => Promise<Device>;
}) {
    const { id } = useParams();
    const navigate = useNavigate();
    // The appliance entry is synthetic and only exists in the list response.
    const device = devices.find((item) => item.id === id);
    return device ? (
        <DeviceDetails
            device={device}
            locale={locale}
            onBack={() => navigate('/devices')}
            onSave={(patch) => onSave(device.id, patch)}
        />
    ) : (
        <div className="state-panel">
            <p>{texts[locale].missing}</p>
            <Link to="/devices">{texts[locale].back}</Link>
        </div>
    );
}
