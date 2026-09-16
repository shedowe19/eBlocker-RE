# Code-Landkarte

Generiert mit `python3 scripts/code_map.py`. Produktionscode und Tests werden getrennt gezählt.

## Java-Server nach Fachbereich

| Bereich | Produktionsdateien | Testdateien |
| --- | ---: | ---: |
| [app](../apps/server/src/main/java/org/eblocker/server/app) | 4 | 3 |
| [common (Komposition)](../apps/server/src/main/java/org/eblocker/server/common) | 8 | 7 |
| [common/blacklist](../apps/server/src/main/java/org/eblocker/server/common/blacklist) | 27 | 24 |
| [common/blocker](../apps/server/src/main/java/org/eblocker/server/common/blocker) | 19 | 5 |
| [common/collections](../apps/server/src/main/java/org/eblocker/server/common/collections) | 1 | 1 |
| [common/data](../apps/server/src/main/java/org/eblocker/server/common/data) | 222 | 87 |
| [common/exceptions](../apps/server/src/main/java/org/eblocker/server/common/exceptions) | 5 | 0 |
| [common/executor](../apps/server/src/main/java/org/eblocker/server/common/executor) | 7 | 4 |
| [common/malware](../apps/server/src/main/java/org/eblocker/server/common/malware) | 3 | 2 |
| [common/network](../apps/server/src/main/java/org/eblocker/server/common/network) | 95 | 54 |
| [common/openvpn](../apps/server/src/main/java/org/eblocker/server/common/openvpn) | 30 | 16 |
| [common/page](../apps/server/src/main/java/org/eblocker/server/common/page) | 3 | 2 |
| [common/pubsub](../apps/server/src/main/java/org/eblocker/server/common/pubsub) | 5 | 1 |
| [common/recorder](../apps/server/src/main/java/org/eblocker/server/common/recorder) | 6 | 1 |
| [common/registration](../apps/server/src/main/java/org/eblocker/server/common/registration) | 8 | 7 |
| [common/scheduler](../apps/server/src/main/java/org/eblocker/server/common/scheduler) | 30 | 0 |
| [common/service](../apps/server/src/main/java/org/eblocker/server/common/service) | 7 | 4 |
| [common/session](../apps/server/src/main/java/org/eblocker/server/common/session) | 8 | 3 |
| [common/squid](../apps/server/src/main/java/org/eblocker/server/common/squid) | 13 | 5 |
| [common/ssl](../apps/server/src/main/java/org/eblocker/server/common/ssl) | 8 | 7 |
| [common/startup](../apps/server/src/main/java/org/eblocker/server/common/startup) | 6 | 2 |
| [common/status](../apps/server/src/main/java/org/eblocker/server/common/status) | 6 | 1 |
| [common/system](../apps/server/src/main/java/org/eblocker/server/common/system) | 7 | 3 |
| [common/transaction](../apps/server/src/main/java/org/eblocker/server/common/transaction) | 5 | 0 |
| [common/update](../apps/server/src/main/java/org/eblocker/server/common/update) | 6 | 4 |
| [common/util](../apps/server/src/main/java/org/eblocker/server/common/util) | 18 | 16 |
| [http](../apps/server/src/main/java/org/eblocker/server/http) | 227 | 102 |
| [icap](../apps/server/src/main/java/org/eblocker/server/icap) | 113 | 64 |
| [upnp](../apps/server/src/main/java/org/eblocker/server/upnp) | 12 | 4 |

## Einstiegspunkte

| Aufgabe | Einstieg |
| --- | --- |
| HTTP-Endpunkt finden | `apps/server/src/main/java/org/eblocker/server/http/server/routes/` |
| Bestehende Bildschirm-Routen | `apps/web/src/settings/app/routes/` |
| Neue React-Oberfläche | `apps/console/src/` |
| Geräte in Redis speichern | `apps/server/src/main/java/org/eblocker/server/common/data/JedisDeviceRepository.java` |
| Anwendung starten / Initialisierung | `apps/server/src/main/java/org/eblocker/server/app/` |
| Gemeinsame Java-Versionen | `build/parent/pom.xml` |
| DNS-Filter und Statistiken | `platform/dns/coredns/` |
| Dynamisches DNS | `platform/dns/dynamic/` |
| Netzwerkkonfiguration / native Werkzeuge | `platform/native/network-tools/` |
| Kryptografie | `libs/crypto/` |
| Herkunft der importierten Quellen | `sources.lock.json` |

