'use strict';

/** Run Karma and its optional mock server as one lifecycle. */
module.exports = function runKarma(options, done, dependencies) {
    const {Server, fork} = dependencies || {
        Server: require('karma').Server,
        fork: require('child_process').fork
    };
    let child;
    let completed = false;

    function finish(error) {
        if (completed) {
            return;
        }
        completed = true;
        if (child) {
            child.kill();
        }
        done(error);
    }

    try {
        if (options.startServers) {
            child = fork(options.nodeServer, [], {
                env: {...process.env, NODE_ENV: 'dev', PORT: '8888'}
            });
            child.once('error', finish);
        }
        const karma = new Server({
            configFile: options.configFile,
            singleRun: options.singleRun
        }, code => finish(code === 0 ? undefined : new Error('Karma exited with code ' + code)));
        Promise.resolve(karma.start()).catch(finish);
    } catch (error) {
        finish(error);
    }
};
