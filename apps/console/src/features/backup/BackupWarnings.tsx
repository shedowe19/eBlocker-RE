// SPDX-License-Identifier: EUPL-1.2
import type { Locale } from '../../i18n';
import type { BackupWarning } from './contracts';
import { backupMessages, warningMessages } from './messages';

export function BackupWarnings({
    warnings,
    locale,
}: {
    warnings: BackupWarning[];
    locale: Locale;
}) {
    if (!warnings.length) return null;
    const t = backupMessages[locale];
    return (
        <div className="backup-warnings" role="status">
            <h3>{t.warnings}</h3>
            <ul>
                {warnings.map((warning, index) => (
                    <li key={index}>
                        {Object.hasOwn(warningMessages[locale], warning.id)
                            ? warningMessages[locale][warning.id]
                            : t.unknownWarning}
                        {warning.itemName ? ` (${warning.itemName})` : ''}
                    </li>
                ))}
            </ul>
        </div>
    );
}
