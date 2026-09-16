// SPDX-License-Identifier: EUPL-1.2
import { useState } from 'react';
import type { ConsoleClient } from '../../api/client';
import type { Locale } from '../../i18n';
import { dnsResolversSchema, dnsStatusSchema, ipv6Schema, networkSchema } from './contracts';
import { messages } from './messages';
import {
    AddressList,
    booleanLabel,
    Field,
    lookupLabel,
    PageHeading,
    reported,
    ResourcePanel,
} from './presentation';
import { useResource } from './resource';
import { NetworkEditor } from './NetworkEditor';
import { Ipv6Editor } from './Ipv6Editor';
import { networkMessages } from './networkMessages';

export function NetworkPage({ client, locale }: { client: ConsoleClient; locale: Locale }) {
    const t = messages[locale];
    const [revision, setRevision] = useState(0);
    const [editing, setEditing] = useState<'ipv4' | 'ipv6'>();
    const network = useResource(client, '/network', networkSchema, revision);
    const ipv6 = useResource(client, '/network/ip6', ipv6Schema, revision);
    const dns = useResource(client, '/dns/config/resolvers', dnsResolversSchema, revision);
    const dnsStatus = useResource(client, '/dns/status', dnsStatusSchema, revision);
    const loading = [network, ipv6, dns, dnsStatus].some((resource) => resource.loading);
    const refresh = () => setRevision((value) => value + 1);
    const resolverNames: Record<string, string> = { dhcp: t.router, custom: t.custom, tor: 'Tor' };
    const strategyNames: Record<string, string> = {
        default: t.given,
        given: t.given,
        round_robin: t.roundRobin,
        random: t.random,
    };
    return (
        <>
            <PageHeading
                title={t.network}
                description={t.networkIntro}
                loading={loading}
                refresh={refresh}
                locale={locale}
            />
            <div className="system-grid">
                <ResourcePanel title={t.ipv4} resource={network} locale={locale} retry={refresh}>
                    {(data) => (
                        <>
                            {(data.rebootNecessary || data.pendingReboot) && (
                                <p className="system-warning">{t.reboot}</p>
                            )}
                            <dl className="system-fields">
                                <Field label={t.mode}>
                                    {data.automatic ? t.automatic : t.manual}
                                </Field>
                                <Field label={t.ipAddress}>
                                    <code>{reported(data.ipAddress, locale)}</code>
                                </Field>
                                <Field label={t.networkMask}>
                                    <code>{reported(data.networkMask, locale)}</code>
                                </Field>
                                <Field label={t.gateway}>
                                    <code>{reported(data.gateway, locale)}</code>
                                </Field>
                                <Field label={t.dhcp}>{booleanLabel(data.dhcp, locale)}</Field>
                                {data.dhcp && (
                                    <>
                                        <Field label={t.dhcpFirst}>
                                            <code>{reported(data.dhcpRangeFirst, locale)}</code>
                                        </Field>
                                        <Field label={t.dhcpLast}>
                                            <code>{reported(data.dhcpRangeLast, locale)}</code>
                                        </Field>
                                        <Field label={t.dhcpLease}>
                                            {data.dhcpLeaseTime === undefined
                                                ? t.unknown
                                                : `${data.dhcpLeaseTime} ${t.seconds}`}
                                        </Field>
                                    </>
                                )}
                                <Field label={t.primaryDns}>
                                    <code>{reported(data.nameServerPrimary, locale)}</code>
                                </Field>
                                <Field label={t.secondaryDns}>
                                    <code>{reported(data.nameServerSecondary, locale)}</code>
                                </Field>
                                <Field label={t.advisedDns}>
                                    <code>{reported(data.advisedNameServer, locale)}</code>
                                </Field>
                            </dl>
                            {data.pendingReboot && data.pendingConfiguration && (
                                <section aria-label={networkMessages[locale].planned}>
                                    <h3>{networkMessages[locale].planned}</h3>
                                    <dl className="system-fields">
                                        <Field label={t.ipAddress}>
                                            <code>
                                                {reported(
                                                    data.pendingConfiguration.ipAddress,
                                                    locale,
                                                )}
                                            </code>
                                        </Field>
                                        <Field label={t.gateway}>
                                            <code>
                                                {reported(
                                                    data.pendingConfiguration.gateway,
                                                    locale,
                                                )}
                                            </code>
                                        </Field>
                                        <Field label={t.networkMask}>
                                            <code>
                                                {reported(
                                                    data.pendingConfiguration.networkMask,
                                                    locale,
                                                )}
                                            </code>
                                        </Field>
                                    </dl>
                                </section>
                            )}
                        </>
                    )}
                </ResourcePanel>
                <ResourcePanel title={t.ipv6} resource={ipv6} locale={locale} retry={refresh}>
                    {(data) => (
                        <dl className="system-fields">
                            <Field label={t.routerAdvertisements}>
                                {booleanLabel(data.routerAdvertisementsEnabled, locale)}
                            </Field>
                            <Field label={t.privacy}>
                                {booleanLabel(data.privacyExtensionsEnabled, locale)}
                            </Field>
                            <Field label={t.localAddresses}>
                                <AddressList addresses={data.localAddresses} locale={locale} />
                            </Field>
                            <Field label={t.globalAddresses}>
                                <AddressList addresses={data.globalAddresses} locale={locale} />
                            </Field>
                        </dl>
                    )}
                </ResourcePanel>
                <ResourcePanel
                    title={t.dnsService}
                    resource={dnsStatus}
                    locale={locale}
                    retry={refresh}
                >
                    {(enabled) => <p>{booleanLabel(enabled, locale)}</p>}
                </ResourcePanel>
                <ResourcePanel title={t.dns} resource={dns} locale={locale} retry={refresh}>
                    {(data) => (
                        <dl className="system-fields">
                            <Field label={t.defaultResolver}>
                                {lookupLabel(resolverNames, data.defaultResolver)}
                            </Field>
                            {data.defaultResolver === 'custom' && (
                                <Field label={t.resolverStrategy}>
                                    {data.customResolverMode
                                        ? lookupLabel(strategyNames, data.customResolverMode)
                                        : t.unknown}
                                </Field>
                            )}
                            <Field label={t.dhcpResolvers}>
                                <AddressList addresses={data.dhcpNameServers} locale={locale} />
                            </Field>
                            <Field label={t.customResolvers}>
                                <AddressList addresses={data.customNameServers} locale={locale} />
                            </Field>
                        </dl>
                    )}
                </ResourcePanel>
            </div>
            <div className="network-actions">
                <button
                    className="button"
                    disabled={loading || !!network.error || !!editing}
                    onClick={() => setEditing('ipv4')}
                >
                    {networkMessages[locale].edit4}
                </button>
                <button
                    className="button secondary"
                    disabled={loading || !!ipv6.error || !!editing}
                    onClick={() => setEditing('ipv6')}
                >
                    {networkMessages[locale].edit6}
                </button>
                <a href="#/dns">{networkMessages[locale].dns}</a>
            </div>
            {editing === 'ipv4' && (
                <NetworkEditor
                    client={client}
                    locale={locale}
                    close={() => setEditing(undefined)}
                    updated={refresh}
                />
            )}
            {editing === 'ipv6' && (
                <Ipv6Editor
                    client={client}
                    locale={locale}
                    close={() => setEditing(undefined)}
                    updated={refresh}
                />
            )}
        </>
    );
}
