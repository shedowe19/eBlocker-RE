// SPDX-License-Identifier: EUPL-1.2
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { DeviceDetails, DeviceList, type Device } from './index';

const laptop: Device = {
    id: 'device:001122334455',
    name: 'Mein Laptop',
    vendor: 'Example Computer',
    hardwareAddress: '00:11:22:33:44:55',
    ipAddresses: ['192.168.1.10', '2001:db8::10'],
    lastSeen: 1750000000.5,
    isOnline: true,
    isCurrentDevice: true,
    isGateway: false,
    isEblocker: false,
    isVpnClient: false,
    enabled: true,
    paused: false,
    filterAdsEnabled: true,
    filterTrackersEnabled: true,
    malwareFilterEnabled: false,
    sslEnabled: false,
    filterMode: 'AUTOMATIC',
};
const phone: Device = {
    ...laptop,
    id: 'device:66778899aabb',
    name: 'Telefon',
    vendor: 'Example Mobile',
    hardwareAddress: '66:77:88:99:aa:bb',
    ipAddresses: ['192.168.1.11'],
    isOnline: false,
    isCurrentDevice: false,
    paused: true,
};

describe('device overview', () => {
    it.each(['Laptop', '2001:DB8::10', '00:11:22', 'COMPUTER'])(
        'finds devices by %s',
        async (query) => {
            const user = userEvent.setup();
            render(<DeviceList devices={[laptop, phone]} locale="de" onSelect={vi.fn()} />);
            await user.type(screen.getByRole('searchbox', { name: 'Geräte suchen' }), query);
            expect(
                screen.getByRole('button', { name: 'Details zu Mein Laptop anzeigen' }),
            ).toBeInTheDocument();
            expect(
                screen.queryByRole('button', { name: 'Details zu Telefon anzeigen' }),
            ).not.toBeInTheDocument();
            expect(screen.getByRole('status')).toHaveTextContent('1 von 2 Geräten');
        },
    );

    it('combines search with the connection filter and resets both', async () => {
        const user = userEvent.setup();
        render(<DeviceList devices={[laptop, phone]} locale="de" onSelect={vi.fn()} />);
        await user.selectOptions(screen.getByRole('combobox', { name: 'Verbindung' }), 'offline');
        expect(
            screen.getByRole('button', { name: 'Details zu Telefon anzeigen' }),
        ).toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Details zu Mein Laptop anzeigen' }),
        ).not.toBeInTheDocument();
        await user.type(screen.getByRole('searchbox'), 'Laptop');
        expect(screen.getByRole('heading', { name: 'Keine passenden Geräte' })).toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Suche und Filter zurücksetzen' }));
        expect(screen.getByRole('status')).toHaveTextContent('2 von 2 Geräten');
        expect(screen.getByRole('searchbox')).toHaveValue('');
        expect(screen.getByRole('combobox')).toHaveValue('all');
    });

    it('offers a keyboard-accessible device selection with the original identifier', async () => {
        const user = userEvent.setup();
        const onSelect = vi.fn();
        render(<DeviceList devices={[laptop]} locale="en" onSelect={onSelect} />);
        const openButton = screen.getByRole('button', { name: 'View details for Mein Laptop' });
        openButton.focus();
        await user.keyboard('{Enter}');
        expect(onSelect).toHaveBeenCalledWith(laptop.id);
        expect(screen.getByRole('table', { name: 'Devices on your network' })).toBeInTheDocument();
    });

    it('distinguishes an empty network from an empty search', () => {
        render(<DeviceList devices={[]} locale="en" onSelect={vi.fn()} />);
        expect(screen.getByRole('heading', { name: 'No devices yet' })).toBeInTheDocument();
        expect(screen.queryByRole('searchbox')).not.toBeInTheDocument();
        expect(
            screen.queryByRole('button', { name: 'Reset search and filters' }),
        ).not.toBeInTheDocument();
    });

    it('switches language and handles unnamed devices without inventing attributes', () => {
        const unnamed = { ...laptop, name: '  ', vendor: undefined, ipAddresses: [] };
        const { rerender } = render(
            <DeviceList devices={[unnamed]} locale="de" onSelect={vi.fn()} />,
        );
        expect(
            screen.getByRole('button', { name: 'Details zu Unbenanntes Gerät anzeigen' }),
        ).toBeInTheDocument();
        expect(screen.getByText('Keine IP-Adresse bekannt')).toBeInTheDocument();
        rerender(<DeviceList devices={[unnamed]} locale="en" onSelect={vi.fn()} />);
        expect(
            screen.getByRole('button', { name: 'View details for Unnamed device' }),
        ).toBeInTheDocument();
        expect(screen.getByText('No known IP address')).toBeInTheDocument();
        expect(screen.queryByText('Example Computer')).not.toBeInTheDocument();
    });

    it('does not show synthetic appliance defaults as enabled protection', () => {
        render(
            <DeviceList
                devices={[{ ...laptop, name: 'eBlocker', isEblocker: true }]}
                locale="de"
                onSelect={vi.fn()}
            />,
        );
        expect(screen.getByText('Nicht anwendbar')).toBeInTheDocument();
        expect(screen.queryByText('Aktiviert')).not.toBeInTheDocument();
    });
});

