'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {createRequire} = require('node:module');
const file = path.resolve(__dirname, '../src/settings/app/_bootstrap/_configs/routeConfig.js');
const requireRoute = createRequire(file);

test('all settings states preserve order, access rules, resolves, URLs and parameters', () => {
    const source = fs.readFileSync(file, 'utf8')
        .replace(/import (\w+) from '([^']+)';/g, 'const $1 = requireRoute("$2");')
        .replace('export default function', 'function');
    const defaults = [];
    const states = [];
    vm.runInNewContext(source + '\nRoutesConfig(urls, provider, names);', {
        requireRoute,
        urls: {otherwise: value => defaults.push(value)},
        provider: {state: value => states.push(value)},
        names: new Proxy({}, {get: (_, key) => String(key)})
    });
    const actual = JSON.parse(JSON.stringify({defaults, states}, (_, value) =>
        typeof value === 'function' ? {$function: value.toString()} : value));
    const expected = JSON.parse(fs.readFileSync(path.join(__dirname, 'contracts/settings-states.json')));
    assert.deepEqual(actual, expected);
});

test('shell resolves retain the shared state array and task visibility binding', async () => {
    const source = fs.readFileSync(file, 'utf8')
        .replace(/import (\w+) from '([^']+)';/g, 'const $1 = requireRoute("$2");')
        .replace('export default function', 'function');
    const states = [];
    vm.runInNewContext(source + '\nRoutesConfig(urls, provider, names);', {
        requireRoute,
        urls: {otherwise() {}},
        provider: {state: value => states.push(value)},
        names: new Proxy({}, {get: (_, key) => String(key)})
    });
    const app = states.find(state => state.name === 'app');
    let published;
    const resolved = app.resolve.states.at(-1)({setStates: value => { published = value; }});
    assert.equal(resolved, published);
    assert.equal(resolved.length, states.length);
    states.forEach((state, index) => assert.equal(resolved[index], state));
    const main = states.find(state => state.resolve && state.resolve.viewConfig);
    const tasks = states.find(state => state.name === 'tasks');
    await main.resolve.viewConfig.at(-1)({getConfig: () => Promise.resolve({enabled: false})});
    assert.equal(tasks.hide, true);
    await main.resolve.viewConfig.at(-1)({getConfig: () => Promise.resolve({enabled: true})});
    assert.equal(tasks.hide, false);
});
