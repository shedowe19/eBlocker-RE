'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const {once} = require('node:events');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const createApp = require('../src/server/app');

test('Express 5 serves assets, SPA links, mock API and missing routes', async t => {
    const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'eblocker-web-'));
    t.after(() => fs.rm(directory, {recursive: true, force: true}));
    await fs.writeFile(path.join(directory, 'index.html'), '<title>eBlocker</title>');
    await fs.writeFile(path.join(directory, 'test.css'), 'body {color: red}');
    const server = createApp({environment: 'build', buildDirectory: directory}).listen(0, '127.0.0.1');
    t.after(() => new Promise(resolve => server.close(resolve)));
    await once(server, 'listening');
    const base = 'http://127.0.0.1:' + server.address().port;
    assert.equal(await (await fetch(base + '/settings/network')).text(), '<title>eBlocker</title>');
    assert.equal((await fetch(base + '/test.css')).status, 200);
    assert.equal((await fetch(base + '/api/unknown')).status, 404);
    assert.equal((await fetch(base + '/app/missing.html')).status, 404);
    assert.equal((await fetch(base + '/api/localtimestamp')).status, 200);
});

test('API redirection requires an explicit target', () => {
    assert.throws(() => createApp({redirectRestApi: true}), /REST_API/);
});