## Größte verbleibende Java-Klassen

Diese Liste zeigt weitere Zerlegungskandidaten. Sie ist keine Liste unbenutzter Dateien.

| Datei | Zeilen |
| --- | ---: |
| [AppModuleService.java](../apps/server/src/main/java/org/eblocker/server/http/service/AppModuleService.java) | 947 |
| [DomainBlockingService.java](../apps/server/src/main/java/org/eblocker/server/common/blacklist/DomainBlockingService.java) | 925 |
| [JedisDataSource.java](../apps/server/src/main/java/org/eblocker/server/common/data/JedisDataSource.java) | 912 |
| [UserService.java](../apps/server/src/main/java/org/eblocker/server/http/service/UserService.java) | 843 |
| [EblockerModule.java](../apps/server/src/main/java/org/eblocker/server/common/EblockerModule.java) | 793 |
| [DeviceRegistrationProperties.java](../apps/server/src/main/java/org/eblocker/server/common/registration/DeviceRegistrationProperties.java) | 778 |
| [EblockerServerApp.java](../apps/server/src/main/java/org/eblocker/server/app/EblockerServerApp.java) | 632 |
| [EblockerDnsServer.java](../apps/server/src/main/java/org/eblocker/server/common/network/unix/EblockerDnsServer.java) | 623 |
| [OpenVpnService.java](../apps/server/src/main/java/org/eblocker/server/common/openvpn/OpenVpnService.java) | 610 |
| [DeviceControllerImpl.java](../apps/server/src/main/java/org/eblocker/server/http/controller/impl/DeviceControllerImpl.java) | 609 |
| [SquidConfigController.java](../apps/server/src/main/java/org/eblocker/server/common/squid/SquidConfigController.java) | 596 |
| [SSLControllerImpl.java](../apps/server/src/main/java/org/eblocker/server/http/controller/impl/SSLControllerImpl.java) | 548 |

## Fachgrenzen und neue Module

