// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';
const de = {
    addUser: 'Benutzer hinzufügen',
    editUser: 'Benutzer bearbeiten',
    deleteUser: 'Benutzer löschen',
    addProfile: 'Schutzprofil hinzufügen',
    editProfile: 'Schutzprofil bearbeiten',
    deleteProfile: 'Schutzprofil löschen',
    addFilter: 'Filterliste hinzufügen',
    editFilter: 'Filterliste bearbeiten',
    deleteFilter: 'Filterliste löschen',
    assign: 'Zuordnung ändern',
    save: 'Speichern',
    saving: 'Änderung wird gespeichert …',
    saved: 'Gespeichert. Die Einstellungen wurden neu geladen.',
    savedReload: 'Gespeichert. Die aktuelle Übersicht konnte noch nicht geladen werden.',
    uncertain:
        'Die Änderung konnte nicht bestätigt werden. Der Gerätezustand wird neu geladen. Prüfe ihn vor einem weiteren Versuch.',
    conflict:
        'Die Einstellungen wurden inzwischen geändert oder der Eintrag ist nicht mehr verfügbar. Schließe die Bearbeitung und öffne sie erneut.',
    invalid: 'Prüfe die Eingaben. Die Änderung wurde vom Server nicht angenommen.',
    name: 'Name',
    description: 'Beschreibung',
    role: 'Rolle',
    nameInvalid: 'Gib einen Namen mit 1 bis 16 Zeichen ein.',
    profileNameInvalid: 'Gib einen Namen mit 1 bis 128 Zeichen ein.',
    descriptionInvalid: 'Die Beschreibung darf höchstens 4096 Zeichen enthalten.',
    birthdayInvalid: 'Gib ein gültiges Geburtsdatum ein, das nicht in der Zukunft liegt.',
    chooseProfile: 'Schutzprofil auswählen',
    profileRequired: 'Wähle ein verfügbares Schutzprofil aus.',
    profileHelp:
        'Die Regeln dieses Profils gelten für alle zugeordneten Benutzer. Ein eigenes Profil kannst du vorher unter Schutzprofile erstellen.',
    removeConfirm: 'Ich möchte diesen Eintrag endgültig löschen.',
    deleteUserHelp:
        'Zugeordnete Geräte werden wieder ihrem Standardbenutzer zugeordnet. Ein eigenes Schutzprofil kann ebenfalls entfernt werden.',
    deleteProfileHelp:
        'Ein zugeordnetes Schutzprofil kann nicht gelöscht werden. Ordne seinen Benutzern zuerst ein anderes Profil zu.',
    deleteFilterHelp:
        'Eine verwendete Liste kann nicht gelöscht werden. Entferne sie zuerst aus aktiven Schutzprofilen.',
    inUse: 'Dieser Eintrag wird noch verwendet.',
    pin: 'PIN verwalten',
    newPin: 'Neue PIN',
    repeatPin: 'PIN wiederholen',
    setPin: 'PIN speichern',
    removePin: 'PIN entfernen',
    pinPresent: 'PIN eingerichtet',
    pinAbsent: 'Keine PIN eingerichtet',
    pinUnknown: 'PIN-Status nicht verfügbar',
    pinHelp: 'Die PIN schützt den Benutzerwechsel. Verwende 4 bis 16 Zeichen.',
    pinInvalid: 'Die PIN muss 4 bis 16 Zeichen lang sein. Beide Eingaben müssen übereinstimmen.',
    removePinConfirm: 'Ich möchte den PIN-Schutz dieses Benutzers entfernen.',
    assignHelp: 'Die Zuordnung ändert auch den aktuell verwendeten Benutzer dieses Geräts.',
    assignConfirm: 'Ich bestätige den Benutzerwechsel für dieses Gerät.',
    template: 'Vorlage übernehmen',
    noTemplate: 'Ohne Vorlage',
    templateHelp: 'Eine Vorlage ersetzt die Inhalts- und Zeitregeln in diesem Formular.',
    singleUser: 'Persönliches Profil',
    singleUserHelp:
        'Persönliche Profile werden einem Benutzer zugeordnet; gemeinsame Profile können mehrere Benutzer verwenden.',
    quotaHelp:
        'Minuten pro Tag, 0 bis 1440. Leere Felder lassen einen bisher nicht festgelegten Tag unverändert.',
    quotaInvalid:
        'Tageslimits müssen ganze Zahlen zwischen 0 und 1440 sein. Bei aktiviertem Tageslimit sind alle sieben Tage erforderlich.',
    addWindow: 'Zeitfenster hinzufügen',
    removeWindow: 'Zeitfenster entfernen',
    windowInvalid:
        'Zeitfenster benötigen einen gültigen Tag und eine Anfangszeit vor der Endzeit (00:00 bis 24:00).',
    endOfDay: 'Tagesende (24:00)',
    windowHelp:
        'Außerhalb der erlaubten Zeitfenster wird der Internetzugang gesperrt. Zeitangaben beziehen sich auf den eBlocker.',
    optionalWindowQuota: 'Kontingent in Minuten (optional)',
    domains: 'Domains',
    domainsHelp:
        'Eine Domain pro Zeile, zum Beispiel example.org. Keine Pfade oder vollständigen URLs.',
    domainsInvalid:
        'Gib mindestens eine gültige Domain ein. Namen dürfen keine URLs, Pfade oder Leerzeichen enthalten.',
    filterNameInvalid: 'Listenname: 1 bis 50 Zeichen. Beschreibung: höchstens 150 Zeichen.',
    filterEnabled: 'Liste aktiviert',
    builtinHelp:
        'Vorgegebene Listen können aktiviert oder deaktiviert werden. Ihre Inhalte werden vom Anbieter gepflegt.',
    unavailableFilter: 'Nicht mehr verfügbare Liste',
    confirmRules: 'Diese Inhalts- und Zeitregeln sind geprüft',
    loadingDomains: 'Domains werden geladen …',
};
const en: typeof de = {
    addUser: 'Add user',
    editUser: 'Edit user',
    deleteUser: 'Delete user',
    addProfile: 'Add protection profile',
    editProfile: 'Edit protection profile',
    deleteProfile: 'Delete protection profile',
    addFilter: 'Add filter list',
    editFilter: 'Edit filter list',
    deleteFilter: 'Delete filter list',
    assign: 'Change assignment',
    save: 'Save',
    saving: 'Saving change …',
    saved: 'Saved. The settings have been refreshed.',
    savedReload: 'Saved. The current overview could not yet be loaded.',
    uncertain:
        'The change could not be confirmed. The device state is being reloaded. Check it before trying again.',
    conflict:
        'The settings have changed or the entry is no longer available. Close the editor and open it again.',
    invalid: 'Check your entries. The server did not accept the change.',
    name: 'Name',
    description: 'Description',
    role: 'Role',
    nameInvalid: 'Enter a name with 1 to 16 characters.',
    profileNameInvalid: 'Enter a name with 1 to 128 characters.',
    descriptionInvalid: 'The description must not exceed 4096 characters.',
    birthdayInvalid: 'Enter a valid birthday that is not in the future.',
    chooseProfile: 'Choose a protection profile',
    profileRequired: 'Choose an available protection profile.',
    profileHelp:
        'These rules apply to every user of this profile. You can create a personal profile in Protection profiles first.',
    removeConfirm: 'I want to permanently delete this entry.',
    deleteUserHelp:
        'Assigned devices return to their default user. A personal protection profile may also be removed.',
    deleteProfileHelp:
        'An assigned profile cannot be deleted. Assign its users to another profile first.',
    deleteFilterHelp:
        'A list in use cannot be deleted. Remove it from active protection profiles first.',
    inUse: 'This entry is still in use.',
    pin: 'Manage PIN',
    newPin: 'New PIN',
    repeatPin: 'Repeat PIN',
    setPin: 'Save PIN',
    removePin: 'Remove PIN',
    pinPresent: 'PIN configured',
    pinAbsent: 'No PIN configured',
    pinUnknown: 'PIN status unavailable',
    pinHelp: 'The PIN protects switching users. Use 4 to 16 characters.',
    pinInvalid: 'The PIN must have 4 to 16 characters and both entries must match.',
    removePinConfirm: 'I want to remove PIN protection for this user.',
    assignHelp: 'Assignment also changes the user currently operating this device.',
    assignConfirm: 'I confirm switching the user for this device.',
    template: 'Apply template',
    noTemplate: 'Without a template',
    templateHelp: 'A template replaces the content and time rules in this form.',
    singleUser: 'Personal profile',
    singleUserHelp:
        'Personal profiles are assigned to one user; shared profiles can serve multiple users.',
    quotaHelp:
        'Minutes per day, 0 to 1440. Empty fields keep previously unspecified days unchanged.',
    quotaInvalid:
        'Daily limits must be whole numbers from 0 to 1440. All seven days are required when daily limits are enabled.',
    addWindow: 'Add time window',
    removeWindow: 'Remove time window',
    windowInvalid: 'Time windows need a valid day and a start before the end (00:00 to 24:00).',
    endOfDay: 'End of day (24:00)',
    windowHelp:
        'Internet access is blocked outside the allowed windows. Times use the eBlocker clock.',
    optionalWindowQuota: 'Minutes allowance (optional)',
    domains: 'Domains',
    domainsHelp: 'One domain per line, such as example.org. Do not enter paths or full URLs.',
    domainsInvalid:
        'Enter at least one valid domain. Names must not contain URLs, paths or spaces.',
    filterNameInvalid: 'List name: 1 to 50 characters. Description: at most 150 characters.',
    filterEnabled: 'List enabled',
    builtinHelp:
        'Built-in lists can be enabled or disabled. Their contents are maintained by their provider.',
    unavailableFilter: 'Unavailable list',
    confirmRules: 'These content and time rules have been reviewed',
    loadingDomains: 'Loading domains …',
};
export const editMessages: Record<Locale, typeof de> = { de, en };
