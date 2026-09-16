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

    const ipAnon = {
        name: 'anonymization',
        parent: STATES.MAIN,
        redirectTo: 'anonymizationstate', // auto activate substate
        url: slashOptionUrl + 'anonymization',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_security.svg',
        navbarOrder: 4,
        requiredLicense: function() {
            return 'BAS';
        },
        translationKey: 'ADMINCONSOLE.IP_ANON.LABEL'
    };

    const ipAnonState = {
        name: 'anonymizationstate',
        parent: ipAnon.name,
        redirectTo: 'tor', // auto activate substate
        requiredLicense: ipAnon.requiredLicense,
        component: 'ipAnonComponent'
    };

    const tor = {
        name: 'tor',
        url: slashOptionSubState + 'tor',
        parent: ipAnonState.name,
        tabOrder: 1,
        requiredLicense: ipAnonState.requiredLicense,
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            torCountries: ['TorService', function(TorService) {
                return TorService.getAllTorCountries().then(function success(response) {
                    return response.data;
                }, function error() {
                    return [];
                });
            }],
            selectedTorCountries: ['TorService', function(TorService) {
                return TorService.getSelectedTorExitNodes().then(function success(response) {
                    return response.data;
                }, function error() {
                    return [];
                });
            }]
        },
        translationKey: 'ADMINCONSOLE.TOR.LABEL',
        component: 'torComponent'
    };

    const vpnconnect = {
        name: 'vpnconnect',
        url: slashOptionSubState + 'vpn/:id',
        parent: ipAnonState.name,
        tabOrder: 2,
        requiredLicense: ipAnonState.requiredLicense,
        translationKey: 'ADMINCONSOLE.VPN_CONNECT.LABEL',
        component: 'vpnConnectComponent'
    };

    const vpnconnectDetails = {
        name: 'vpnconnectdetails',
        url: slashOptionSubState + 'vpn/details',
        parent: ipAnon.name,
        requiredLicense: ipAnon.requiredLicense,
        component: 'vpnConnectDetailsComponent'
    };

    const vpnHome = {
        name: 'mobile',
        url: slashOptionUrl + 'mobile',
        parent: STATES.MAIN,
        showInNavbar: true,
        iconUrl: '/img/icons/ic_smartphone_black.svg',
        navbarOrder: 5,
        requiredLicense: function() {
            return 'BAS';
        },
        translationKey: 'ADMINCONSOLE.VPN_HOME.LABEL',
        component: 'vpnHomeStatusComponent'
    };

    const vpnHomeWizard = {
        name: STATES.VPN_HOME_WIZARD,
        parent: STATES.PARENT,
        url: 'mobile/assistent', // must be equal to state name for now
        requiredLicense: vpnHome.requiredLicense,
        allowActive: true,
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            vpnHomeStatus: ['VpnHomeService', function (VpnHomeService) {
                return VpnHomeService.loadStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            security: 'security',
            token: ['security', function(security) {
                // ** security service should already be initialized
                return security.getToken();
            }],
            registrationInfo: ['token', 'RegistrationService', function(token, RegistrationService) {
                // 'token' needed only indirectly for the REST call, so we wait until
                // the token has been loaded (from storage or server). Afterwards the security
                // service will set the default bearer header, allowing to make this call.
                // We need to load the registration data, because wizard is out-of-app. If user reloads
                // the browser, the app will try to re-enter wizard, w/o loading registration info, because that is
                // done in main state. But wizard, as said, is out-of-app and not child of main state.
                return RegistrationService.loadRegistrationInfo().then(function success(response) {
                    return response;
                }, function error() {
                    return null;
                });
            }]
        },
        translationKey: 'ADMINCONSOLE.VPN_HOME_WIZARD.TOOLBAR.TITLE',
        component: 'vpnHomeWizardComponent'
    };

    return {
        ipAnon,
        ipAnonState,
        tor,
        vpnconnect,
        vpnconnectDetails,
        vpnHome,
        vpnHomeWizard
    };
};
