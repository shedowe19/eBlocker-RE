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

    const parentalControl = {
        name: 'parentalcontrol',
        parent: STATES.MAIN,
        redirectTo: 'parentalcontrolstate', // auto activate substate
        url: slashOptionUrl + 'parentalcontrol',
        showInNavbar: true,
        iconUrl: '/img/icons/icons8-teddy-bear.svg',
        navbarOrder: 3,
        requiredLicense: function() {
            return 'FAM';
        },
        // ** This should allow to use different names in resolve and component-bindings:
        // See: https://ui-router.github.io/guide/ng1/route-to-component
        // bindings: { profiles: 'userProfiles'}
        // asynch = wait: we need to wait before the promise is resolved or onSuccess in transition hook is called
        // right away disabling the spinner.
        // when = lazy: we want to wait until the tansition has started, or else onBefore is not called until the data
        // is loaded (thus not enabling the spinner).
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
            devices: ['DeviceService', function(DeviceService) {
                return DeviceService.getAll().then(function success(response) {
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
            }]
        },
        translationKey: 'ADMINCONSOLE.PARENTAL_CONTROL.LABEL'
    };

    const parentalControlState = {
        name: 'parentalcontrolstate',
        parent: parentalControl.name,
        redirectTo: 'users', // auto activate substate
        requiredLicense: parentalControl.requiredLicense,
        component: 'parentalControlComponent'
    };

    const users = {
        name: 'users',
        url: slashOptionSubState + 'users/:id', //:id defines that the URL may contain a param 'id'
        parent: parentalControlState.name,
        tabOrder: 1,
        requiredLicense: parentalControl.requiredLicense,
        translationKey: 'ADMINCONSOLE.USERS.LABEL',
        component: 'usersComponent'
    };

    const usersDetails = {
        name: STATES.USER_DETAILS,
        url: slashOptionSubState + 'users/details',
        parent: parentalControl.name,
        ignoreTab: true,
        requiredLicense: parentalControl.requiredLicense,
        component: 'usersDetailsComponent'
    };

    const blacklists = {
        name: 'blacklists',
        url: slashOptionSubState + 'blacklists/:id',
        parent: parentalControlState.name,
        tabOrder: 3,
        requiredLicense: parentalControl.requiredLicense,
        translationKey: 'ADMINCONSOLE.BLACKLISTS.LABEL',
        component: 'blacklistsComponent'
    };

    const whitelists = {
        name: 'whitelists',
        url: slashOptionSubState + 'whitelists/:id',
        parent: parentalControlState.name,
        tabOrder: 4,
        requiredLicense: parentalControl.requiredLicense,
        translationKey: 'ADMINCONSOLE.WHITELISTS.LABEL',
        component: 'whitelistsComponent'
    };

    const blacklistDetails = {
        name: 'blacklistdetails',
        url: slashOptionSubState + 'blacklists/details',
        parent: parentalControl.name,
        ignoreTab: true,
        requiredLicense: parentalControl.requiredLicense,
        component: 'blacklistDetailsComponent'
    };

    const whitelistDetails = {
        name: 'whitelistdetails',
        url: slashOptionSubState + 'whitelists/details',
        parent: parentalControl.name,
        ignoreTab: true,
        requiredLicense: parentalControl.requiredLicense,
        component: 'whitelistDetailsComponent'
    };

    return {
        parentalControl,
        parentalControlState,
        users,
        usersDetails,
        blacklists,
        whitelists,
        blacklistDetails,
        whitelistDetails
    };
};
