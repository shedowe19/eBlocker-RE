'use strict';

const terser = require('gulp-terser');

// Dependencies may contain modern JavaScript even when our application sources
// pass through Babel. Keep the parser current and preserve license notices.
module.exports = function minifyJavaScript() {
    return terser({format: {comments: /^!|@preserve|@license|@cc_on/i}});
};
