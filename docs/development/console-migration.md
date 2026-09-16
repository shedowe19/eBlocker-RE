# React console migration

Status: 2026-09-16. Fourteen German/English administration areas are implemented
under `/next/`. Existing appliance services and the five remaining AngularJS
applications are retained. Implemented screens and contracts do not establish a
successful appliance rollout or complete browser/accessibility parity.

## Delivered behavior

| Area / hash route | Implemented behavior | Main source under `apps/console/src/features` |
| --- | --- | --- |
| Devices `/devices` | List/details, search, filtering, IPv4/IPv6 and bounded settings PATCH | `devices` |
| Protection `/protection` | Filter overview and confirmed trusted-app enablement | `protection` |
| HTTPS `/https` | Status, CA identity/validity and existing certificate download | `protection` |
| Network `/network` | IPv4/DHCP configuration and IPv6-mode editors with conflict checks | `system` |
| DNS `/dns` | Resolver settings, custom IPv4/IPv6 records, enablement, cache clear and statistics | `dns` |
| Family `/family` | User/profile CRUD, assignments, filters, weekly windows, daily limits and bonus grants | `family` |
| VPN/Tor `/vpn` | Existing OpenVPN device assignment, connect/disconnect, Tor toggle and mobile status | `vpn` |
| WireGuard `/wireguard` | Pure profile validation plus separately authorized import/list/status/connect/disconnect/cancel/delete | `wireguard` |
| Security `/security` | Password enable/change/disable and current-session logout | `security` |
| System `/system` | Version, update/subsystem state and warnings | `system` |
| Updates `/updates` | Check/install/recovery requests, automatic updates and time windows | `updates` |
| Backup `/backup` | Configuration export/download, upload, verification, explicit restore and separate reboot | `backup` |
| Diagnostics `/diagnostics` | Confirmed local report creation/status/download | `diagnostics` |
| All features `/features` | Searchable catalog with exact links into retained settings | `catalog` |

`src/api` owns same-origin transport, runtime schemas and session state; `App.tsx`
owns navigation/language/activity. Each feature owns its messages and domain forms.
Required invalid API values produce visible errors rather than synthetic healthy
state. Production has no fixture/sample-data mode, external fonts or analytics.

The catalog assigns all 85 original Settings states exactly once and retains the
five original application entry points. Static checks protect links, components and
templates, not runtime equivalence. Setup, settings, dashboard, controlbar and advice
remain necessary; their links can require a separate legacy login.

## Session and credential boundary

`ConsoleClient` chooses cookie mode on HTTPS. Its bootstrap/login/renew/logout use
`/api/adminconsole/authentication/session` and the `/login`, `/renew`, `/logout`
subpaths. Bootstrap/status returns app context, authentication/password-required
state, expiration and CSRF token, not an administrator JWT for JavaScript storage.
The server cookie is `__Host-eblocker-console`, Secure, HttpOnly, SameSite=Strict,
with Path `/`. Session requests validate actual TLS transport, same HTTPS origin,
console metadata and CSRF. Origin/security metadata supplied as query parameters
cannot substitute for headers.

Login rotates the session identity. Server logout revokes the current session
family, including an overlapping login/renewal result; it does not revoke every
administrator session globally. Successful password enable/change/removal/reset
also invalidates older ADMINCONSOLE/CONSOLE/ADMINDASHBOARD tokens through the
server epoch. The server retains Bearer endpoints for existing clients.

On an HTTP development origin, the client's automatic mode deliberately uses the
existing Bearer flow with tokens **only in memory**. This maintains the Vite
HTTP-loopback development adapter; it does not test cookie sessions. Neither mode
puts credentials in localStorage/sessionStorage. Only language preference persists.
Real cookie verification requires the same trusted HTTPS origin as the API.

Only user pointer/keyboard/wheel activity extends the 20-minute client idle limit;
polling and renewal do not. Hidden-tab return checks expiry. Late operations cannot
restore a cleared client session. General JSON calls have bounded deadlines; the
WireGuard control exchange allows 45 seconds and backup/binary transfers 60 seconds.
Requests stay on the current origin and reject redirects.

New password/PIN hashes use versioned Argon2id with random salt. Successful legacy
PIN verification reads current persistence and compares the old stored PIN atomically
before rehash; it never saves a stale whole-user snapshot. Concurrent PIN changes
are rechecked before authentication. Whole-user CAS, copied cache values and
versioned cache refreshes likewise protect family edits. Failed persistence does
not publish the replacement or trigger dependent dashboard updates. These targeted
repairs do not create a transaction across all Redis, listener and network effects.

## Narrow settings and family operations

The six-field device settings PATCH preserves omitted values, validates types and
names, protects infrastructure devices and conflicts with active pause changes.
Separate assignment PATCHes change the intended user relation. Family management
supports ordinary user/profile create/edit/delete and preserves system/template
protection, administrator/parent authority and checked bonus arithmetic.

IPv4 GET responses add `revision`, `pendingReboot` and, when present,
`pendingConfiguration`. The flat fields describe the running interface; desired
settings awaiting an operating-system reboot are reported separately. PATCH
requires the observed revision. Every existing network writer rotates it, and a
saved pending configuration blocks further network writes until an actual kernel
boot change. A Java restart keeps that pending state. The journal uses the existing
Redis persistence; it is not a new power-loss-safe transaction. Null properties
may be omitted by the HTTP serializer.

Network/DNS PATCHes carry expected/current values and the intended change. The
server rejects stale expected state and preserves fields outside the supported
editor. Resolver edits retain observed DHCP data; custom DNS record edits preserve
VPN fields and built-in entries. Forms read fresh state before applying and read
back afterward. Network changes may interrupt this console or change the appliance
address; a timed-out request is not proof that no change occurred. Existing
Java/native services remain responsible for applying these settings.

