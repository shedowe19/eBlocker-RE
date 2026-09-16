// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { systemStatusSchema } from './contracts';
import { messages, subsystemNames } from './messages';
import { useResource } from './resource';
import {
    booleanLabel,
    Field,
    lookupLabel,
    PageHeading,
    reported,
    ResourcePanel,
    Status,
} from './presentation';

export function SystemPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const [revision, setRevision] = useState(0);
    const resource = useResource(client, '/systemstatus', systemStatusSchema, revision);
    const refresh = () => setRevision((value) => value + 1);
    return (
        <>
            <PageHeading
                title={t.system}
                description={t.systemIntro}
                loading={resource.loading}
                refresh={refresh}
                locale={locale}
            />
            <ResourcePanel title={t.overview} resource={resource} locale={locale} retry={refresh}>
                {(data) => (
                    <>
                        <dl className="system-fields">
                            <Field label={t.version}>{reported(data.projectVersion, locale)}</Field>
                            <Field label={t.execution}>
                                <Status state={data.executionState} locale={locale} />
                            </Field>
                            <Field label={t.filterVersion}>
                                {reported(data.updatingStatus?.listsPacketVersion, locale)}
                            </Field>
                            <Field label={t.automaticUpdates}>
                                {booleanLabel(
                                    data.updatingStatus?.automaticUpdatesActivated,
                                    locale,
                                )}
                            </Field>
                            <Field label={t.updatesAvailable}>
                                {booleanLabel(data.updatingStatus?.updatesAvailable, locale, true)}
                            </Field>
                        </dl>
                        {data.updatingStatus?.lastUpdateAttemptFailed && (
                            <p className="system-warning">{t.updateFailed}</p>
                        )}
                        <h3>{t.subsystems}</h3>
                        {!data.subSystemDetails ? (
                            <p>{t.unknown}</p>
                        ) : data.subSystemDetails.length === 0 ? (
                            <p>{t.noSubsystems}</p>
                        ) : (
                            <div className="system-table-scroll">
                                <table className="system-table" aria-label={t.subsystems}>
                                    <thead>
                                        <tr>
                                            <th scope="col">{t.component}</th>
                                            <th scope="col">{t.state}</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {[...data.subSystemDetails]
                                            .sort(
                                                (a, b) =>
                                                    (a.order ?? Number.MAX_SAFE_INTEGER) -
                                                    (b.order ?? Number.MAX_SAFE_INTEGER),
                                            )
                                            .map((item, index) => (
                                                <tr key={`${item.name}-${index}`}>
                                                    <th scope="row">
                                                        {lookupLabel(
                                                            subsystemNames[locale],
                                                            item.name,
                                                        )}
                                                    </th>
                                                    <td>
                                                        <Status
                                                            state={item.status}
                                                            locale={locale}
                                                        />
                                                        {typeof item.msgContext?.error ===
                                                            'string' && (
                                                            <p className="system-detail">
                                                                {item.msgContext.error}
                                                            </p>
                                                        )}
                                                    </td>
                                                </tr>
                                            ))}
                                    </tbody>
                                </table>
                            </div>
                        )}
                        <h3>{t.warnings}</h3>
                        {!data.warnings ? (
                            <p>{t.unknown}</p>
                        ) : data.warnings.length === 0 ? (
                            <p>{t.noWarnings}</p>
                        ) : (
                            <ul className="system-warnings">
                                {data.warnings.map((warning, index) => (
                                    <li key={index}>{warning?.trim() || t.unknownWarning}</li>
                                ))}
                            </ul>
                        )}
                    </>
                )}
            </ResourcePanel>
        </>
    );
}
