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

    const ssl = {
        name: STATES.HTTPS,
        parent: STATES.MAIN,
        redirectTo: 'sslstate', // auto activate substate
        url: slashOptionUrl + 'https',
        showInNavbar: true,
        iconUrl: '/img/icons/ic_lock_outline_black.svg',
        navbarOrder: 8,
        requiredLicense: function() {
            return 'PRO';
        },
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            sslEnabled: ['SslService', function(SslService) {
                return SslService.getStatus(true).then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            sslCertStatus: ['SslService', function(SslService) {
                return SslService.getSslCertStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            sslSettings: ['SslService', function(SslService) {
                return SslService.getUpdatedSettingsRenewalStatus().then(function success(settings) {
                    return settings;
                }, function error() {
                    return null;
                });
            }],
            caOptions: ['SslService', function(SslService) {
                return SslService.getRootCaOptions().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            sslRecordingEnabled: ['SslService', function(SslService) {
                return SslService.getSslErrorRecordingEnabled().then(function success(response) {
                    return response.data.enabled;
                }, function error() {
                    return null;
                });
            }],
            devices: ['DeviceService', function(DeviceService) {
                return DeviceService.getAll().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }]
        },
        translationKey: 'ADMINCONSOLE.SSL.LABEL',
    };

    const sslstate = {
        name: 'sslstate',
        parent: ssl.name,
        redirectTo: 'sslstatus', // auto activate substate
        requiredLicense: ssl.requiredLicense,
        component: 'sslComponent'
    };

    const sslStatus = {
        name: 'sslstatus',
        url: slashOptionSubState + 'status',
        parent: sslstate.name,
        tabOrder: 1,
        requiredLicense: ssl.requiredLicense,
        translationKey: 'ADMINCONSOLE.SSL_STATUS.LABEL',
        component: 'sslStatusComponent'
    };

    const sslCertificate = {
        name: 'sslcertificate',
        url: slashOptionSubState + 'certificate',
        parent: sslstate.name,
        tabOrder: 2,
        disableWhenNoSsl: true, // to disable tab, when ssl disabled
        requiredLicense: ssl.requiredLicense,
        translationKey: 'ADMINCONSOLE.SSL_CERTIFICATE.LABEL',
        component: 'sslCertificateComponent'
    };

    const sslFails = {
        name: 'sslfails',
        url: slashOptionSubState + 'fails',
        parent: sslstate.name,
        tabOrder: 3,
        disableWhenNoSsl: true,
        showWarningWhenSuggestions: true,
        requiredLicense: ssl.requiredLicense,
        translationKey: 'ADMINCONSOLE.SSL_FAILS.LABEL',
        component: 'sslFailsComponent'
    };

    const trustedApps = {
        name: 'trustedapps',
        url: slashOptionSubState + 'trustedapps/:id',
        parent: sslstate.name,
        tabOrder: 4,
        disableWhenNoSsl: true,
        requiredLicense: ssl.requiredLicense,
        translationKey: 'ADMINCONSOLE.TRUSTED_APPS.LABEL',
        component: 'trustedAppsComponent'
    };

    const trustedAppsDetails = {
        name: 'trustedappsdetails',
        url: slashOptionSubState + 'details',
        parent: ssl.name,
        requiredLicense: ssl.requiredLicense,
        component: 'trustedAppsDetailsComponent'
    };

    const trustedDomains = {
        name: 'trusteddomains',
        url: slashOptionSubState + 'trusteddomains',
        parent: sslstate.name,
        tabOrder: 5,
        disableWhenNoSsl: true,
        requiredLicense: ssl.requiredLicense,
        translationKey: 'ADMINCONSOLE.TRUSTED_DOMAINS.LABEL',
        component: 'trustedDomainsComponent'
    };

    const manualRecording = {
        name: 'manualrecording',
        url: slashOptionSubState + 'manualrecording',
        parent: sslstate.name,
        tabOrder: 6,
        disableWhenNoSsl: true,
        requiredLicense: ssl.requiredLicense,
        translationKey: 'ADMINCONSOLE.MANUAL_RECORDING.LABEL',
        component: 'manualRecordingComponent'
    };

    const filter = {
        name: 'filter',
        parent: STATES.MAIN,
        redirectTo: 'filterstate', // auto activate substate, show tab view
        url: slashOptionUrl + 'filter',
        showInNavbar: true,
        iconUrl: '/img/icons/eblocker-blocked-24px-2.svg',
        navbarOrder: 6,
        requiredLicense: function() {
            return 'PRO';
        },
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            sslEnabled: ['SslService', function(SslService) {
                return SslService.getStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }]
        },
        translationKey: 'ADMINCONSOLE.FILTER.LABEL'
    };

    const filterState = {
        name: 'filterstate',
        parent: filter.name,
        redirectTo: 'filteroverview', // auto activate substate, actual tab
        requiredLicense: filter.requiredLicense,
        component: 'filterComponent'
    };

    const filterOverview = {
        name: 'filteroverview',
        url: slashOptionSubState + 'overview/:id',
        parent: filterState.name,
        tabOrder: 1,
        requiredLicense: filter.requiredLicense,
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            updateStatus: ['UpdateService', function(UpdateService) {
                return UpdateService.getStatus().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
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
            }]
        },
        translationKey: 'ADMINCONSOLE.FILTER_OVERVIEW.LABEL',
        component: 'filterOverviewComponent'
    };

    const filterDetails = {
        name: STATES.FILTER_DETAILS,
        url: slashOptionSubState + 'details',
        parent: filter.name,
        ignoreTab: true,
        requiredLicense: filter.requiredLicense,
        component: 'filterDetailsComponent'
    };

    const advancedFilterSettings = {
        name: 'advancedsettings',
        url: slashOptionSubState + 'advanced',
        parent: filterState.name,
        tabOrder: 2,
        requiredLicense: filter.requiredLicense,
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            captivePortal: ['CaptivePortalService', function(CaptivePortalService) {
                return CaptivePortalService.get().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            compressionMode: ['CompressionService', function(CompressionService) {
                return CompressionService.get().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            doNotTrack: ['DoNotTrackService', function(DoNotTrackService) {
                return DoNotTrackService.get().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            referrer: ['ReferrerService', function(ReferrerService) {
                return ReferrerService.get().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            webRtc: ['WebRtcService', function(WebRtcService) {
                return WebRtcService.get().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }]
        },
        translationKey: 'ADMINCONSOLE.ADVANCED_FILTER_SETTINGS.LABEL',
        component: 'advancedSettingsComponent'
    };

    const filterAnalysis = {
        name: 'filteranalysis',
        url: slashOptionSubState + 'analysis',
        parent: filterState.name,
        tabOrder: 3,
        requiredLicense: filter.requiredLicense,
        translationKey: 'ADMINCONSOLE.FILTER_ANALYSIS.LABEL',
        component: 'analysisComponent'
    };

    const analysisDetails = {
        name: 'analysisdetails',
        url: slashOptionSubState + 'analysis/details',
        parent: filter.name,
        ignoreTab: true,
        requiredLicense: filterAnalysis.requiredLicense,
        component: 'analysisDetailsComponent'
    };

    return {
        ssl,
        sslstate,
        sslStatus,
        sslCertificate,
        sslFails,
        trustedApps,
        trustedAppsDetails,
        trustedDomains,
        manualRecording,
        filter,
        filterState,
        filterOverview,
        filterDetails,
        advancedFilterSettings,
        filterAnalysis,
        analysisDetails
    };
};
