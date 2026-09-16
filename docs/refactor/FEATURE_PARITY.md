# Feature parity

Status: 2026-09-16. This inventory distinguishes implemented code, retained product
paths and deployment evidence. [PROGRESS](PROGRESS.md) separates current integration
work from historical test runs. No row establishes full appliance parity.

| Area | Implemented now | Retained behavior / remaining work |
| --- | --- | --- |
| Repository/build | Integrated sources, shared Maven reactor, React/Go modules and packages | Native legacy build systems and full appliance release/upgrade process remain |
| HTTP API | Nine route modules; 360 frozen baseline declarations and separately reviewed session/PATCH/control additions | Original names, flags and precedence retained; no new general-purpose command API |
| Devices | React list/details/search, narrow settings PATCH and device-user assignment; versioned cache commits reject stale refreshes | Existing service effects remain; no transaction spanning Redis, listeners and firewall |
| Protection | Filter-list overview and confirmed trusted-app enablement with fresh reads | Complete blocker/list administration and additional protection flows retain legacy screens |
| HTTPS | Status, root-CA metadata and existing certificate download | CA generation/replacement, trust installation and complete TLS administration remain |
| Network/DNS | React IPv4/DHCP/IPv6 and DNS resolver/record/enablement editors; strict PATCH handlers merge supported fields into current configuration | Changes still use existing Java/native services and can interrupt access; no appliance-wide rollback transaction |
| Family | User/profile create/read/edit/delete, device assignment, filter selections, schedules, daily limits and bonus grants; authority checks, CAS and copied cache publication | Existing parental-control engine remains; template/system protections and appliance behavior still require integration verification |
| VPN/Tor/mobile | Existing OpenVPN profiles and per-device connect/disconnect, Tor toggle and mobile status | Profile import and full Tor/mobile setup remain in retained screens; real tunnel behavior needs appliance tests |
| Updates | Check/install/recovery requests, automatic-update settings and schedules with conflict checks | Existing update engine executes requests; no transactional appliance upgrade or proven interrupted-upgrade recovery |
| Backup | Configuration export/download, bounded upload, verification, explicit restore and separate reboot request | Existing provider archive/encryption semantics retained; not a complete appliance image or Redis cutover workflow |
| Diagnostics | Confirmed local report creation/status/download and release of browser object URLs | No invented server report deletion/content selector; real report completeness remains a server/appliance concern |
| Feature catalog | Exact ownership of all 85 Settings states; local links and component/template retention checks | Static removal guard, not equivalent runtime behavior or permission to remove old applications |
| WireGuard validation | Bounded pure Go parser, public plan, read-only Java bridge and React validation | Validation always retains applied=false/killSwitchActive=false; it stores and activates nothing |
| WireGuard control | Separate disabled-by-default local service, typed Java ADMINCONSOLE bridge and React profile import/list/status/connect/disconnect/cancel/delete | Dedicated privilege/account/socket/peer checks require installation verification; observation service stays unprivileged |
| WireGuard runtime | Private source/profile journal, ownership, recovery and native split/full tunnels; dual-stack policy rules, socket marks and atomic owned nft guard with complete readback | DNS configuration/dynamic endpoints/unhandled topology rejected; real handshake, failure/recovery and leak evidence remains outstanding |
| Passwords/PINs | Argon2id, successful legacy rehash and server administrator-token epoch; PIN rehash compares stored hashes atomically | Authentication still depends on the existing persistence/security system; no general identity-platform replacement |
| Sessions | HTTPS-only HttpOnly/Secure/SameSite cookie, same-origin/CSRF checks, server session-family logout and late-response protection | HTTP development uses in-memory Bearer mode; legacy APIs retained; no global session inventory; idle expiry includes client behavior |
| UI applications | Fourteen German/English React areas under /next/ with strict production static-serving CSP | All five AngularJS setup/settings/dashboard/controlbar/advice applications remain packaged and necessary |
| Persistence | Offline RDB conversion into logical snapshots and transactional SQLite, binary/TTL preservation, bounded decoding and provenance | Redis remains active; no live source-consistency acquisition, runtime repositories, Pub/Sub replacement or cutover/rollback |
| DNS/ICAP/PKI | Dependency/UPnP upgrades, selected race repairs and revocation-aware certificate tests | Existing proxy and data plane remain; complete network/proxy/PKI replacement is not claimed |
| amd64/arm64 | Cross-build and Debian package tooling for native components | Cross-build does not demonstrate arm64 execution, installation, systemd, boot or hardware behavior |
| Validation | Unit/component/contract/race tests, private-file/SQLite recovery tests, semantic firewall cases and opt-in integration gates | Browser/visual/axe, successful remote native CI, real tunnels/leaks and appliance release gates need independent evidence |

Shared control envelopes and errors live in
[`contracts/wireguard-control/v1`](../../contracts/wireguard-control/v1). Go's handler
produces them; Java and React check the same shape. The full native policy contract is
[`libs/wireguard/policy`](../../libs/wireguard/policy/README.md). An attested owned
firewall is distinct from a completed WireGuard handshake or working DNS.

`api-parity.csv` is the original endpoint inventory. The unchanged original route
fixture and `http-routes-additions.json` remain separate. `legacy-disposition.csv`
is a historical inventory whose paths have moved into the monorepo.
[CODE_MAP](../CODE_MAP.md) provides current entry points; `sources.lock.json` records
source provenance. None of these inventories proves universal latest versions or
complete runtime parity.
