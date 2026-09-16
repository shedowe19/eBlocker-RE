// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';

const de = {
    title: 'Anmeldesicherheit',
    introduction: 'Verwalte das Administratorpasswort für deinen eBlocker.',
    status: 'Passwortschutz',
    enabled: 'Aktiviert',
    disabled: 'Deaktiviert',
    statusUnknown: 'Der aktuelle Passwortschutz konnte nicht ermittelt werden.',
    reload: 'Anmeldung neu laden',
    protectedDescription: 'Für die Verwaltung wird ein Administratorpasswort benötigt.',
    unprotectedDescription: 'Die Verwaltung kann ohne Administratorpasswort geöffnet werden.',
    change: 'Passwort ändern',
    enable: 'Passwortschutz aktivieren',
    disable: 'Passwortschutz deaktivieren',
    currentPassword: 'Aktuelles Administratorpasswort',
    newPassword: 'Neues Administratorpasswort',
    repeatPassword: 'Neues Passwort wiederholen',
    passwordHelp: '1 bis 50 Zeichen. Leerzeichen gehören zum Passwort.',
    currentRequired: 'Gib dein aktuelles Administratorpasswort ein.',
    newInvalid: 'Gib ein neues Passwort mit 1 bis 50 Zeichen ein.',
    mismatch: 'Die neuen Passwörter stimmen nicht überein.',
    confirmationRequired: 'Bestätige, dass du den Passwortschutz deaktivieren möchtest.',
    disableDescription:
        'Ohne Passwortschutz kann die Verwaltung ohne Administratorpasswort verwendet werden.',
    confirmDisable: 'Ich möchte den Passwortschutz deaktivieren.',
    save: 'Passwort speichern',
    saving: 'Änderung wird gespeichert …',
    cancel: 'Abbrechen',
    reauthenticate:
        'Nach dem Speichern wirst du auf dieser Oberfläche abgemeldet und kannst dich neu anmelden.',
    saved: 'Die Änderung wurde gespeichert. Melde dich neu an.',
    ambiguous:
        'Die Änderung konnte nicht bestätigt werden. Melde dich erneut an, um den aktuellen Passwortschutz zu prüfen.',
    reconnect: 'Neu anmelden',
    erased: 'Bitte gib die Passwörter erneut ein.',
    wait: (seconds: number) => `Nächster Versuch in ${seconds} Sekunden.`,
};

type Messages = typeof de;
const en: Messages = {
    title: 'Sign-in security',
    introduction: 'Manage the administrator password for your eBlocker.',
    status: 'Password protection',
    enabled: 'Enabled',
    disabled: 'Disabled',
    statusUnknown: 'The current password protection status is unavailable.',
    reload: 'Reload sign-in',
    protectedDescription: 'An administrator password is required to manage eBlocker.',
    unprotectedDescription: 'Management can be opened without an administrator password.',
    change: 'Change password',
    enable: 'Enable password protection',
    disable: 'Disable password protection',
    currentPassword: 'Current administrator password',
    newPassword: 'New administrator password',
    repeatPassword: 'Repeat new password',
    passwordHelp: '1 to 50 characters. Spaces are part of the password.',
    currentRequired: 'Enter your current administrator password.',
    newInvalid: 'Enter a new password with 1 to 50 characters.',
    mismatch: 'The new passwords do not match.',
    confirmationRequired: 'Confirm that you want to disable password protection.',
    disableDescription:
        'Without password protection, management can be used without an administrator password.',
    confirmDisable: 'I want to disable password protection.',
    save: 'Save password',
    saving: 'Saving change …',
    cancel: 'Cancel',
    reauthenticate: 'After saving, you will be signed out of this interface and can sign in again.',
    saved: 'The change was saved. Sign in again.',
    ambiguous:
        'The change could not be confirmed. Sign in again to check the current password protection.',
    reconnect: 'Sign in again',
    erased: 'Please enter the passwords again.',
    wait: (seconds: number) => `Next attempt in ${seconds} seconds.`,
};

export const securityMessages: Record<Locale, Messages> = { de, en };
