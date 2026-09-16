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

    const devices = {
        name: 'devices',
        parent: STATES.MAIN,
        url: slashOptionUrl + 'devices',
        redirectTo: 'devicesstate',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_devices_black.svg',
        navbarOrder: 2,
        separator: true,
        requiredLicense: function() {
            return 'WOL';
        },
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            users: ['UserService', function(UserService) {
                return UserService.getAll().then(function success(response) {
                    return response.data;
                }, function error() {
                    return [];
                });
            }],
            profiles: ['UserProfileService', function(UserProfileService) {
                return UserProfileService.getAll().then(function success(response) {
                    return response.data;
                }, function error() {
                    return [];
                });
            }],
            dnsEnabled: ['DnsService', function(DnsService) {
                return DnsService.loadDnsStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            sslEnabled: ['SslService', function(SslService) {
                return SslService.getStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            vpnHomeStatus: ['VpnHomeService', function(VpnHomeService) {
                return VpnHomeService.loadStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            vpnHomeCertificates: ['VpnHomeService', 'vpnHomeStatus', function(VpnHomeService, vpnHomeStatus) {
                if (vpnHomeStatus.isRunning) {
                    return VpnHomeService.loadCertificates().then(function success(response) {
                        return response.data;
                    }, function error() {
                        return null;
                    });
                } else {
                    return [];
                }
            }]
        },
        translationKey: 'ADMINCONSOLE.DEVICES.LABEL'
        // template: '<div ui-view></div>'
        // component: 'devicesComponent'
    };

    const devicesState = {
        name: 'devicesstate',
        parent: devices.name,
        redirectTo: 'deviceslist', // auto activate substate
        requiredLicense: devices.requiredLicense,
        component: 'devicesComponent'
    };

    const devicesList = {
        name: 'deviceslist',
        url: slashOptionSubState + 'list/:id',
        parent: devicesState.name,
        tabOrder: 1,
        requiredLicense: devices.requiredLicense,
        translationKey: 'ADMINCONSOLE.DEVICES_LIST.LABEL',
        component: 'devicesListComponent'
    };

    const devicesDiscovery = {
        name: 'devicesdiscovery',
        url: slashOptionSubState + 'discovery',
        parent: devicesState.name,
        tabOrder: 2,
        requiredLicense: devices.requiredLicense,
        translationKey: 'ADMINCONSOLE.DEVICES_DISCOVERY.LABEL',
        component: 'devicesDiscoveryComponent'
    };

    const devicesDetails = {
        name: 'devicedetails',
        url: slashOptionSubState + 'details',
        parent: devices.name,
        requiredLicense: devices.requiredLicense,
        component: 'devicesDetailsComponent'
    };

    return {
        devices,
        devicesState,
        devicesList,
        devicesDiscovery,
        devicesDetails
    };
};
