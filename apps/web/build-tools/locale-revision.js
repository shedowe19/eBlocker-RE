'use strict';

const {createHash} = require('crypto');

/** One stable cache revision per module, shared by all of its languages. */
module.exports = function localeRevision(files) {
    const hash = createHash('sha256');
    const sorted = files.slice().sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
    for (const file of sorted) {
        const bytes = Buffer.from(file.content);
        hash.update(JSON.stringify([file.name, bytes.length]));
        hash.update(bytes);
    }
    return hash.digest('hex').slice(0, 16);
};
