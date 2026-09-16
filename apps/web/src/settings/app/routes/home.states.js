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

    const defaultState = {
        name: STATES.DEFAULT,
        parent: STATES.MAIN,
        requiredLicense: function() {
            return 'WOL';
        }
    };

    const home = {
        name: STATES.HOME,
        parent: STATES.MAIN,
        redirectTo: 'license', // auto activate substate
        url: slashOptionUrl + 'home',
        showInNavbar: true,
        iconUrl: '/img/icons/eblocker.svg',
        navbarOrder: 1,
        requiredLicense: function() {
            return 'WOL';
        },
        translationKey: 'ADMINCONSOLE.HOME.LABEL',
        component: 'homeComponent'
    };

    const homeLicense = {
        name: 'license',
        url: slashOptionSubState + 'license',
        parent: STATES.HOME,
        tabOrder: 1,
        requiredLicense: function() {
            return 'WOL';
        },
        translationKey: 'ADMINCONSOLE.LICENSE.LABEL',
        component: 'licenseComponent'
    };

    const homeUpdate = {
        name: 'update',
        url: slashOptionSubState + 'update',
        parent: STATES.HOME,
        tabOrder: 2,
        requiredLicense: function() {
            return 'WOL';
        },
        translationKey: 'ADMINCONSOLE.UPDATE.LABEL',
        component: 'updateComponent'
    };

    const homeAbout = {
        name: 'about',
        url: slashOptionSubState + 'about',
        parent: STATES.HOME,
        tabOrder: 3,
        requiredLicense: function() {
            return 'WOL';
        },
        translationKey: 'ADMINCONSOLE.ABOUT.LABEL',
        component: 'aboutComponent'
    };

    const homeLegal = {
        name: 'legal',
        url: slashOptionSubState + 'legal',
        parent: STATES.HOME,
        tabOrder: 4,
        requiredLicense: function() {
            return 'WOL';
        },
        translationKey: 'ADMINCONSOLE.LEGAL.LABEL',
        component: 'legalComponent'
    };

    return {
        defaultState,
        home,
        homeLicense,
        homeUpdate,
        homeAbout,
        homeLegal
    };
};
