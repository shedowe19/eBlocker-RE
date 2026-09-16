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

    const dns = {
        name: 'dns',
        parent: STATES.MAIN,
        redirectTo: 'dnsstate', // auto activate substate
        url: slashOptionUrl + 'dns',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_dns_black.svg',
        navbarOrder: 7,
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            dnsEnabled: ['DnsService', function(DnsService) {
                return DnsService.loadDnsStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            configuration: ['DnsService', function(DnsService) {
                return DnsService.loadDnsConfiguration().then(function success(configuration) {
                    return configuration;
                }, function error() {
                    return null;
                });
            }]
        },
        requiredLicense: function() {
            return 'BAS';
        },
        translationKey: 'ADMINCONSOLE.DNS.LABEL'
    };

    const dnsState = {
        name: 'dnsstate',
        parent: dns.name,
        redirectTo: 'dnsstatus', // auto activate substate
        requiredLicense: dns.requiredLicense,
        component: 'dnsComponent'
    };

    const dnsStatus = {
        name: 'dnsstatus',
        url: slashOptionSubState + 'status',
        parent: dnsState.name,
        tabOrder: 1,
        requiredLicense: dns.requiredLicense,
        translationKey: 'ADMINCONSOLE.DNS_STATUS.LABEL',
        component: 'dnsStatusComponent'
    };

    const dnsServer = {
        name: 'dnsserver',
        url: slashOptionSubState + 'server',
        parent: dnsState.name,
        disabledWhenNoDns: true,
        disabledWhenNotCustom: true,
        tabOrder: 2,
        requiredLicense: dns.requiredLicense,
        translationKey: 'ADMINCONSOLE.DNS_SERVER.LABEL',
        component: 'dnsServerComponent'
    };

    const dnsLocal = {
        name: 'dnslocal',
        url: slashOptionSubState + 'local',
        parent: dnsState.name,
        disabledWhenNoDns: true,
        tabOrder: 3,
        requiredLicense: dns.requiredLicense,
        translationKey: 'ADMINCONSOLE.DNS_LOCAL.LABEL',
        component: 'dnsLocalComponent'
    };

    return {
        dns,
        dnsState,
        dnsStatus,
        dnsServer,
        dnsLocal
    };
};
