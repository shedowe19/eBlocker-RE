// SPDX-License-Identifier: EUPL-1.2
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { expect, it } from 'vitest';
import { agentStatusSchema, wireGuardPlanSchema } from './contracts';

// Produced and checked by the Go HTTP handler, also consumed by the Java bridge.
// Keep one protocol fixture across all three languages instead of approximating it.
function fixture(name: string): unknown {
    return JSON.parse(
        readFileSync(
            resolve(process.cwd(), '../../contracts/network-agent/v1', `${name}.json`),
            'utf8',
        ),
    );
}

it('accepts the Go status contract including an unassigned interface and optional route fields', () => {
    const { data } = agentStatusSchema.parse(fixture('status'));
    expect(data.readOnly).toBe(true);
    expect(data.capabilities.wireguard.management).toBe(false);
    expect(data.interfaces[1].addresses).toEqual([]);
    expect(data.interfaces[0].addresses.map((address) => address.family)).toEqual(['ipv4', 'ipv6']);
    expect(data.routes.map((route) => route.table)).toEqual([100, 1001]);
});

it('accepts the Go WireGuard plan while retaining the validation-only boundary', () => {
    const { data } = wireGuardPlanSchema.parse(fixture('wireguard-plan'));
    expect(data.applied).toBe(false);
    expect(data.killSwitchActive).toBe(false);
    expect(data.peers[0].hasPresharedKey).toBe(true);
    expect(data.peers[1].endpoint).toBeUndefined();
    expect(data.endpointExclusions).toEqual(['198.51.100.1']);
});
