'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {Readable, Writable} = require('node:stream');
const {pipeline} = require('node:stream/promises');
const {runInNewContext} = require('node:vm');
const Vinyl = require('vinyl');
const minifyJavaScript = require('./minify-javascript');

async function minify(source) {
    let output;
    await pipeline(
        Readable.from([new Vinyl({path: '/bundle.js', contents: Buffer.from(source)})]),
        minifyJavaScript(),
        new Writable({objectMode: true, write(file, encoding, callback) {
            output = file.contents.toString();
            callback();
        }})
    );
    return output;
}

test('production minification accepts modern dependencies and preserves their behavior and licenses', async () => {
    const source = `/*! Dependency license */
        const {value = 7} = {};
        let selected;
        selected ??= value;
        const read = ({nested} = {}) => nested?.count ?? selected;
        globalThis.result = [read(), read({nested: {count: 3}})];`;
    const output = await minify(source);
    const context = {};
    runInNewContext(output, context);
    assert.equal(JSON.stringify(context.result), '[7,3]');
    assert.match(output, /Dependency license/);
    assert.ok(output.length < source.length);
});

test('invalid production JavaScript fails the build pipeline', async () => {
    await assert.rejects(minify('const invalid = ;'), /Unexpected token/);
});
