'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const revision = require('./locale-revision');

test('locale revisions are independent of filesystem order', () => {
    const files = [{name: 'en.json', content: '{"hello":"Hello"}'}, {name: 'de.json', content: '{"hello":"Hallo"}'}];
    assert.equal(revision(files), revision(files.slice().reverse()));
    assert.deepEqual(files.map(file => file.name), ['en.json', 'de.json']);
});

test('changing either language invalidates the shared module revision', () => {
    const before = [{name: 'en.json', content: 'Hello'}, {name: 'de.json', content: 'Hallo'}];
    assert.notEqual(revision(before), revision([before[0], {name: 'de.json', content: 'Guten Tag'}]));
    assert.notEqual(revision(before), revision([{name: 'en.json', content: 'Hi'}, before[1]]));
});

test('filename and content boundaries are unambiguous', () => {
    assert.notEqual(revision([{name: 'a', content: 'bc'}]), revision([{name: 'ab', content: 'c'}]));
});
