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
import homeStates from '../../routes/home.states';
import familyStates from '../../routes/family.states';
import devicesStates from '../../routes/devices.states';
import protectionStates from '../../routes/protection.states';
import vpnStates from '../../routes/vpn.states';
import dnsStates from '../../routes/dns.states';
import systemStates from '../../routes/system.states';
import networkStates from '../../routes/network.states';
import shellStates from '../../routes/shell.states';

// Route order is a compatibility contract; feature modules own state definitions.
export default function RoutesConfig($urlRouterProvider, $stateProvider, STATES) {
    'ngInject';
    'use strict';

    $urlRouterProvider.otherwise('/' + STATES.AUTH);
    const allStates = [];
    const system = systemStates(STATES);
    const states = Object.assign({},
        homeStates(STATES),
        familyStates(STATES),
        devicesStates(STATES),
        protectionStates(STATES),
        vpnStates(STATES),
        dnsStates(STATES),
        system,
        networkStates(STATES),
        shellStates(STATES, allStates, system.tasks)
    );
    allStates.push(
        states.home,
        states.homeLicense,
        states.homeUpdate,
        states.adminPassword,
        states.homeAbout,
        states.homeLegal,
        states.parentalControl,
        states.parentalControlState,
        states.devices,
        states.ssl,
        states.sslStatus,
        states.sslCertificate,
        states.sslFails,
        states.trustedApps,
        states.trustedDomains,
        states.ipAnon,
        states.ipAnonState,
        states.system,
        states.network,
        states.networkSettings,
        states.networkSettingsIp6,
        states.networkWizard,
        states.vpnHome,
        states.manualRecording,
        states.users,
        states.blacklists,
        states.whitelists,
        states.tor,
        states.vpnconnect,
        states.dns,
        states.status,
        states.timeAndLanguage,
        states.events,
        states.backup,
        states.reset,
        states.diagnostics,
        states.usersDetails,
        states.blacklistDetails,
        states.whitelistDetails,
        states.devicesState,
        states.devicesDetails,
        states.vpnconnectDetails,
        states.tasks,
        states.trustedAppsDetails,
        states.sslstate,
        states.filter,
        states.filterState,
        states.advancedFilterSettings,
        states.vpnHomeWizard,
        states.devicesList,
        states.devicesDiscovery,
        states.dnsStatus,
        states.dnsLocal,
        states.dnsServer,
        states.dnsState,
        states.filterOverview,
        states.filterAnalysis,
        states.analysisDetails,
        states.defaultState,
        states.filterDetails,
        states.doctor,
        states.splashScreen,
        states.activationFinish,
        states.activation,
        states.updating,
        states.booting,
        states.standBy,
        states.factoryResetScreen,
        states.print,
        states.systemPending,
        states.shutdown,
        states.login,
        states.logout,
        states.resetPassword,
        states.notLicensed,
        states.authentication,
        states.expiredState,
        states.mainState,
        states.appState,
        states.openSourceLicenses,
        states.openSourceLicensesJava,
        states.openSourceLicensesCCpp,
        states.openSourceLicensesJavascript,
        states.openSourceLicensesRuby,
        states.openSourceLicensesDebian
    );

    // Parent parameters would reinitialize the entire application on child transitions.
    allStates.forEach((state) => {
        // initializes params and allows to pass these params within this state
        // to simplify and to avoid errors during development we add these params to all states.

        // Removing main here will cause the resolves not to be called as often as
        // before. This may cause unexpected behavior.
        if (state.name !== STATES.MAIN && state.name !== STATES.PARENT) {
            state.params = {param: null, id: null, origin: null};
        }

        $stateProvider.state(state);
    });
}
