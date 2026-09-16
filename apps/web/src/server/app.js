/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL
 * (the "License"); You may not use this work except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://joinup.ec.europa.eu/page/eupl-text-11-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
/*jshint node:true*/
'use strict';

const express = require('express');
const path = require('node:path');
const favicon = require('serve-favicon');
const logger = require('morgan');
const four0four = require('./utils/404')();

function createApp(options = {}) {
    const app = express();
    const environment = options.environment || process.env.NODE_ENV || 'dev';
    const buildDirectory = options.buildDirectory || path.resolve(__dirname, '../../build');
    const redirectRestApi = options.redirectRestApi === undefined ?
        process.env.REDIRECT_REST_API === 'true' : options.redirectRestApi;
    const restApi = options.restApi || process.env.REST_API;

    app.use(favicon(path.join(__dirname, 'favicon.ico')));
    app.use(express.urlencoded({extended: true}));
    app.use(express.json());
    app.use(logger('dev'));
    if (redirectRestApi) {
        if (!restApi) {
            throw new Error('REST_API is required when API redirection is enabled');
        }
        app.use('/api', function(request, response) {
            response.redirect(301, restApi + request.originalUrl);
        });
    } else {
        app.use('/api', require('./routes'));
    }

    app.use(express.static(buildDirectory));
    app.use('/app/{*path}', four0four.notFoundMiddleware);
    if (environment === 'build') {
        app.get('/{*path}', function(request, response) {
            response.sendFile(path.join(buildDirectory, 'index.html'));
        });
    } else {
        app.use(four0four.notFoundMiddleware);
    }
    return app;
}

if (require.main === module) {
    const port = process.env.PORT || 8001;
    const host = process.env.HOST || '127.0.0.1';
    createApp().listen(port, host, function() {
        console.log('eBlocker development server listening on http://' + host + ':' + port);
    });
}

module.exports = createApp;