The reviewed additions are recorded in
`apps/server/src/test/resources/contracts/http-routes-additions.json`. The original
360-route fixture stays separate; no route count in this document replaces that
executable contract.

## WireGuard observation and control

`POST /api/adminconsole/wireguard/validate` accepts `{config: string}` and uses the
existing read-only agent. Validation is a public import plan: it stores no keys,
executes no hooks and never changes routes/DNS/firewall. Its `applied` and
`killSwitchActive` fields remain false.

Management uses the separate `/api/adminconsole/wireguard/profiles` bridge:

| Method and suffix | Body | Result |
| --- | --- | --- |
| GET collection | None | Public profile summaries; historical phase only |
| PUT `/{id}` | `{schemaVersion: 1, configuration: string}` | Imported public summary |
| GET `/{id}` | None | Public plan and fresh runtime observation, or runtime null |
| POST `/{id}/connect`, `/disconnect` | `{schemaVersion: 1}` | Lifecycle state requiring subsequent readback |
| POST `/{id}/cancel` | `{schemaVersion: 1}` | Whether cancellation was requested |
| DELETE `/{id}` | None | Explicit deletion result |

The dedicated local control service is disabled by default, uses its own system
account/CAP_NET_ADMIN and verifies the exact Java peer UID/primary GID through
SO_PEERCRED. The read-only agent retains its separate privilege boundary. Requests
are strictly bounded; errors and public DTOs contain no private configuration or
unchecked native text. Shared fixtures live in `contracts/wireguard-control/v1`.

Import stores private 0600 source files until explicit deletion. The manager rejects
replacement/deletion of active or incomplete profiles. It journals native policy
before mutation and recovers only owned resources. Full tunnels install an atomic
owned firewall before interfaces/routes, with both address families, exact UDP
endpoint exemptions and explicit LAN/DHCP/NDP exceptions. Firewall removal comes
last. DNS configuration/dynamic endpoints and unsupported topologies remain rejected.

The UI separates stored/list state from fresh runtime observations. Only complete
native readback can set `runtime.killSwitchActive`. Active interface/peer/policy
state is not a handshake, internet reachability or DNS success. Native lifecycle,
semantic packet and contract tests do not establish real tunnel/leak behavior.
The control service serializes operations, so collection and status reads are
sequential; `cancel` can interrupt a running operation. Ambiguous writes are not
silently retried. Existing OpenVPN/Tor behavior remains separate and available.

## Backups, diagnostics and existing updates

Configuration backup uses the existing `/api/configbackup` export/upload/verify/import
and download contract, including its existing encrypted sensitive-provider payloads.
Uploads are bounded and a restore requires successful verification plus explicit
confirmation. Restart is a separate operation. Restore acknowledgment does not
prove a successful reboot or complete appliance recovery. The page clears transient
passwords and browser download references and does not invent server-side archive
deletion.

Diagnostics confirms creation of the local report, observes its state and retrieves
the existing authenticated binary download. Releasing the browser object URL does
not delete the appliance report; the existing API exposes no such deletion or
content-selection operation. Neither page automatically sends files to third parties.

Existing OpenVPN/Tor and update forms continue to read current state, check conflicts
and read back narrow changes. OpenVPN profile import/full mobile setup and remaining
Tor administration retain exact legacy links. Update requests use the existing
engine; this is not a transactional appliance upgrade or rollback implementation.

## Development, packaging and remaining gates

Use the repository-pinned Node toolchain:

```sh
cd apps/console
npm ci
npm run check
EBLOCKER_DEV_TARGET=https://your-eblocker-origin npm run dev
```

The loopback Vite proxy is a Bearer development adapter on HTTP, as described above.
Without a target, API failures remain visible; no sample data is substituted. Keep
TLS verification enabled. Real cookie/origin checks require a same-origin HTTPS
setup rather than treating this proxy as cookie evidence.

`npm run build` creates production assets. Maven packages them under
`/opt/eblocker-icap/htdocs/next`; existing web assets remain beside them. Production
static files have CSP, nosniff and frame denial; Vite removes only the development
meta policy required by its refresh preamble. Hash routes avoid a history fallback.

`npm run test:e2e` uses explicit intercepted test fixtures and a separately installed
browser. [PROGRESS](../refactor/PROGRESS.md) records verified runs; a unit/build/package
success is not browser, axe, visual, systemd or appliance evidence. Final integration
counts for this continuation are recorded after the coordinator's completion run.

Remaining work includes complete protection/HTTPS editors, VPN import/full mobile
and Tor setup, setup/dashboard/controlbar/advice parity, real cookie/browser/native
integration and appliance installation/upgrade verification. Offline RDB/SQLite
conversion is implemented but Redis runtime cutover/PubSub replacement is not.
Native WireGuard still needs real kernel/handshake/failure/leak proof and resolver
management. AngularJS can be removed only after complete functional, packaging,
upgrade and browser/appliance equivalence is demonstrated.

## Local validation of this continuation

The final console check passed 347 tests across 26 files, formatting, TypeScript,
and the production build. Eighteen desktop/mobile Playwright scenarios are
registered, including family mutations, verified backup restoration, DNS conditional
updates and the WireGuard profile lifecycle. They were not executed here: browser
socket creation is unavailable in this workspace. The console Debian archive was
checked against all five current production assets. The shared JavaScript bundle
is 178.95 kB gzip; route-level splitting remains a possible improvement.
