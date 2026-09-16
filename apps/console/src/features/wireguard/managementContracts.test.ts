// SPDX-License-Identifier: EUPL-1.2
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';
import {
    profileDeletedSchema,
    profileResponseSchema,
    profilesResponseSchema,
    profileStatusResponseSchema,
    protectionObserved,
    tunnelObserved,
} from './managementContracts';

function golden(name: string): unknown {
    return JSON.parse(
        readFileSync(
            resolve(process.cwd(), '../../contracts/wireguard-control/v1', `${name}.json`),
            'utf8',
        ),
    );
}
it('projects actual Go control list/import/delete fixtures without inventing runtime observations', () => {
    const list = profilesResponseSchema.parse(golden('profile-list'));
    expect(list.data.profiles[0].profileId).toBe('home');
    expect(list.data.profiles[0]).not.toHaveProperty('runtime');
    expect(profilesResponseSchema.parse(golden('empty-list')).data.profiles).toEqual([]);
    const imported = profileResponseSchema.parse(golden('import-result'));
    expect(imported.data.phase).toBe('imported');
    expect(imported.data.plan.applied).toBe(false);
    expect(profileDeletedSchema.parse(golden('delete-result')).data).toEqual({
        profileId: 'home',
        deleted: true,
    });
});
it('uses the Go runtime observation for protection even though its immutable import plan remains unapplied', () => {
    const { data } = profileStatusResponseSchema.parse(golden('profile-status'));
    expect(data.plan.killSwitchActive).toBe(false);
    expect(data.runtime?.plan.applied).toBe(false);
    expect(tunnelObserved(data.runtime)).toBe(true);
    expect(protectionObserved(data.runtime)).toBe(true);
    expect(data.runtime?.observation.peers[0].lastHandshakeUnix).toBe(1700000000);
    const changed = structuredClone(data);
    changed.runtime!.observation.policy!.digest = 'changed';
    expect(protectionObserved(changed.runtime)).toBe(false);
});
