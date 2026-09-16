// SPDX-License-Identifier: EUPL-1.2
import { z } from 'zod';

// ConfigurationBackupControllerImpl / ConfigBackupReference. Passwords are deliberately stripped.
export const backupReferenceSchema = z.object({
    fileReference: z.string().regex(/^eblocker-config-[A-Za-z0-9_-]+\.eblcfg$/),
    passwordRequired: z.boolean(),
});
export const backupWarningSchema = z.object({
    id: z.string().min(1),
    itemId: z.string().nullable().optional(),
    itemName: z.string().nullable().optional(),
});
export const backupResultSchema = z.object({ warnings: z.array(backupWarningSchema) });
export const backupExportSchema = backupResultSchema.extend({
    configBackupReference: backupReferenceSchema,
});
export type BackupReference = z.infer<typeof backupReferenceSchema>;
export type BackupWarning = z.infer<typeof backupWarningSchema>;
// http.server.maxContentSize, configuration.properties; legacy upload also limits files to 1 MB.
export const MAX_BACKUP_BYTES = 1_048_576;
export function validBackupFile(file: File): boolean {
    return file.size > 0 && file.size <= MAX_BACKUP_BYTES && /\.eblcfg$/i.test(file.name);
}