| Aufgabe | Zuständiger Einstieg |
| --- | --- |
| Vierzehn React-Bereiche, Sitzung und Navigation | [Console-Migration](development/console-migration.md), `apps/console/src/App.tsx`, `src/api/client.ts`, `src/features/` |
| Familie: Benutzer-/Profilverwaltung, Zuordnung, Filter und Zeiten | `apps/console/src/features/family/`, `http/controller/FamilySettingsPatch.java`, `http/service/UserService.java`, `ParentalControlService.java` |
| Benutzer-CAS, PIN-Rehash und versionierter Benutzercache | `http/service/UserService.java`, `common/data/DataSource.java`, `JedisEntityRepository.java` |
| Begrenzter Geräte-PATCH und versionierte Gerätecache-Commits | `http/controller/DeviceSettingsPatch.java`, `http/service/DeviceService.java` |
| HTTPS-Cookie/CSRF und serverseitiger Logout | `http/security/ConsoleSessionService.java`, `http/controller/ConsoleSessionController.java`; HTTP-Entwicklung verwendet weiter Bearer im Speicher |
| Passwortformat und Administrator-JWT-Epoche | `http/security/PasswordUtil.java`, `SecurityService.java`, `JsonWebTokenHandler.java`, `TokenInfo.java` |
| IPv4/DHCP/IPv6- und DNS-PATCH-Editoren | `apps/console/src/features/system/`, `src/features/dns/`, `http/controller/NetworkSettingsController.java` |
| Konfigurationssicherung und lokale Diagnoseberichte | `apps/console/src/features/backup/`, `src/features/diagnostics/`, `http/controller/impl/ConfigurationBackupControllerImpl.java` |
| Bestehendes OpenVPN/Tor und Mobile-Status | `apps/console/src/features/vpn/`; vorhandene Java-VPN-/Tor-Services bleiben zuständig |
| Updateanforderungen und Zeitfenster | `apps/console/src/features/updates/`; vorhandener Java-Update-Service führt sie aus |
| Katalog und Erhalt aller 85 Settings-Zustände | [Katalog](../apps/console/src/features/catalog/catalog.json), [Erhaltungsprüfung](../scripts/tests/test_feature_retention.py) |
| Netzwerk-/Tunnelkonfiguration in Redis | `common/data/JedisNetworkConfiguration.java` |
| JSON-Entitäten und historische Schlüssel | `common/data/JedisEntityRepository.java` |
| Geräte-Hashes und Discovery-Zeitstempel | `common/data/JedisDeviceRepository.java` |
| Offline-RDB-Konvertierung, SQLite-Import/Export/Audit und Provenienz | [Persistence-Modul](../libs/persistence/README.md), `libs/persistence/src/main/java/org/eblocker/persistence/` |
| Lesende Java-Agent-Bridge | `common/network/agent/NetworkAgentClient.java`, `http/controller/NetworkAgentController.java` |
| Getrennte Java-Control-Bridge und Administratorrouten | `common/network/agent/WireGuardControlClient.java`, `WireGuardControlModels.java`, `http/controller/WireGuardControlController.java` |
| Lesender Go-Agent / Unix-Socket | [Network Agent](../apps/network-agent/README.md), `apps/network-agent/internal/api/`, `internal/network/` |
| Privater Control-Dienst / SO_PEERCRED / feste Fehler | [Control-Vertrag](../apps/network-agent/internal/controlapi/README.md), `internal/controlapi/`, `cmd/eblocker-network-control/` |
| Gemeinsame Go-/Java-/React-JSON-Fixtures | `contracts/network-agent/v1/`, `contracts/wireguard-control/v1/` |
| WireGuard-Parser und Schlüssel-Leihe | [WireGuard-Core](../libs/wireguard/README.md), `libs/wireguard/profile.go`, `runtime.go` |
| Private Importquellen, Journal, Rollback und Recovery | [Manager](../libs/wireguard/manager/README.md), `libs/wireguard/manager/` |
| Full-Tunnel-Plan, Attestation und Paketsemantik | [Policy](../libs/wireguard/policy/README.md), `libs/wireguard/policy/` |
| Native RTNETLINK-/WireGuard-/nftables-Operationen und CLI | `apps/network-agent/internal/wgkernel/`, `cmd/eblocker-wireguard/` |
| Baseline-Routen und getrennte Session/PATCH/Control-Ergänzungen | `apps/server/src/test/resources/contracts/http-routes.json`, `http-routes-additions.json` |

Verkürzte Java-Pfade beziehen sich auf
`apps/server/src/main/java/org/eblocker/server/`, verkürzte Agentpfade auf
`apps/network-agent/`. Die erzeugten Datei-/Zeilenzahlen oben beschreiben den
Quellstand der Generierung und sind keine Test- oder Paritätszahlen.

Der lesende Netzwerkdienst bleibt ohne native Schreibrechte. Die separate
Control-Unit wird nicht automatisch aktiviert; Java erhält nur Socketzugang,
keine CAP_NET_ADMIN. Eine vollständige Kernel-Attestation kann den beobachteten
eigenen Guard bestätigen, aber keinen Handshake oder tatsächlichen Leak-Test.

Alle fünf alten Webanwendungen bleiben erhalten. Offline-RDB/SQLite konvertiert
Daten, ersetzt jedoch weder Redis-Laufzeitsemantik noch Pub/Sub und seine
Java-/Go-/nativen-/Ruby-Verbraucher. [Funktionsparität](refactor/FEATURE_PARITY.md)
und [Prüfstatus](refactor/PROGRESS.md) benennen die verbleibenden Freigabegrenzen.
