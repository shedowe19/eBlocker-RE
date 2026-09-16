// SPDX-License-Identifier: EUPL-1.2
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../api/http';
import { appliance, device } from '../../test/fixtures';
import type { Device } from './types';
import { DeviceDetails } from './DeviceDetails';

describe('device settings editor', () => {
    it('sends only changed values, trims the name and displays the confirmed result', async () => {
        const user = userEvent.setup();
        const onSave = vi
            .fn()
            .mockResolvedValue({ ...device, name: 'Neuer Name', filterAdsEnabled: false });
        render(<DeviceDetails device={device} locale="de" onBack={vi.fn()} onSave={onSave} />);
        await user.click(screen.getByRole('button', { name: 'Einstellungen bearbeiten' }));
        expect(screen.getByRole('textbox', { name: 'Gerätename' })).toHaveFocus();
        expect(screen.getByRole('button', { name: 'Änderungen speichern' })).toBeDisabled();
        await user.clear(screen.getByRole('textbox', { name: 'Gerätename' }));
        await user.type(screen.getByRole('textbox', { name: 'Gerätename' }), '  Neuer Name  ');
        await user.click(screen.getByRole('checkbox', { name: 'Werbung' }));
        await user.click(screen.getByRole('button', { name: 'Änderungen speichern' }));
        await screen.findByText('Die Geräteeinstellungen wurden gespeichert.');
        expect(onSave).toHaveBeenCalledExactlyOnceWith({
            name: 'Neuer Name',
            filterAdsEnabled: false,
        });
        expect(screen.getByRole('heading', { name: 'Neuer Name' })).toBeInTheDocument();
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Einstellungen bearbeiten' })).toHaveFocus();
    });

    it('keeps failed changes as a draft, shows no raw error and can cancel without changing the device', async () => {
        const user = userEvent.setup();
        const onSave = vi.fn().mockRejectedValue(new Error('secret private server response'));
        render(<DeviceDetails device={device} locale="en" onBack={vi.fn()} onSave={onSave} />);
        await user.click(screen.getByRole('button', { name: 'Edit settings' }));
        await user.clear(screen.getByRole('textbox', { name: 'Device name' }));
        await user.type(screen.getByRole('textbox', { name: 'Device name' }), 'Unsaved name');
        await user.click(screen.getByRole('button', { name: 'Save changes' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(
            'The changes could not be confirmed.',
        );
        expect(screen.queryByText(/secret private/)).not.toBeInTheDocument();
        expect(screen.getByRole('textbox')).toHaveValue('Unsaved name');
        expect(screen.getByRole('heading', { name: device.name })).toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Cancel' }));
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
        expect(screen.getByRole('heading', { name: device.name })).toBeInTheDocument();
        await user.click(screen.getByRole('button', { name: 'Edit settings' }));
        expect(screen.getByRole('textbox')).toHaveValue(device.name);
        expect(onSave).toHaveBeenCalledOnce();
    });

    it('blocks repeated submits and cancellation while awaiting confirmation', async () => {
        const user = userEvent.setup();
        let complete!: (result: Device) => void;
        const onSave = vi.fn(
            () =>
                new Promise<Device>((resolve) => {
                    complete = resolve;
                }),
        );
        render(<DeviceDetails device={device} locale="en" onBack={vi.fn()} onSave={onSave} />);
        await user.click(screen.getByRole('button', { name: 'Edit settings' }));
        await user.click(screen.getByRole('checkbox', { name: 'Trackers' }));
        const form = screen.getByRole('form', { name: 'Edit device settings' });
        fireEvent.submit(form);
        fireEvent.submit(form);
        expect(onSave).toHaveBeenCalledExactlyOnceWith({ filterTrackersEnabled: false });
        expect(screen.getByRole('button', { name: 'Saving changes …' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
        expect(screen.getByRole('button', { name: 'Back to devices' })).toBeDisabled();
        expect(screen.getByRole('checkbox', { name: 'Ads' })).toBeDisabled();
        await act(async () => complete({ ...device, filterTrackersEnabled: false }));
        expect(screen.getByRole('status')).toHaveTextContent('Device settings have been saved.');
    });

    it.each([appliance, { ...device, isGateway: true }])(
        'does not expose editing for protected infrastructure entries',
        (entry) => {
            render(<DeviceDetails device={entry} locale="en" onBack={vi.fn()} onSave={vi.fn()} />);
            expect(screen.queryByRole('button', { name: 'Edit settings' })).not.toBeInTheDocument();
            expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
        },
    );

    it('requires a non-empty replacement name before saving', async () => {
        const user = userEvent.setup();
        const onSave = vi.fn();
        render(<DeviceDetails device={device} locale="de" onBack={vi.fn()} onSave={onSave} />);
        await user.click(screen.getByRole('button', { name: 'Einstellungen bearbeiten' }));
        await user.clear(screen.getByRole('textbox', { name: 'Gerätename' }));
        expect(screen.getByRole('textbox')).toHaveAttribute('aria-invalid', 'true');
        expect(screen.getByText('Gib einen Namen mit 1 bis 50 Zeichen ein.')).toBeInTheDocument();
        expect(screen.getByRole('button', { name: 'Änderungen speichern' })).toBeDisabled();
        fireEvent.submit(screen.getByRole('form'));
        expect(onSave).not.toHaveBeenCalled();
    });

    it('preserves the draft when the language changes and resets it for another device', async () => {
        const user = userEvent.setup();
        const onSave = vi.fn();
        const { rerender } = render(
            <DeviceDetails device={device} locale="de" onBack={vi.fn()} onSave={onSave} />,
        );
        await user.click(screen.getByRole('button', { name: 'Einstellungen bearbeiten' }));
        await user.clear(screen.getByRole('textbox'));
        await user.type(screen.getByRole('textbox'), 'Draft');
        rerender(<DeviceDetails device={device} locale="en" onBack={vi.fn()} onSave={onSave} />);
        expect(screen.getByRole('textbox', { name: 'Device name' })).toHaveValue('Draft');
        rerender(
            <DeviceDetails
                device={{ ...device, id: 'device:other', name: 'Other' }}
                locale="en"
                onBack={vi.fn()}
                onSave={onSave}
            />,
        );
        expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
        expect(screen.getByRole('heading', { name: 'Other' })).toBeInTheDocument();
    });

    it('keeps protection unchanged during a pause while permitting a name update', async () => {
        const user = userEvent.setup();
        const onSave = vi.fn().mockResolvedValue({ ...device, paused: true, name: 'Renamed' });
        render(
            <DeviceDetails
                device={{ ...device, paused: true }}
                locale="en"
                onBack={vi.fn()}
                onSave={onSave}
            />,
        );
        await user.click(screen.getByRole('button', { name: 'Edit settings' }));
        expect(screen.getByRole('checkbox', { name: 'Enable device protection' })).toBeDisabled();
        await user.clear(screen.getByRole('textbox'));
        await user.type(screen.getByRole('textbox'), 'Renamed');
        await user.click(screen.getByRole('button', { name: 'Save changes' }));
        await waitFor(() => expect(onSave).toHaveBeenCalledExactlyOnceWith({ name: 'Renamed' }));
        expect(screen.getByText('Paused')).toBeInTheDocument();
    });

    it.each([
        ['conflict', 'The device status has changed.'],
        ['validation', 'The changes could not be accepted.'],
    ] as const)('explains a %s response without raw server text', async (code, message) => {
        const user = userEvent.setup();
        render(
            <DeviceDetails
                device={device}
                locale="en"
                onBack={vi.fn()}
                onSave={vi.fn().mockRejectedValue(new ApiError(code))}
            />,
        );
        await user.click(screen.getByRole('button', { name: 'Edit settings' }));
        await user.click(screen.getByRole('checkbox', { name: 'Ads' }));
        await user.click(screen.getByRole('button', { name: 'Save changes' }));
        expect(await screen.findByRole('alert')).toHaveTextContent(message);
    });
});
