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

    const network = {
        name: 'network',
        parent: STATES.MAIN,
        redirectTo: 'networksettings', // auto activate substate
        url: slashOptionUrl + 'network',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_settings_ethernet_black.svg',
        navbarOrder: 10,
        requiredLicense: function() {
            return 'WOL';
        },
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            configuration: ['NetworkService', function(NetworkService) {
                return NetworkService.getNetworkConfig().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            configurationIp6: ['NetworkService', function(NetworkService) {
                return NetworkService.getNetworkIp6Config().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            dnsEnabled: ['DnsService', function(DnsService) {
                return DnsService.loadDnsStatus(true).then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }]
        },
        translationKey: 'ADMINCONSOLE.NETWORK_SETTINGS.LABEL',
        //component: 'networkSettingsComponent'
        component: 'networkComponent'
    };

    const networkSettings = {
        name: 'networksettings',
        url: slashOptionSubState + 'networksettings',
        parent: network.name,
        tabOrder: 1,
        requiredLicense: network.requiredLicense,
        translationKey: 'ADMINCONSOLE.NETWORK_SETTINGS.LABEL',
        component: 'networkSettingsComponent'
    };

    const networkSettingsIp6 = {
        name: 'networksettingsip6',
        url: slashOptionSubState + 'networksettingsip6',
        parent: network.name,
        tabOrder: 2,
        requiredLicense: network.requiredLicense,
        translationKey: 'ADMINCONSOLE.NETWORK_SETTINGS_IP6.LABEL',
        component: 'networkSettingsIp6Component'
    };

    const networkWizard = {
        name: STATES.NETWORK_WIZARD,
        parent: STATES.PARENT,
        url: 'network/assistent', // must be equal to state name for now
        requiredLicense: network.requiredLicense,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.NETWORK_WIZARD.TOOLBAR.TITLE',
        component: 'networkWizardComponent'
    };

    const doctor = {
        name: STATES.DOCTOR,
        parent: STATES.MAIN,
        url: slashOptionUrl + 'doctor',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_doctor.svg',
        navbarOrder: 11,
        requiredLicense: function() {
            return 'BAS';
        },
        translationKey: 'ADMINCONSOLE.DOCTOR.LABEL',
        component: 'doctorDiagnosisComponent'
    };

    return {
        network,
        networkSettings,
        networkSettingsIp6,
        networkWizard,
        doctor
    };
};
