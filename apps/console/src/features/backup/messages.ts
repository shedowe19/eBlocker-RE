// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';

const de = {
    title: 'Sicherung und Wiederherstellung',
    intro: 'Sichere die vorhandene Konfiguration oder stelle eine geprüfte eBlocker-Sicherung wieder her.',
    exportTitle: 'Konfiguration sichern',
    exportInfo:
        'Die Datei enthält persönliche Einstellungen. Mit Passwort werden auch sensible Schlüssel gesichert. Das Passwort schützt diese Schlüssel, nicht die gesamte Sicherungsdatei.',
    includeKeys: 'Sensible Schlüssel mit Passwort sichern',
    noKeys: 'Ohne Passwort fehlen sensible Schlüssel, beispielsweise für HTTPS und OpenVPN.',
    password: 'Sicherungspasswort',
    repeat: 'Sicherungspasswort wiederholen',
    passwordHelp:
        '1 bis 50 Zeichen; Leerzeichen gehören zum Passwort. Bewahre das Passwort getrennt von der Datei auf.',
    passwordInvalid: 'Gib ein Passwort mit 1 bis 50 Zeichen ein.',
    mismatch: 'Die Passwörter stimmen nicht überein.',
    create: 'Sicherung erstellen',
    creating: 'Sicherung wird erstellt …',
    download: 'Sicherung herunterladen',
    prepareDownload: 'Download bereitstellen',
    downloading: 'Datei wird übertragen …',
    ready: 'Die Sicherung steht zum Download bereit.',
    reset: 'Auswahl und sensible Eingaben zurücksetzen',
    resetInfo:
        'Dies entfernt lokale Eingaben und Download-Verweise. Bereits übertragene Dateien bleiben in der temporären Ablage des eBlockers bis zu dessen Bereinigung.',
    restoreTitle: 'Konfiguration wiederherstellen',
    restoreInfo:
        'Wähle eine .eblcfg-Datei mit höchstens 1 MiB. Sie wird zunächst hochgeladen und geprüft; erst deine Bestätigung startet die Wiederherstellung.',
    file: 'Sicherungsdatei',
    fileInvalid: 'Wähle eine nicht leere .eblcfg-Datei mit höchstens 1 MiB.',
    upload: 'Sicherung hochladen',
    uploading: 'Sicherung wird hochgeladen …',
    uploaded: 'Hochgeladene Datei',
    restoreKeys: 'Sensible Schlüssel aus der Sicherung wiederherstellen',
    skipKeys:
        'Ohne Passwort werden verschlüsselte Schlüssel nicht wiederhergestellt. Beachte die Warnungen der Prüfung.',
    verify: 'Sicherung prüfen',
    verifying: 'Sicherung wird geprüft …',
    verified: 'Die Sicherung wurde geprüft. Lies die Warnungen vor der Wiederherstellung.',
    edit: 'Passwort oder Schlüsselwahl ändern',
    restore: 'Wiederherstellung vorbereiten',
    confirmTitle: 'Konfiguration ersetzen?',
    confirmInfo:
        'Die Wiederherstellung überschreibt bestehende Einstellungen. Sichere vorher die aktuelle Konfiguration. Anschließend ist ein Neustart erforderlich; Netzwerk und Anmeldung können sich ändern.',
    confirm: 'Jetzt wiederherstellen',
    cancel: 'Abbrechen',
    restoring: 'Konfiguration wird wiederhergestellt …',
    restored:
        'Die Wiederherstellung wurde vom eBlocker bestätigt. Starte das System neu, damit alle Einstellungen wirksam werden.',
    system: 'System und Neustart öffnen',
    ambiguous:
        'Die Wiederherstellung konnte nicht bestätigt werden und kann bereits Änderungen vorgenommen haben. Prüfe den Systemzustand, bevor du sie erneut startest.',
    warnings: 'Hinweise zur Sicherung',
    unknownWarning: 'Weitere Warnung des eBlockers',
};
type Messages = { [Key in keyof typeof de]: string };
const en: Messages = {
    title: 'Backup and restore',
    intro: 'Save the existing configuration or restore a verified eBlocker backup.',
    exportTitle: 'Back up configuration',
    exportInfo:
        'The file contains personal settings. A password also includes sensitive keys. It protects those keys, not the entire backup file.',
    includeKeys: 'Include sensitive keys protected by a password',
    noKeys: 'Without a password, sensitive keys such as HTTPS and OpenVPN keys are omitted.',
    password: 'Backup password',
    repeat: 'Repeat backup password',
    passwordHelp:
        '1 to 50 characters; spaces are part of the password. Keep the password separately from the file.',
    passwordInvalid: 'Enter a password with 1 to 50 characters.',
    mismatch: 'The passwords do not match.',
    create: 'Create backup',
    creating: 'Creating backup …',
    download: 'Download backup',
    prepareDownload: 'Prepare download',
    downloading: 'Transferring file …',
    ready: 'The backup is ready to download.',
    reset: 'Reset selection and sensitive inputs',
    resetInfo:
        'This removes local inputs and download references. Files already transferred remain in eBlocker’s temporary storage until it is cleaned up.',
    restoreTitle: 'Restore configuration',
    restoreInfo:
        'Choose an .eblcfg file no larger than 1 MiB. It is uploaded and verified first; only your confirmation starts the restore.',
    file: 'Backup file',
    fileInvalid: 'Choose a non-empty .eblcfg file no larger than 1 MiB.',
    upload: 'Upload backup',
    uploading: 'Uploading backup …',
    uploaded: 'Uploaded file',
    restoreKeys: 'Restore sensitive keys from the backup',
    skipKeys:
        'Without a password, encrypted keys will not be restored. Review the verification warnings.',
    verify: 'Verify backup',
    verifying: 'Verifying backup …',
    verified: 'The backup was verified. Read the warnings before restoring it.',
    edit: 'Change password or key selection',
    restore: 'Prepare restore',
    confirmTitle: 'Replace configuration?',
    confirmInfo:
        'Restoring overwrites existing settings. Back up the current configuration first. A restart is required afterwards; network access and sign-in may change.',
    confirm: 'Restore now',
    cancel: 'Cancel',
    restoring: 'Restoring configuration …',
    restored:
        'eBlocker confirmed the restore. Restart the system to activate all restored settings.',
    system: 'Open system and restart',
    ambiguous:
        'The restore could not be confirmed and may already have changed settings. Check the system state before starting it again.',
    warnings: 'Backup notices',
    unknownWarning: 'Additional eBlocker warning',
};
export const backupMessages: Record<Locale, Messages> = { de, en };

