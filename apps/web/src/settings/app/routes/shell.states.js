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

module.exports = function(STATES, allStates, tasks) {
    const slashOptionUrl = '';
    const slashOptionSubState = '/';

    const notLicensed = {
        name: 'nolicense',
        parent: STATES.MAIN,
        requiredLicense: function() {
            return 'WOL';
        },
        component: 'notLicensedComponent'
    };

    const systemPending = {
        name: 'systempending',
        parent: STATES.PARENT,
        // abstract: true,
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.STAND_BY.TOOLBAR.TITLE',
        component: 'systemPendingComponent'
    };

    const print = {
        name: 'print',
        parent: STATES.PARENT,
        url: 'print',
        ignoreHook: true, // should always be possible
        translationKey: 'ADMINCONSOLE.PRINT.TOOLBAR.TITLE',
        component: 'printComponent'
    };

    const standBy = {
        name: 'standby',
        parent: systemPending.name,
        url: 'standby?origin',
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.STAND_BY.TOOLBAR.TITLE',
        component: 'standByComponent'
    };

    const factoryResetScreen = {
        name: 'factoryResetScreen',
        parent: systemPending.name,
        url: 'factoryreset',
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.FACTORY_RESET_SCREEN.TOOLBAR.TITLE',
        component: 'factoryResetScreenComponent'
    };

    const booting = {
        name: 'booting',
        parent: systemPending.name,
        url: 'booting',
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.BOOTING.TOOLBAR.TITLE',
        component: 'bootingComponent'
    };

    const updating = {
        name: 'updating',
        parent: systemPending.name,
        url: 'updating',
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.UPDATING.TOOLBAR.TITLE',
        component: 'updatingComponent'
    };

    const shutdown = {
        name: 'shutdown',
        parent: systemPending.name,
        url: 'shutdown',
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.SHUTDOWN.TOOLBAR.TITLE',
        component: 'shutdownComponent'
    };

    const appState = {
        name: 'app',
        url: '/',
        component: 'settingsComponent',
        ignoreHook: true, // parent state: should always be possible
        resolvePolicy: { async: 'WAIT', when: 'EAGER' },
        resolve: {
            /*
             * to avoid flash of untranslated content: returns a call to $translate.onReady, effectively blocking the
             * rendering of the app until the first translation file is loaded:
             */
            translateReady: ['$translate', function($translate) {
                return $translate.onReady();
            }],
            states: ['StateService', function(StateService) {
                StateService.setStates(allStates);
                return allStates;
            }],
            settings: 'settings',
            locale: ['settings', function(settings) {
                return settings.load().then(function s(r) {
                    return r;
                }, function e() {
                    return settings.getDefaultLocale();
                });
            }],
            consoleUrl: ['ConsoleService', function(ConsoleService) {
                return ConsoleService.init().then(function success(response) {
                    return response;
                }, function error() {
                    return null;
                });
            }],
            systemStatus: ['SystemService', function(SystemService) {
                return SystemService.loadSystemStatus();
            }]
        }
    };

    const activation = {
        name: 'activation',
        parent: STATES.PARENT,
        resolvePolicy: { async: 'WAIT', when: 'LAZY' },
        resolve: {
            setupWizardInfo: ['SetupService', function(SetupService) {
                return SetupService.getInfo().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            regions: ['TimezoneService', function(TimezoneService) {
                return TimezoneService.getRegions().then(function success(response) {
                    return response.data;
                }, function error() {
                    return [];
                });
            }],
        },
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.ACTIVATION.TOOLBAR.TITLE',
        component: 'activationComponent'
    };

    const activationFinish = {
        name: 'activationfinish',
        parent: STATES.PARENT,
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.ACTIVATION_FINISH.TOOLBAR.TITLE',
        component: 'activationFinishComponent'
    };

    const login = {
        name: 'login',
        parent: STATES.PARENT,
        url: 'login',
        allowActive: true,
        ignoreHook: true,
        translationKey: 'ADMINCONSOLE.LOGIN.TOOLBAR.TITLE',
        component: 'loginComponent'
    };

    const logout = {
        name: 'logout',
        parent: STATES.PARENT,
        ignoreHook: true, // logout should always be possible
        component: 'logoutComponent'
    };

    const resetPassword = {
        name: 'resetpassword',
        parent: STATES.PARENT,
        url: 'resetpassword',
        allowActive: true,
        ignoreHook: true, // reset PW must always be available
        component: 'resetPasswordComponent'
    };

    const authentication = {
        name: 'auth',
        parent: STATES.PARENT,
        ignoreHook: true,
        url: 'auth',
        component: 'authComponent'
    };

    const splashScreen = {
        name: STATES.SPLASH,
        parent: STATES.PARENT,
        ignoreHook: true, // should always be possible
        allowActive: true,
        translationKey: 'ADMINCONSOLE.SPLASH_SCREEN.TOOLBAR.TITLE',
        component: 'splashScreenComponent'
    };

    const mainState = {
        name: STATES.MAIN,
        parent: STATES.PARENT,
        abstract: true,
        component: 'mainComponent',
        // MUST be eager, so that e.g. registrationInfo is present in transition hook
        resolvePolicy: { async: 'WAIT', when: 'EAGER' },
        resolve: {
            featureToggleIp6: ['FeatureToggleService', function(FeatureToggleService) {
                return FeatureToggleService.isFeatureEnabled('ip6').then(function success(response) {
                    return response.data;
                }, function error() {
                    return false;
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
                return RegistrationService.loadRegistrationInfo().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            postRegistrationInformation: ['CustomerInfoService', function(CustomerInfoService) {
                return CustomerInfoService.getCustomerInfo().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            setupInfo: ['SetupService', function(SetupService) {
                return SetupService.getInfo().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            configSections: ['StateService', function(StateService) {
                return StateService.getStates().filter(function(state) {
                    return state.showInNavbar;
                }, function error() {
                    return null;
                });
            }],
            showSplashScreen: ['SplashService', function(SplashService) {
                return SplashService.get().then(function success(response) {
                    return response.data;
                }, function error() {
                    return null;
                });
            }],
            viewConfig: ['TasksService', function(TasksService) {
                // decide whether to show task tab or not. Must be done here, so that we can go to task-state by URL.
                // Otherwise hide property (default is false) will prevent traversing to task state until system state
                // is loaded and updates the property.
                return TasksService.getConfig().then(function success(config) {
                    tasks.hide = !config.enabled;
                    return config;
                }, function error() {
                    return {};
                });
            }]
        }
    };

    const expiredState = {
        name: 'expired',
        parent: STATES.PARENT,
        translationKey: 'ADMINCONSOLE.EXPIRED.TOOLBAR.TITLE',
        ignoreHook: true,
        component: 'expiredComponent'
    };

    const openSourceLicenses = {
        name: STATES.OPEN_SOURCE_LICENSES,
        parent: STATES.PARENT,
        url: 'licenses', // must be equal to state name for now
        requiredLicense: function() {
            return 'BAS';
        },
        ignoreHook: true,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.OPEN_SOURCE_LICENSES.TOOLBAR.TITLE',
        component: 'openSourceLicensesComponent'
    };

    const openSourceLicensesJava = {
        name: STATES.OPEN_SOURCE_LICENSES_JAVA,
        parent: STATES.PARENT,
        url: 'licenses/java',
        requiredLicense: function() {
            return 'BAS';
        },
        ignoreHook: true,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.OPEN_SOURCE_LICENSES.TOOLBAR.TITLE',
        component: 'libsJavaComponent'
    };

    const openSourceLicensesCCpp = {
        name: STATES.OPEN_SOURCE_LICENSES_CCPP,
        parent: STATES.PARENT,
        url: 'licenses/ccpp',
        requiredLicense: function() {
            return 'BAS';
        },
        ignoreHook: true,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.OPEN_SOURCE_LICENSES.TOOLBAR.TITLE',
        component: 'libsCCppComponent'
    };

    const openSourceLicensesJavascript = {
        name: STATES.OPEN_SOURCE_LICENSES_JAVASCRIPT,
        parent: STATES.PARENT,
        url: 'licenses/javascript',
        requiredLicense: function() {
            return 'BAS';
        },
        ignoreHook: true,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.OPEN_SOURCE_LICENSES.TOOLBAR.TITLE',
        component: 'libsJavascriptComponent'
    };

    const openSourceLicensesRuby = {
        name: STATES.OPEN_SOURCE_LICENSES_RUBY,
        parent: STATES.PARENT,
        url: 'licenses/ruby',
        requiredLicense: function() {
            return 'BAS';
        },
        ignoreHook: true,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.OPEN_SOURCE_LICENSES.TOOLBAR.TITLE',
        component: 'libsRubyComponent'
    };

    const openSourceLicensesDebian = {
        name: STATES.OPEN_SOURCE_LICENSES_DEBIAN,
        parent: STATES.PARENT,
        url: 'licenses/debian',
        requiredLicense: function() {
            return 'BAS';
        },
        ignoreHook: true,
        allowActive: true,
        translationKey: 'ADMINCONSOLE.OPEN_SOURCE_LICENSES.TOOLBAR.TITLE',
        component: 'libsDebianComponent'
    };

    return {
        notLicensed,
        systemPending,
        print,
        standBy,
        factoryResetScreen,
        booting,
        updating,
        shutdown,
        appState,
        activation,
        activationFinish,
        login,
        logout,
        resetPassword,
        authentication,
        splashScreen,
        mainState,
        expiredState,
        openSourceLicenses,
        openSourceLicensesJava,
        openSourceLicensesCCpp,
        openSourceLicensesJavascript,
        openSourceLicensesRuby,
        openSourceLicensesDebian
    };
};
