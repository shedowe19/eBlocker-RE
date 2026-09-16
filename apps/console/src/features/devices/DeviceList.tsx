// SPDX-License-Identifier: EUPL-1.2
import { useId, useMemo, useState } from 'react';
import { deviceMessages } from './messages';
import { ConnectionStatus, deviceName, DeviceRoles, ProtectionStatus } from './presentation';
import type { Device, DeviceLocale } from './types';

export interface DeviceListProps {
    devices: Device[];
    locale: DeviceLocale;
    onSelect: (id: string) => void;
}

type ConnectionFilter = 'all' | 'online' | 'offline';

export function DeviceList({ devices, locale, onSelect }: DeviceListProps) {
    const t = deviceMessages[locale];
    const id = useId();
    const [query, setQuery] = useState('');
    const [connection, setConnection] = useState<ConnectionFilter>('all');
    const visibleDevices = useMemo(() => {
        const search = query.trim().toLocaleLowerCase(locale);
        return devices.filter((device) => {
            if (connection === 'online' && !device.isOnline) return false;
            if (connection === 'offline' && device.isOnline) return false;
            return [
                deviceName(device, locale),
                device.vendor,
                device.hardwareAddress,
                ...device.ipAddresses,
            ].some((value) => value?.toLocaleLowerCase(locale).includes(search));
        });
    }, [devices, query, connection, locale]);

    function resetFilters() {
        setQuery('');
        setConnection('all');
    }

    return (
        <section className="devices" aria-labelledby={`${id}-title`}>
            <header className="devices__header">
                <div>
                    <h2 id={`${id}-title`}>{t.title}</h2>
                    <p className="devices__muted">{t.description}</p>
                </div>
                <p className="devices__count" role="status">
                    {t.shown(visibleDevices.length, devices.length)}
                </p>
            </header>

            {devices.length > 0 && (
                <div className="devices__toolbar">
                    <div className="devices__field devices__field--search">
                        <label htmlFor={`${id}-search`}>{t.search}</label>
                        <input
                            id={`${id}-search`}
                            type="search"
                            value={query}
                            placeholder={t.searchPlaceholder}
                            onChange={(event) => setQuery(event.target.value)}
                        />
                    </div>
                    <div className="devices__field">
                        <label htmlFor={`${id}-connection`}>{t.connectionFilter}</label>
                        <select
                            id={`${id}-connection`}
                            value={connection}
                            onChange={(event) =>
                                setConnection(event.target.value as ConnectionFilter)
                            }
                        >
                            <option value="all">{t.all}</option>
                            <option value="online">{t.online}</option>
                            <option value="offline">{t.offline}</option>
                        </select>
                    </div>
                </div>
            )}

            {visibleDevices.length > 0 ? (
                <div className="devices__table-wrap">
                    <table className="devices__table">
                        <caption className="devices__visually-hidden">{t.title}</caption>
                        <thead>
                            <tr>
                                <th scope="col">{t.device}</th>
                                <th scope="col">{t.addresses}</th>
                                <th scope="col">{t.connectionFilter}</th>
                                <th scope="col">{t.protection}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {visibleDevices.map((device) => (
                                <tr key={device.id}>
                                    <td className="devices__name-cell">
                                        <button
                                            type="button"
                                            className="devices__name"
                                            aria-label={t.openDetails(deviceName(device, locale))}
                                            onClick={() => onSelect(device.id)}
                                        >
                                            {deviceName(device, locale)}
                                        </button>
                                        {device.vendor?.trim() && (
                                            <span className="devices__vendor">{device.vendor}</span>
                                        )}
                                        <DeviceRoles device={device} locale={locale} />
                                    </td>
                                    <td>
                                        <span className="devices__mobile-label" aria-hidden="true">
                                            {t.addresses}
                                        </span>
                                        {device.ipAddresses.length ? (
                                            <ul className="devices__addresses">
                                                {device.ipAddresses.map((address, index) => (
                                                    <li key={`${address}-${index}`}>{address}</li>
                                                ))}
                                            </ul>
                                        ) : (
                                            <span className="devices__muted">{t.noAddress}</span>
                                        )}
                                    </td>
                                    <td>
                                        <span className="devices__mobile-label" aria-hidden="true">
                                            {t.connectionFilter}
                                        </span>
                                        <ConnectionStatus
                                            online={device.isOnline}
                                            locale={locale}
                                        />
                                    </td>
                                    <td>
                                        <span className="devices__mobile-label" aria-hidden="true">
                                            {t.protection}
                                        </span>
                                        <ProtectionStatus device={device} locale={locale} />
                                    </td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </div>
            ) : (
                <div className="devices__empty">
                    <h3>{devices.length ? t.noResultsTitle : t.noDevicesTitle}</h3>
                    <p>{devices.length ? t.noResultsText : t.noDevicesText}</p>
                    {devices.length > 0 && (
                        <button
                            className="devices__secondary-button"
                            type="button"
                            onClick={resetFilters}
                        >
                            {t.reset}
                        </button>
                    )}
                </div>
            )}
        </section>
    );
}