export const warningMessages: Record<Locale, Record<string, string>> = {
    de: {
        LICENSE_CRYPTO_FAILURE: 'Die Lizenzschlüssel konnten nicht entschlüsselt werden.',
        NO_PASSWORD_HTTPS_CA_NOT_IMPORTED:
            'Ohne Passwort wurde die HTTPS-Zertifizierungsstelle nicht wiederhergestellt.',
        NO_PASSWORD_OPENVPN_SERVER_NOT_IMPORTED:
            'Ohne Passwort wurden die Schlüssel für eBlocker Mobile nicht wiederhergestellt.',
        NO_PASSWORD_OPENVPN_CLIENTS_NOT_IMPORTED:
            'Ohne Passwort wurden die OpenVPN-Schlüssel für die Anonymisierung nicht wiederhergestellt.',
        NO_PASSWORD_REGISTRATION_NOT_IMPORTED:
            'Ohne Passwort wurden Registrierungsdaten nicht wiederhergestellt.',
        UPNP_PORT_FORWARDING_FAILURE:
            'Die automatische Portweiterleitung konnte nicht eingerichtet werden.',
        ITEM_NOT_EXPORTED: 'Ein Element konnte nicht gesichert werden.',
        ITEM_NOT_IMPORTED: 'Ein Element konnte nicht wiederhergestellt werden.',
    },
    en: {
        LICENSE_CRYPTO_FAILURE: 'License keys could not be decrypted.',
        NO_PASSWORD_HTTPS_CA_NOT_IMPORTED:
            'Without a password, the HTTPS certificate authority was not restored.',
        NO_PASSWORD_OPENVPN_SERVER_NOT_IMPORTED:
            'Without a password, eBlocker Mobile keys were not restored.',
        NO_PASSWORD_OPENVPN_CLIENTS_NOT_IMPORTED:
            'Without a password, OpenVPN keys for anonymization were not restored.',
        NO_PASSWORD_REGISTRATION_NOT_IMPORTED:
            'Without a password, registration data was not restored.',
        UPNP_PORT_FORWARDING_FAILURE: 'Automatic port forwarding could not be configured.',
        ITEM_NOT_EXPORTED: 'An item could not be backed up.',
        ITEM_NOT_IMPORTED: 'An item could not be restored.',
    },
};
