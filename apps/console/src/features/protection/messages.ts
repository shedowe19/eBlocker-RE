// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';

const de = {
    protection: 'Schutz und Ausnahmen',
    introduction: 'Globale Filterlisten prüfen und gezielte HTTPS-Ausnahmen für Apps verwalten.',
    lists: 'Filterlisten',
    listSearch: 'Filterlisten suchen',
    search: 'App-Ausnahmen suchen',
    category: 'Kategorie',
    allCategories: 'Alle Kategorien',
    listName: 'Filterliste',
    method: 'Filtermethode',
    state: 'Einstellung',
    update: 'Listenstatus',
    builtin: 'Von eBlocker bereitgestellt',
    custom: 'Eigene Konfiguration',
    emptyLists: 'Keine Filterlisten gemeldet.',
    noResults: 'Keine passenden Einträge.',
    listsNote:
        'Aktivierte Listen sind konfiguriert. Ob sie ein Gerät schützen, hängt zusätzlich von dessen Schutz- und Netzwerkeinstellungen ab.',
    apps: 'HTTPS-Ausnahmen für Apps',
    appsNote:
        'Eine aktivierte Ausnahme nimmt ihre Domains und IP-Adressen von der HTTPS-Analyse aus. Damit können inkompatible Apps funktionieren; auf diesen Verbindungen entfällt die Inhaltsfilterung.',
    emptyApps: 'Keine sichtbaren App-Ausnahmen vorhanden.',
    addresses: 'Ausgenommene Domains und IP-Adressen',
    enable: 'Ausnahme aktivieren',
    disable: 'Ausnahme deaktivieren',
    enabled: 'Aktiviert',
    disabled: 'Deaktiviert',
    confirmTitle: 'App-Ausnahme ändern',
    enableExplanation:
        'Diese Domains und IP-Adressen werden für alle betroffenen Geräte von der HTTPS-Analyse ausgenommen.',
    disableExplanation:
        'Diese Ausnahme wird deaktiviert. Die App kann danach Verbindungsprobleme haben. Andere passende Ausnahmen bleiben wirksam.',
    confirm: 'Änderung speichern',
    cancel: 'Abbrechen',
    saving: 'Wird gespeichert …',
    saved: 'Änderung gespeichert und vom Gerät bestätigt.',
    unconfirmed:
        'Die Änderung wurde gesendet, der neue Zustand konnte aber nicht bestätigt werden. Aktualisiere die Anzeige vor weiteren Änderungen.',
    unknown: 'Nicht gemeldet',
    https: 'HTTPS-Analyse',
    httpsIntro: 'Status und öffentliches Zertifikat deines eBlockers prüfen.',
    httpsStatus: 'Globale HTTPS-Analyse',
    httpsNote:
        'Die globale Einstellung allein bestätigt keine erfolgreiche HTTPS-Analyse. Die Gerätefreigabe und das Vertrauen in das eBlocker-Zertifikat müssen ebenfalls eingerichtet sein.',
    certificate: 'Öffentliches CA-Zertifikat',
    noCertificate: 'Es ist noch kein CA-Zertifikat eingerichtet.',
    certificateName: 'Zertifikatsname',
    validFrom: 'Gültig ab',
    validUntil: 'Gültig bis',
    fingerprint: 'SHA-256-Fingerabdruck',
    expired: 'Dieses Zertifikat ist abgelaufen.',
    notYetValid: 'Dieses Zertifikat ist noch nicht gültig.',
    download: 'Zertifikat herunterladen',
    downloadTitle: 'Download und HTTPS-Aktivierung',
    downloadExplanation:
        'Beim Herunterladen aktiviert eBlocker automatisch die HTTPS-Analyse für das aktuell verwendete Gerät. Erst die anschließende Installation des Zertifikats schafft das notwendige Vertrauen im Browser oder Betriebssystem.',
    downloadConfirm: 'HTTPS für dieses Gerät aktivieren und Zertifikat laden',
};
type Messages = { [Key in keyof typeof de]: string };
const en: Messages = {
    protection: 'Protection and exceptions',
    introduction: 'Review global filter lists and manage individual HTTPS exceptions for apps.',
    lists: 'Filter lists',
    listSearch: 'Search filter lists',
    search: 'Search app exceptions',
    category: 'Category',
    allCategories: 'All categories',
    listName: 'Filter list',
    method: 'Filter method',
    state: 'Setting',
    update: 'List status',
    builtin: 'Provided by eBlocker',
    custom: 'Custom configuration',
    emptyLists: 'No filter lists reported.',
    noResults: 'No matching entries.',
    listsNote:
        'Enabled lists are configured. Whether they protect a device also depends on its protection and network settings.',
    apps: 'HTTPS exceptions for apps',
    appsNote:
        'An enabled exception excludes its domains and IP addresses from HTTPS inspection. This can restore compatibility with apps; content filtering does not apply to those connections.',
    emptyApps: 'No visible app exceptions available.',
    addresses: 'Excluded domains and IP addresses',
    enable: 'Enable exception',
    disable: 'Disable exception',
    enabled: 'Enabled',
    disabled: 'Disabled',
    confirmTitle: 'Change app exception',
    enableExplanation:
        'These domains and IP addresses will be excluded from HTTPS inspection for all affected devices.',
    disableExplanation:
        'This exception will be disabled. The app may then have connection problems. Other matching exceptions remain effective.',
    confirm: 'Save change',
    cancel: 'Cancel',
    saving: 'Saving …',
    saved: 'Change saved and confirmed by the appliance.',
    unconfirmed:
        'The change was sent, but its new state could not be confirmed. Refresh the page before making further changes.',
    unknown: 'Not reported',
    https: 'HTTPS inspection',
    httpsIntro: 'Review your eBlocker status and public certificate.',
    httpsStatus: 'Global HTTPS inspection',
    httpsNote:
        'The global setting alone does not confirm successful HTTPS inspection. The device must also be enabled and trust the eBlocker certificate.',
    certificate: 'Public CA certificate',
    noCertificate: 'No CA certificate has been configured yet.',
    certificateName: 'Certificate name',
    validFrom: 'Valid from',
    validUntil: 'Valid until',
    fingerprint: 'SHA-256 fingerprint',
    expired: 'This certificate has expired.',
    notYetValid: 'This certificate is not valid yet.',
    download: 'Download certificate',
    downloadTitle: 'Download and enable HTTPS',
    downloadExplanation:
        'Downloading automatically enables HTTPS inspection for the device you are currently using. You then need to install the certificate to establish trust in your browser or operating system.',
    downloadConfirm: 'Enable HTTPS for this device and download certificate',
};
export const messages: Record<Locale, Messages> = { de, en };
export const categories: Record<Locale, Record<string, string>> = {
    de: {
        ADS: 'Werbung',
        TRACKER: 'Tracker',
        MALWARE: 'Schadsoftware',
        PARENTAL_CONTROL: 'Jugendschutz',
        CUSTOM: 'Eigene Listen',
        CONTENT: 'Inhalte',
    },
    en: {
        ADS: 'Advertising',
        TRACKER: 'Trackers',
        MALWARE: 'Malware',
        PARENTAL_CONTROL: 'Parental controls',
        CUSTOM: 'Custom lists',
        CONTENT: 'Content',
    },
};
export const methods: Record<Locale, Record<string, string>> = {
    de: { DOMAIN: 'Domains', PATTERN: 'URL-Muster' },
    en: { DOMAIN: 'Domains', PATTERN: 'URL patterns' },
};
export const updateStates: Record<Locale, Record<string, string>> = {
    de: {
        NEW: 'Neu',
        INITIAL_UPDATE: 'Wird erstmals geladen',
        INITIAL_UPDATE_FAILED: 'Erstes Laden fehlgeschlagen',
        INITIAL_UPDATE_DELAYED: 'Erstes Laden verzögert',
        READY: 'Bereit',
        UPDATE: 'Wird aktualisiert',
        UPDATE_FAILED: 'Aktualisierung fehlgeschlagen',
    },
    en: {
        NEW: 'New',
        INITIAL_UPDATE: 'Initial download',
        INITIAL_UPDATE_FAILED: 'Initial download failed',
        INITIAL_UPDATE_DELAYED: 'Initial download delayed',
        READY: 'Ready',
        UPDATE: 'Updating',
        UPDATE_FAILED: 'Update failed',
    },
};
