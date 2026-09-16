// SPDX-License-Identifier: EUPL-1.2
import { useCallback, useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { PageHeading, ResourcePanel } from '../system/presentation';
import { blockersSchema, trustedAppsSchema } from './contracts';
import { FilterLists } from './FilterLists';
import { TrustedApps } from './TrustedApps';
import { messages } from './messages';
import { useLoad } from './useLoad';

export function ProtectionPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const [revision, setRevision] = useState(0);
    const loadBlockers = useCallback(
        (signal: AbortSignal) => client.getBlockers(blockersSchema, signal),
        [client],
    );
    const loadApps = useCallback(
        (signal: AbortSignal) => client.get('/trustedapps/all', trustedAppsSchema, signal),
        [client],
    );
    const blockers = useLoad(loadBlockers, revision);
    const apps = useLoad(loadApps, revision);
    const refresh = () => setRevision((previous) => previous + 1);
    return (
        <>
            <PageHeading
                title={t.protection}
                description={t.introduction}
                loading={blockers.loading || apps.loading}
                refresh={refresh}
                locale={locale}
            />
            <div className="protection-sections">
                <ResourcePanel title={t.lists} resource={blockers} locale={locale} retry={refresh}>
                    {(data) => <FilterLists lists={data} locale={locale} />}
                </ResourcePanel>
                <ResourcePanel title={t.apps} resource={apps} locale={locale} retry={refresh}>
                    {(data) => (
                        <TrustedApps
                            apps={data}
                            client={client}
                            locale={locale}
                            stale={apps.loading || !!apps.error}
                            onChanged={refresh}
                        />
                    )}
                </ResourcePanel>
            </div>
        </>
    );
}
