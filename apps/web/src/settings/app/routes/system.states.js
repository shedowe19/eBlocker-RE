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

module.exports = function(STATES) {
    const slashOptionUrl = '';
    const slashOptionSubState = '/';

    const system = {
        name: 'system',
        parent: STATES.MAIN,
        redirectTo: 'timeandlanguage', // auto activate substate
        url: slashOptionUrl + 'system',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_settings.svg',
        navbarOrder: 9,
        requiredLicense: function() {
            return 'WOL';
        },
        translationKey: 'ADMINCONSOLE.SYSTEM.LABEL',
        component: 'systemComponent'
    };

    const adminPassword = {
        name: 'adminpassword',
        url: slashOptionSubState + 'adminpassword',
        parent: system.name,
        tabOrder: 2,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.ADMIN_PASSWORD.LABEL',
        component: 'adminPasswordComponent'
    };

    const diagnostics = {
        name: 'diagnostics',
        url: slashOptionSubState + 'diagnostics',
        parent: system.name,
        tabOrder: 6,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.DIAGNOSTICS.LABEL',
        component: 'diagnosticsComponent'
    };

    const events = {
        name: 'events',
        url: slashOptionSubState + 'events',
        parent: system.name,
        tabOrder: 4,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.EVENTS.LABEL',
        component: 'eventsComponent'
    };

    const backup = {
        name: 'backup',
        url: slashOptionSubState + 'backup',
        parent: system.name,
        tabOrder: 7,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.BACKUP.LABEL',
        component: 'backupComponent'
    };

    const reset = {
        name: 'reset',
        url: slashOptionSubState + 'reset',
        parent: system.name,
        tabOrder: 8,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.RESET.LABEL',
        component: 'resetComponent'
    };

    const status = {
        name: 'status',
        url: slashOptionSubState + 'status',
        parent: system.name,
        tabOrder: 2,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.STATUS.LABEL',
        component: 'statusComponent'
    };

    const tasks = {
        name: 'tasks',
        url: slashOptionSubState + 'tasks',
        parent: system.name,
        tabOrder: 5,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.TASKS.LABEL',
        component: 'tasksComponent',
        hide: true
    };

    const timeAndLanguage = {
        name: 'timeandlanguage',
        url: slashOptionSubState + 'locale',
        parent: system.name,
        tabOrder: 1,
        requiredLicense: system.requiredLicense,
        translationKey: 'ADMINCONSOLE.TIME_LANGUAGE.LABEL',
        component: 'timeLanguageComponent'
    };

    return {
        system,
        adminPassword,
        diagnostics,
        events,
        backup,
        reset,
        status,
        tasks,
        timeAndLanguage
    };
};