describe('device details', () => {
    it('explains the appliance entry without presenting synthetic filter defaults', () => {
        render(
            <DeviceDetails
                device={{ ...laptop, isEblocker: true, paused: true }}
                locale="en"
                onBack={vi.fn()}
            />,
        );
        expect(screen.getByText('Not applicable')).toBeInTheDocument();
        expect(screen.queryByText('Paused')).not.toBeInTheDocument();
        const filterSettings = screen.getByRole('region', { name: 'Filter settings' });
        expect(
            within(filterSettings).getByText(/This entry is eBlocker itself/),
        ).toBeInTheDocument();
        expect(within(filterSettings).queryByText('Enabled')).not.toBeInTheDocument();
        expect(within(filterSettings).queryByText('Disabled')).not.toBeInTheDocument();
        expect(within(filterSettings).queryByText('Automatic')).not.toBeInTheDocument();
    });

    it('shows pausing before enabled state and keeps filter configuration separate', () => {
        render(
            <DeviceDetails
                device={{ ...laptop, paused: true, enabled: false }}
                locale="en"
                onBack={vi.fn()}
            />,
        );
        expect(screen.getByText('Paused')).toBeInTheDocument();
        const filterSettings = screen.getByRole('region', { name: 'Filter settings' });
        expect(within(filterSettings).getAllByText('Enabled')).toHaveLength(2);
        expect(within(filterSettings).getAllByText('Disabled')).toHaveLength(2);
        expect(within(filterSettings).getByText('Automatic')).toBeInTheDocument();
        const network = screen.getByRole('region', { name: 'Network' });
        expect(within(network).getByText('192.168.1.10')).toBeInTheDocument();
        expect(within(network).getByText('2001:db8::10')).toBeInTheDocument();
    });

    it('converts Unix seconds, including fractions, into the correct date', () => {
        const { container } = render(
            <DeviceDetails device={laptop} locale="de" onBack={vi.fn()} />,
        );
        const date = new Date(laptop.lastSeen! * 1000);
        const time = container.querySelector('time');
        expect(time).toHaveAttribute('dateTime', '2025-06-15T15:06:40.500Z');
        expect(time).toHaveTextContent(
            new Intl.DateTimeFormat('de', { dateStyle: 'medium', timeStyle: 'short' }).format(date),
        );
    });

    it('does not invent a last-seen date or filter mode', () => {
        const { container } = render(
            <DeviceDetails
                device={{ ...laptop, lastSeen: undefined, filterMode: undefined }}
                locale="en"
                onBack={vi.fn()}
            />,
        );
        expect(container.querySelector('time')).not.toBeInTheDocument();
        expect(screen.getAllByText('Not known')).toHaveLength(2);
    });

    it('returns to the list using the provided action', async () => {
        const user = userEvent.setup();
        const onBack = vi.fn();
        render(<DeviceDetails device={phone} locale="de" onBack={onBack} />);
        await user.click(screen.getByRole('button', { name: 'Zur Geräteübersicht' }));
        expect(onBack).toHaveBeenCalledOnce();
    });
});
