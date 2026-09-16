// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';
const de = {
    edit4: 'IPv4 und DHCP konfigurieren',
    edit6: 'IPv6 konfigurieren',
    close: 'Schließen',
    cancel: 'Abbrechen',
    save: 'Änderung prüfen',
    apply: 'Netzwerkänderung bestätigen',
    saving: 'Änderung wird angefordert …',
    configuration: 'IPv4- und DHCP-Einstellungen',
    mode: 'Betriebsart',
    auto: 'Automatisch (Plug & Play)',
    individual: 'Individuell (eBlocker als DHCP-Server)',
    expert: 'Expertenmodus',
    autoInfo:
        'Im automatischen Modus bezieht eBlocker seine Adresse per DHCP vom Router. Aktiviere und prüfe dessen DHCP-Dienst, bevor du umstellst.',
    individualInfo:
        'eBlocker übernimmt DHCP. Stelle zuerst diese Konfiguration um und starte gegebenenfalls neu. Deaktiviere anschließend den DHCP-Dienst des Routers und verbinde deine Geräte erneut. Zwei dauerhaft aktive DHCP-Server können Verbindungsprobleme verursachen.',
    externalInfo:
        'Trage im externen DHCP-Server die eBlocker-IP als Gateway ein. Bei aktivem eBlocker-DNS ist sie auch der DNS-Server. Diese Routeränderungen führt eBlocker nicht aus.',
    ip: 'eBlocker IPv4-Adresse',
    mask: 'Netzmaske',
    gateway: 'Router / Gateway',
    first: 'Erste DHCP-Adresse',
    last: 'Letzte DHCP-Adresse',
    lease: 'DHCP-Leasezeit in Sekunden',
    fixed: 'Geräteadressen standardmäßig fest zuordnen',
    dhcp: 'DHCP-Server auf eBlocker aktivieren',
    primary: 'Primärer Namensserver',
    secondary: 'Sekundärer Namensserver (optional)',
    discovery: 'DHCP-Server suchen',
    scanning: 'DHCP-Suche läuft …',
    scanNone:
        'Keine DHCP-Antwort empfangen. Das beweist nicht, dass kein anderer DHCP-Server aktiv ist.',
    scanFound: 'Antwortende DHCP-Server',
    dhcpActual: 'Gemeldeter DHCP-Dienst auf eBlocker',
    yes: 'Aktiv',
    no: 'Inaktiv',
    autoConfirmed:
        'Ich habe einen anderen aktiven DHCP-Server für den automatischen Modus geprüft.',
    confirmTitle: 'Netzwerkverbindung ändern?',
    confirmInfo:
        'Die Änderung kann sofort Netzwerkverbindungen unterbrechen. Notiere die bisherige und die neue Adresse. Eine Router- oder DHCP-Anpassung muss gegebenenfalls separat erfolgen. Es wird nicht automatisch neu gestartet.',
    stale: 'Die Konfiguration wurde zwischenzeitlich geändert. Lade sie neu und prüfe deine Eingaben erneut.',
    reload: 'Konfiguration neu laden',
    saved: 'Die gespeicherte Konfiguration wurde neu gelesen und bestätigt.',
    accepted:
        'Der eBlocker hat die Konfiguration angenommen. Ein Neustart ist erforderlich; die Übersicht zeigt bis dahin die aktuell aktiven Adressen.',
    uncertain:
        'Die Änderung konnte nicht bestätigt werden und kann bereits wirksam sein. Prüfe die Verbindung und den aktuellen Zustand vor weiteren Änderungen.',
    address: 'Prüfe IPv4-Adresse, Gateway und die optionalen Namensserver.',
    maskInvalid: 'Die Netzmaske muss eine gültige zusammenhängende IPv4-Netzmaske sein.',
    subnet: 'eBlocker, Gateway und der DHCP-Bereich müssen im gleichen Netz liegen.',
    range: 'Gib einen gültigen DHCP-Bereich mit aufsteigenden IPv4-Adressen ein.',
    unchanged: 'Keine Einstellung geändert.',
    restart: 'eBlocker neu starten',
    restartTitle: 'Neustart ausdrücklich bestätigen',
    restartInfo:
        'Der Neustart unterbricht Netzwerk und Verwaltung. Rufe den eBlocker danach unter seiner neuen Adresse auf. Erneutes Anmelden kann nötig sein.',
    restartConfirm: 'Jetzt neu starten',
    restarting: 'Neustart angefordert. Warte und prüfe die Erreichbarkeit.',
    restartUncertain:
        'Die Antwort ist unbestätigt; der Neustart kann bereits laufen. Prüfe die Erreichbarkeit vor einem weiteren Versuch.',
    planned: 'Angenommene Einstellungen',
    current: 'Aktive Einstellungen',
    print: 'Einstellungen drucken',
    dns: 'DNS-Einstellungen und lokale Namen öffnen',
    ra: 'IPv6-Routerankündigungen verarbeiten',
    privacy: 'IPv6 Privacy Extensions verwenden',
    ip6Help:
        'Routerankündigungen und temporäre IPv6-Adressen beeinflussen die IPv6-Erreichbarkeit. Diese Einstellungen sind kein Nachweis für vollständigen IPv6-Schutz.',
    ip6Leak:
        'Globale IPv6-Adressen sind vorhanden, obwohl Routerankündigungen deaktiviert sind. IPv6-Verkehr kann den Schutz umgehen.',
    ip6Missing: 'Routerankündigungen sind aktiv, aber noch keine globale IPv6-Adresse gemeldet.',
};
type Messages = { [Key in keyof typeof de]: string };
const en: Messages = {
    edit4: 'Configure IPv4 and DHCP',
    edit6: 'Configure IPv6',
    close: 'Close',
    cancel: 'Cancel',
    save: 'Review change',
    apply: 'Confirm network change',
    saving: 'Requesting change …',
    configuration: 'IPv4 and DHCP settings',
    mode: 'Network mode',
    auto: 'Automatic (Plug & Play)',
    individual: 'Individual (eBlocker DHCP server)',
    expert: 'Expert mode',
    autoInfo:
        'Automatic mode obtains eBlocker’s address from the router using DHCP. Enable and verify the router’s DHCP service before switching.',
    individualInfo:
        'eBlocker takes over DHCP. Apply these settings and restart if required first. Then disable DHCP on the router and reconnect your devices. Leaving two DHCP servers active can cause connection problems.',
    externalInfo:
        'Configure the external DHCP server to advertise eBlocker’s IP as the gateway. If eBlocker DNS is enabled, it is also the DNS server. eBlocker does not make these router changes.',
    ip: 'eBlocker IPv4 address',
    mask: 'Network mask',
    gateway: 'Router / gateway',
    first: 'First DHCP address',
    last: 'Last DHCP address',
    lease: 'DHCP lease time in seconds',
    fixed: 'Assign fixed device addresses by default',
    dhcp: 'Enable DHCP server on eBlocker',
    primary: 'Primary name server',
    secondary: 'Secondary name server (optional)',
    discovery: 'Discover DHCP servers',
    scanning: 'Discovering DHCP servers …',
    scanNone: 'No DHCP response received. This does not prove that no other DHCP server is active.',
    scanFound: 'Responding DHCP servers',
    dhcpActual: 'Reported DHCP service on eBlocker',
    yes: 'Active',
    no: 'Inactive',
    autoConfirmed: 'I verified another active DHCP server for automatic mode.',
    confirmTitle: 'Change the network connection?',
    confirmInfo:
        'The change may interrupt network connections immediately. Note the old and new addresses. Router or DHCP settings may require a separate change. The system will not restart automatically.',
    stale: 'The configuration changed in the meantime. Reload it and review your inputs.',
    reload: 'Reload configuration',
    saved: 'The saved configuration was read back and confirmed.',
    accepted:
        'eBlocker accepted the configuration. A restart is required; the overview continues to show the currently active addresses.',
    uncertain:
        'The change could not be confirmed and may already be active. Check connectivity and the current state before making further changes.',
    address: 'Check the IPv4 address, gateway and optional name servers.',
    maskInvalid: 'The network mask must be a valid contiguous IPv4 mask.',
    subnet: 'eBlocker, the gateway and the DHCP range must be in the same subnet.',
    range: 'Enter a valid DHCP range with ascending IPv4 addresses.',
    unchanged: 'No settings changed.',
    restart: 'Restart eBlocker',
    restartTitle: 'Explicitly confirm restart',
    restartInfo:
        'Restarting interrupts networking and this console. Afterwards, open eBlocker at its new address. You may need to sign in again.',
    restartConfirm: 'Restart now',
    restarting: 'Restart requested. Wait and check reachability.',
    restartUncertain:
        'The response is unconfirmed; a restart may already be in progress. Check reachability before trying again.',
    planned: 'Accepted settings',
    current: 'Active settings',
    print: 'Print settings',
    dns: 'Open DNS settings and local names',
    ra: 'Process IPv6 router advertisements',
    privacy: 'Use IPv6 privacy extensions',
    ip6Help:
        'Router advertisements and temporary IPv6 addresses affect IPv6 connectivity. These settings do not establish complete IPv6 protection.',
    ip6Leak:
        'Global IPv6 addresses exist while router advertisements are disabled. IPv6 traffic may bypass protection.',
    ip6Missing: 'Router advertisements are enabled but no global IPv6 address is reported yet.',
};
export const networkMessages: Record<Locale, Messages> = { de, en };
