'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {EventEmitter} = require('node:events');
const runKarma = require('./run-karma');

for (const code of [0, 1, 2, 3]) {
    test('propagates Karma exit code ' + code + ' and cleans up the mock server', async () => {
        const originalEnvironment = {...process.env};
        let killed = 0;
        let forkOptions;
        const child = new EventEmitter();
        child.kill = () => killed++;
        const error = await new Promise(resolve => runKarma({
            startServers: true, nodeServer: 'mock-server.js', configFile: 'karma.conf.js', singleRun: true
        }, resolve, {
            fork: (path, args, options) => { forkOptions = options; return child; },
            Server: class {
                constructor(options, callback) { this.callback = callback; }
                start() { this.callback(code); }
            }
        }));
        assert.equal(Boolean(error), code !== 0);
        assert.equal(killed, 1);
        assert.equal(forkOptions.env.PORT, '8888');
        assert.deepEqual({...process.env}, originalEnvironment);
    });
}

test('reports a rejected start once even when Karma also invokes its callback', async () => {
    let completions = 0;
    const failure = new Error('browser startup failed');
    runKarma({}, error => { completions++; assert.equal(error, failure); }, {
        Server: class {
            constructor(options, callback) { this.callback = callback; }
            start() {
                setImmediate(() => this.callback(1));
                return Promise.reject(failure);
            }
        }
    });
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(completions, 1);
});

test('cleans up when constructing Karma fails', () => {
    let killed = 0;
    const child = new EventEmitter();
    child.kill = () => killed++;
    const failure = new Error('invalid configuration');
    let result;
    runKarma({startServers: true}, error => { result = error; }, {
        fork: () => child,
        Server: class { constructor() { throw failure; } }
    });
    assert.equal(result, failure);
    assert.equal(killed, 1);
});
