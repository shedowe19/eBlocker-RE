// SPDX-License-Identifier: EUPL-1.2
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { ExportBackup } from './ExportBackup';
import { RestoreBackup } from './RestoreBackup';
import { backupMessages } from './messages';
import './backup.css';

export function BackupPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = backupMessages[locale];
    return (
        <div className="backup-page">
            <div className="page-heading">
                <div>
                    <span className="eyebrow">eBlocker</span>
                    <h1>{t.title}</h1>
                    <p>{t.intro}</p>
                </div>
            </div>
            <div className="backup-grid">
                <ExportBackup client={client} locale={locale} />
                <RestoreBackup client={client} locale={locale} />
            </div>
            <p className="muted">{t.resetInfo}</p>
        </div>
    );
}
