# Stand der Modernisierung

Stand: 16. September 2026. Arbeitsbranch: `codex/modernize-architecture`.
Ausgangspunkt: `feature/debian_bookworm`, Commit
`dc954e75116751dbf4c700892e943a1b933d1b5a`.
Die ursprünglichen Commit-IDs aller 22 integrierten Begleitquellen und die
bewussten Importausschlüsse stehen in `sources.lock.json`.

## Aktuelle Fortsetzung: Sitzungen, Verwaltung und nativer Control-Pfad

Die nachstehenden Funktionen sind implementiert und die gemeinsame lokale
Prüfung ist abgeschlossen. Die Ergebnisse gelten für diese Fortsetzung; ältere
Zahlen weiter unten sind historische Vergleichsstände. Reale Appliance-, Browser-
und privilegierte Kernelprüfungen sind weiterhin nicht nachgewiesen.

| Abschlussprüfung | Ergebnis |
| --- | --- |
| Gemeinsamer Java-Reaktor, JDK 17 | 3.134 Tests entdeckt, 3.126 bestanden, acht bekannte Skips, keine Fehler; Server, alle benötigten Bibliotheken, Zertifikatsvalidator, Filterlisten und SQLite-Modul |
| Server, enthalten im Reaktorlauf | 2.402 entdeckt, 2.394 bestanden, acht bekannte Skips |
| React-Konsole | `npm run check` bestanden: 347 Tests in 26 Dateien, Formatierung, TypeScript, Produktionsbuild und Lizenzhinweise |
| HTTP-Verträge und Quellerhalt | 360 eingefrorene ursprüngliche Routen plus 23 deklarierte Ergänzungen; `make check`: elf Prüfungen bestanden, einschließlich Erhalt aller 85 Settings-Zustände und fünf alten Webanwendungen |
| SQLite/RDB, enthalten im Reaktorlauf | 101 Tests bestanden; zusätzlich zuvor paketierter CLI-Import/Retry/Audit/Export-Roundtrip geprüft |
| Netzwerkdienste in Go | 187 Fälle mit Race-Prüfung bestanden, sieben bewusst übersprungene Socket-/Kernel-Fälle; `go vet` bestanden; getrennter Control-Socket-Build kompiliert, sein Integrationsfall ohne Aktivierung übersprungen |
| WireGuard-Core | Vet und Race-Prüfung bestanden; Parser 96,4 %, Manager 80,3 %, Policy 89,0 % Statement-Abdeckung; typisierte Kernel-Testtreiber |
| Browser-Szenarien | 18 Desktop-/Mobile-Szenarien in drei Dateien erfasst und TypeScript-geprüft; kein Chromium-/axe-/visueller Ausführungsnachweis |
| Paketbau | Server und SQLite samt eingebundenen Bibliotheken sowie Konsole per Maven paketiert; Paketbau überspringt die zuvor separat bestandenen Tests. Serverarchiv enthält neue Session-/Netzwerk-/Control-Klassen; alle fünf Konsolenassets stimmen bytegenau mit dem Produktionsbuild überein |
| Go-Pakete | amd64 und arm64 neu gebaut; je drei passende ELF-Programme, getrennte deaktivierte Control-Unit und Lizenzhinweise für 13 externe Module geprüft; keine Installation |

Die gemeinsame JS-Datei der Konsole ist 614,61 kB minifiziert beziehungsweise
178,95 kB gzip groß. Vites Größenhinweis bleibt eine Optimierungsaufgabe; der
Build ist erfolgreich. Die acht Java-Skips sind die bereits weiter unten genannten
sechs deaktivierten Altprüfungen und zwei expliziten Unix-Socket-Integrationsfälle.

Erzeugte aktuelle Debianartefakte (nicht im Git-Quellbestand):

| Paket | SHA-256 |
| --- | --- |
| `apps/server/target/eblocker-icapserver_4.0.3.20260916025826_all.deb` | `b97e9dbb1efff9a1243ea3302ef77067c87c57d256ed65e220b14a05efaa138e` |
| `apps/console/target/eblocker-console_4.0.3.20260916025929_all.deb` | `6ff691855b2eb4743c424cbd2c90964a7200dc73771b5559265f25f5f1a12c7d` |
| `apps/network-agent/target/eblocker-network-agent_4.0.3+next1_amd64.deb` | `33230b7ac09a62d9e0a7d1f5201b5a2846261cb9c555f9d9e3432492ce9edbe9` |
| `apps/network-agent/target/eblocker-network-agent_4.0.3+next1_arm64.deb` | `da320d1fbcb3b5420733000474142afdafbf4e956a205b580c26395ce3597132` |


- Vierzehn React-Bereiche in Deutsch/Englisch. Neu hinzugekommen sind Sicherung
  und Diagnose; Netzwerk/DNS besitzen gezielte Editoren und Familie die
  Benutzer-/Profilverwaltung einschließlich Anlegen, Ändern, Löschen, Zuordnung,
  Filtern, Zeitfenstern und Kontingenten. Vorhandene Legacy-Pfade bleiben erhalten.
- HTTPS-Cookie-Sitzungen mit HttpOnly/Secure/SameSite=Strict, Origin-/CSRF-Prüfung,
  Rotation und serverseitiger Sperrung der aktuellen Sitzungsfamilie bei Logout.
  Späte Login-/Renew-Antworten können den Logout nicht zurücknehmen. Bestehende
  Bearer-Clients bleiben kompatibel; globale Sitzungsinventur ist nicht enthalten.
- Erfolgreich geprüfte alte PINs werden nach Argon2id migriert. PIN-CAS und
  Ganzbenutzer-CAS vermeiden veraltete Persistenzüberschreibungen; Benutzer- und
  Gerätecaches veröffentlichen Kopien/versionierte Commitzustände. Das ersetzt
  keine verteilte Redis-/Netzwerktransaktion.
- Netzwerkänderungen tragen eine geprüfte Revision. Laufende und vor dem Neustart
  gewünschte Einstellungen werden getrennt angezeigt; ein gespeicherter Pending-
  Zustand übersteht Java-Neustarts und verhindert versehentliche Folgeüberschreibungen.
  DNS-Schreibwege sind atomar, die gefundene DNS-/Filter-Lock-Inversion ist behoben.
  Die bestehende Redis-Persistenz erhält dadurch keine neue Stromausfallgarantie.
- Sicherung verwendet vorhandene Konfigurationsexporte, Upload, Prüfung und
  ausdrückliche Wiederherstellung; Diagnose erzeugt und lädt lokale Berichte.
  Binärtransfers haben Größen-/Zeitgrenzen und verwerfen späte Antworten nach Logout.
- WireGuard hat zusätzlich zur unverändert lesenden Agent-API einen eigenen,
  standardmäßig deaktivierten Control-Dienst. Java/React verwalten Profile über
  dessen privaten Socket; SO_PEERCRED prüft die genaue icapd-UID/Primärgruppe.
  Nur der getrennte Dienst erhält nach ausdrücklicher Aktivierung CAP_NET_ADMIN.
- Native Full-Tunnel-Planung und Lifecycle umfassen beide IP-Familien, eigene
  Routingtabellen/Regeln, Socket-Mark, genaue Endpoint-Ausnahmen und eine atomare
  eigene nftables-Firewall. Guard zuerst, Entfernung zuletzt; ein Schutzstatus
  verlangt vollständiges frisches Kernel-Readback. DNS-Konfiguration, dynamische
  Endpoints und nicht beherrschte Topologien bleiben ausdrücklich abgewiesen.
- Private Importquellen bleiben mit 0600 bis Delete gespeichert; temporäre
  Laufzeitkonfigurationen werden nach Disconnect gelöscht. Aktive/unvollständige
  Profile lassen sich nicht überschreiben oder löschen. Gemeinsame Go-/Java-/React-
  Fixtures liegen unter `contracts/wireguard-control/v1`.
- Offline-RDB-Konvertierung ist implementiert: begrenzte RDB-Versionen/Encodings,
  CRC64/Framing, ressourcenbegrenzter Worker, Herkunftsnachweis und atomarer
  SQLite-Import. Die isolierte Persistence-Modulsuite wurde mit 101 bestandenen
  Tests gemeldet; dies ist keine Gesamtserver- oder Appliance-Prüfung. Redis,
  Pub/Sub, native/Ruby-Verbraucher und Laufzeitbindung bleiben unverändert.
- Die 360 Baseline-HTTP-Routen bleiben eingefroren. Session-, PATCH- und
  Control-Ergänzungen stehen im separaten Routenvertrag; die endgültige Gesamtzahl
  wird mit der abschließenden Strukturprüfung übernommen.

### Prüfgrenzen des aktuellen Stands

| Prüfung | Aktueller Nachweis |
| --- | --- |
| Gesamter Server/Console/Struktur/Pakete | Abschlusslauf und finale Zahlen: noch zu ergänzen |
| WireGuard-Core/Manager/Policy | Lokale Race-/Vet- und amd64/arm64-Builds bestanden; kontrollierte Treiber/private Tempdateien, keine Host-Netzmutation |
| Control-Handler und CLI-Einstieg | Race-/Vet-/Cross-Builds bestanden; echter Manager mit Testbackend, Auth-/JSON-/Secret-/Timeout-/Cancel-/Dateiregressionen |
| Offline-RDB/SQLite | 101 isolierte Modultests gemeldet; finaler Paket-/Integrationsstand folgt separat |
| Native Full-Tunnel-Regeln | Fake-Kernel- und semantische Paketprüfungen; kein daraus abgeleiteter echter Handshake oder Leak-Nachweis |
| Unix-/Netlink-/Appliancebetrieb | Isolierte opt-in CI-Gates vorbereitet; kein bestandener nativer CI-Lauf behauptet |
| Browser/visuell/axe | Keine neue Freigabe aus Unit-/Produktionsbuilds abgeleitet |

### Historischer Zwischenstand vor dieser Erweiterung

Die folgende Tabelle gehört zur vorherigen Etappe mit elf React-Bereichen,
Split-Tunnel-CLI und ohne Control-/Cookie-/RDB-Erweiterung. Ihre Läufe überschneiden
sich und werden nicht addiert.

| Prüfung | Belegter Stand |
| --- | --- |
| Vollständige Server-Suite dieser Fortsetzung | 2.226 entdeckt, 2.218 bestanden, acht übersprungen, keine Fehler; vor der anschließenden gezielten Filteränderungs-Race-Korrektur |
| Gerätecache-Korrektur | 64 gezielte Regressionen bestanden |
| Administrator-JWT-Epoche | 64 gezielte Sicherheits-/Authentifizierungsprüfungen bestanden; alle drei Administrator-Kontexte berücksichtigt |
| Offline-SQLite | 46 Tests bestanden, keine Skips; echte SQLite-Dateien, Binärdaten/TTL/alle Typen, konkurrierende Importe, Fehler-Rollback und abrupt beendete JVM |
| Abschließende Paketstände | Serverpaket nach der letzten Filterkorrektur erfolgreich neu gebaut; enthaltene Service-Bytecodes mit dem aktuellen Build verglichen. Agentpakete nach der letzten Core-Recovery-Korrektur für amd64/arm64 neu gebaut; beide ELF-Programme je Architektur sowie zwölf Drittmodul-Lizenzsets und Go-Lizenz geprüft. Keine Installation |
| Console-Debian-Paket | Abschließender Maven-Paketbau mit `-DskipTests` bestanden; eigener aktueller UI-Testlauf steht separat in dieser Tabelle |
| SQLite-CLI-Paket | `verify` bestanden; das erzeugte JAR separat importiert, wiederholt, auditiert, exportiert und erneut importiert; gleiche Prüfsumme, bestehendes Exportziel abgewiesen |
| Struktur, HTTP-Verträge und Funktionserhalt | `make check`: elf Prüfungen bestanden; darin die folgenden fünf Erhaltungsprüfungen |
| Statischer Funktionserhalt | Fünf `test_feature_retention.py`-Prüfungen bestanden: 85 Zustände, genaue Links, Komponenten/Templates und fünf Webanwendungen |
| Erweiterte React-Konsole | `npm run check`: 202 Tests in 17 Dateien, Formatprüfung, TypeScript und Produktionsbuild bestanden |
| Bonuszeit-/Elternrechte-/Filterkorrektur | 47 gezielte Tests bestanden, keine Skips; zwei neue Tests nach der obigen Vollsuite prüfen Bonus-/Filterüberschneidung und fehlgeschlagenes Speichern |
| Netzwerkagent einschließlich nativem Adapter/CLI | 78 Testfälle mit Race-Prüfung bestanden, `go vet` bestanden; sechs explizite Integrationsfälle ausstehend (fünf Socket-/Netlinkfälle, ein privilegierter WireGuard-Lifecycle) |
| WireGuard-Kernel-UAPI | 289.763 Fuzz-Eingaben ohne Fehler; keine reale Kernel-Mutation in dieser Umgebung |
| WireGuard-Core und Lifecycle | `go vet` und `go test -race -cover ./...` bestanden; Parser/Runtime 96,4 %, Manager 78,8 % Statement-Abdeckung; 16 Manager-Testfunktionen mit Unterfällen einschließlich Cleanup nach Crash in allen drei Endzuständen |

### Vorherige React-/Agent-Etappe vom 16. September

Diese Ergebnisse gehören zum Stand mit sieben React-Bereichen **vor** der obigen
Fortsetzung. Sie sind keine erneute Vollprüfung des erweiterten Quellstands.

| Prüfung | Damals belegtes Ergebnis |
| --- | --- |
| Vollständige Server-Suite | 2.181 entdeckt, 2.173 bestanden, acht übersprungen, keine Fehler; anschließende kleine API-/Sicherheitskorrekturen in 67 Regressionen geprüft |
| Redis-Zerlegung | 13 neue Netzwerk-/JSON-Charakterisierungen vor Extraktion, danach 86 relevante Regressionen bestanden |
| Agent-Java-Brücke und Routenkontrakt | 61 Tests bestanden; zwei echte Unix-Socketfälle nicht aktiviert |
| Agent-Go-API | 39 API-/Parser-/Fehler-/Redaktionsfälle bestanden; fünf echte Socket-/Netlinkfälle nicht nachgewiesen |
| Gemeinsamer Go-/Java-/React-Vertrag | Go-Goldens mit Race-Test, 14 Java-Verbrauchertests und gemeinsame React-Schemata geprüft |
| Konsole mit sieben Bereichen | `npm run check`: 143 Tests, Formatprüfung, TypeScript und Produktionsbuild bestanden |
| Pakete | Server-/Console-Maven-Pakete sowie Agentpakete für amd64/arm64 gebaut und Metadaten/ELF geprüft; keine Installation |
| Browser | Zehn Desktop-/Mobile-Playwright-Fälle auflistbar und TypeScript-geprüft; kein erfolgreicher Browser-/axe-/visueller Lauf |

Die acht übersprungenen Serverfälle dieser früheren Suite: vier
`DeviceRegistrationClientTest`, ein `ParentalControlFilterListsServiceTest` und ein
`JedisTest` waren schon zuvor deaktiviert; zwei
`UnixSocketHttpTransportIntegrationTest` benötigen die explizite Socket-Umgebung.

Java-Bridge-Integration verlangt `EBLOCKER_AGENT_INTEGRATION=true`, die Go-Agent-
Integration `EBLOCKER_AGENT_INTEGRATION=1`. Native WireGuard-Mutation benötigt
zusätzlich `EBLOCKER_WIREGUARD_INTEGRATION=1` in einer dafür eingerichteten,
verwerfbaren Linux-Umgebung mit isoliertem Netzwerk-Namespace. AF_UNIX/AF_NETLINK
wurden hier mit EPERM abgewiesen; Berechtigungen wurden nicht umgangen. Weder
Socket-/Kernelbetrieb noch echte Tunnel, systemd-Rechte oder Leak-Schutz sind aus
Unit-/Backend-Tests abzuleiten. Ein gesonderter privilegierter CI-Job ist
konfiguriert: Kernelmodul, äußerer isolierter Namespace und eigener Test-Namespace
prüfen Adressen, Routen, Ownership und Connect/Disconnect. Er wurde lokal nicht
ausgeführt und weist auch bei Erfolg noch keinen Handshake oder Leak-Schutz nach.

## Geprüfte Ausgangsetappe vom 15. September

### Umgesetzt

- Ein gemeinsames Repository mit `apps`, `libs`, `platform`, `content`,
  `packaging`, `third_party`, `build`, `tools` und `docs`. Keine verschachtelten
  Git-Repositories und keine zusätzlichen eBlocker-Checkouts für den Java-Build.
- Gemeinsamer Maven-Reaktor und Parent; bisher nur extern auflösbare eigene
  Bibliotheken und benötigte Forks werden aus den integrierten Quellen gebaut.
  Maven-Wrapper mit Prüfsumme, zentrale Pluginversionen und dokumentierte Befehle.
- Cling durch jUPnP 3.0.5 ersetzt, einschließlich angepasstem Lebenszyklus und
  Netzwerkschnittstellenprüfung. Java-Zielversion von 11 auf 17 angehoben.
  Netty, Logging, Testwerkzeuge und Hilfsbibliotheken aktualisiert;
  [genaue Versionsänderungen](../development/dependency-changes.md).
- Mockito wird beim JVM-Start als Agent geladen; JUnit verwendet eine konsistente
  BOM. Binäre Truststores werden beim Paketbau nicht als Text gefiltert.
  Netty-ICAP-Javadoc repariert und Fehlerunterdrückung entfernt.
- Alle 360 HTTP-Routen in neun Fachmodule überführt. `EblockerHttpsServer`
  verkleinert: 2.278 auf rund 200 Zeilen. Reihenfolge und Sicherheitsoptionen
  sind durch einen eingefrorenen Vertrag abgesichert.
- Alle 85 Bildschirm-Routen in neun Fachmodule überführt. Der zentrale
  `routeConfig.js` wurde von 1.515 auf 147 Zeilen reduziert. Gemeinsame Zustände,
  Berechtigungen, Parameter und Auflösungsfunktionen sind durch Verträge geprüft.
- Node 24.21.0, gesperrte npm-Installation, Express 5 und aktualisierte
  Frontend-Werkzeuge. Entwicklungsserver, Gulp-Abschlüsse, Testprozessfehler und
  stabile Sprachdatei-Revisionen korrigiert. Unbenutzte direkte Pakete entfernt.
- DNS-Abhängigkeiten aktualisiert. Datenrennen beim Hintergrundzählen/-loggen
  und Beenden sowie IPv6-Adressauswertung korrigiert. Redis-Client wird nicht
  mehr kopiert. Go-Tests und Builds für beide Appliance-Architekturen eingerichtet.
- HTTPS-Listener bindet mit korrekter Socket-Option und meldet Bindefehler;
  Beenden ist wiederholbar. Aliasbereinigung berücksichtigt nur exakte verwaltete
  Namen; Tests hängen nicht von der Netzwerkkarte des Build-Rechners ab.
- Zertifikatstests verwenden eine frisch erzeugte lokale CA und signierte CRL:
  gültige Zertifikate werden akzeptiert, abgelaufene und widerrufene abgelehnt.
  Produktionsprüfungen von Gültigkeit und Widerruf bleiben aktiv. Der
  Verbindungstimeout-Test prüft die gesetzte Frist ohne externe Routingannahmen;
  der Lesetimeout wird weiterhin mit einem tatsächlich verzögerten Server geprüft.
- Gemeinsam genutzten privaten Geräteschlüssel und vorgebaute Buster-Pakete aus
  dem Image-Import ausgeschlossen. Image-Playbooks verlangen explizite Pakete
  und gegebenenfalls ein zusammengehöriges individuelles Schlüssel-/Zertifikatpaar.
- CI für Java 17/25, Browser, Go-Race-Tests und Paketbau vorbereitet;
  Dependabot konfiguriert. [Code-Landkarte](../CODE_MAP.md) und Architektur
  beschreiben Einstiegspunkte und weitere große Klassen.

### Tatsächlich geprüft

Die folgenden Java-Ergebnisse stammen aus den vollständigen Modulsuiten der
Ausgangsetappe vom 15. September. Sie sind kein Ergebnis des aktuellen Quellstands
und kein gemeinsamer Root-`verify`-Lauf mit Browser-Ausführung.

| Modul | Tests einschließlich deaktivierter Tests | Fehler | Deaktiviert |
| --- | ---: | ---: | ---: |
| Kryptografie | 53 | 0 | 0 |
| Registrierung | 22 | 0 | 0 |
| Netty-ICAP | 219 | 0 | 2 |
| DateAdapterJ | 59 | 0 | 0 |
| RestExpress Common | 25 | 0 | 0 |
| RestExpress Core | 326 | 0 | 0 |
| Server | 2.063 | 0 | 6 |
| Zertifikatsdienst | 52 | 0 | 0 |
| Filterlisten | 92 | 0 | 0 |
| **Summe** | **2.911** | **0** | **8** |

Damit sind 2.903 Java-Tests bestanden; acht bereits vorhandene Tests bleiben
abgeschaltet. Darunter tatsächlich ausgeführt: Java-HTTP-Routenvertrag,
21 StaticFileController-Tests und die UPnP-Tests.

| Weitere Prüfung | Ergebnis |
| --- | --- |
| Struktur-/Quellroutenprüfung mit Python | 6 Tests bestanden |
| Frontend-Buildwerkzeuge, Entwicklungsserver und 85 Bildschirm-Routen | 13 Node-Tests bestanden |
| Frontend-Assets einschließlich Linting, Bundles, Templates und Lizenzen | Erfolgreich mit Node 24.21.0 |
| Beide Go-DNS-Projekte | `go test -race ./...` bestanden |
| Beide DNS-Binärdateien | Für Linux amd64 und arm64 kompiliert; ELF-Architektur geprüft |
| Maven-Wrapper | Maven 3.9.16 erfolgreich geladen und gestartet |
| Netty-ICAP-Javadoc | Erfolgreich mit `failOnError=true`; bestehende Dokumentationswarnungen bleiben |
| Server-Debian-Paket | Erfolgreich erzeugt; Metadaten und unveränderte Truststore-Bytes geprüft |
| Zertifikatsdienst, Basiskonfiguration, Filterlisten | `verify` erfolgreich, Debian-Pakete erzeugt |
| Bootstrap | Profil `amd64-bookworm` erfolgreich paketiert |
| UI-Debian-Paket | Maven-Pipeline mit Node-Installation, `npm ci`, Asset-Build und Paketierung erfolgreich; Browser-Tests ausdrücklich übersprungen |

Java wurde lokal mit einem vollständigen Temurin JDK 17 gebaut. Nach dem
vollständigen Server-Testlauf wurde die Truststore-Ressourcenbehandlung korrigiert
und der Paketbau mit `-DskipTests` wiederholt. Die übrigen Java-Komponenten wurden
anschließend einschließlich Tests mit `verify` geprüft. Umgebungsbezogene
Proxy-Einstellungen und Toolchain-Downloads gehören nicht zum Repository.

## Offene Produkt- und Freigabegrenzen

**Die neue Appliance ist noch nicht fertig und nicht für ein Upgrade freigegeben.**

| Ziel aus dem Gesamtplan | Aktueller Umfang | Noch erforderlich |
| --- | --- | --- |
| Ein Repository | Quellen, gemeinsame Java-Builds, React-Konsole, Go-Komponenten und Paketskripte integriert | Vollständiger vereinheitlichter Appliance-/Releaseprozess |
| Modernes mehrsprachiges UI | Vierzehn React-Bereiche, Familienverwaltung, Backup/Diagnose, Netzwerk-/DNS-Editoren und Erhalt aller 85 Settings-Zustände | Vollständige Funktions-/Browserparität aller fünf alten Webanwendungen und übrige Legacy-Abläufe |
| Native WireGuard-Integration | Parser, Control-UI/Java-Brücke, Journal/Lifecycle und native Split-/Full-Tunnel-Policy mit attestierter Guard | Reale Apply-/Recovery-/Handshake-/Reconnect-/Leak-Prüfungen, Resolververwaltung und zusätzliche Topologien |
| Typisierter Netzwerk-Agent | Lesender Dienst und getrennte, ausdrücklich aktivierte Control-Schicht mit Unix-Peerprüfung | Systemdienst-/Appliance-Nachweis, Protobuf-Entscheidung und vollständige Netzwerktransaktionen |
| Persistenzmodernisierung | Gegliederte Redis-Verantwortungen; begrenzte Offline-RDB-Konvertierung und SQLite-Migration mit Audit/Roundtrip | Konsistente Live-Quellengewinnung, Runtime-Parität aller Verbraucher, Cutover/Rückweg und Redis-/Ruby-Ablösung |
| Authentifizierung | Argon2id/PIN-CAS-Migration, Administrator-JWT-Epoche und Cookie-/CSRF-Sitzungen mit serverseitigem Logout | Vollständige globale Sitzungsverwaltung und Appliance-/Browsernachweis |
| Plattformen und Updates | Cross-Compilation/Pakete für amd64 und arm64; React-Updateanforderungen | Installation, Boot, echtes arm64, Systemdienstrechte, Hardwaretests und transaktionales Appliance-Upgrade/Rollback |

Reale Browser-, visuelle und axe-Prüfungen fehlen; Unit-/Asset-Builds ersetzen sie
nicht. Ebenso fehlen reale AF_UNIX-/AF_NETLINK-Nachweise dieser Umgebung und ein
bestätigter Appliancebetrieb. Ein entfernter grüner CI-Lauf und JDK-25-Ausführung
werden nicht aus der vorhandenen Workflow-Konfiguration abgeleitet.

Weitere große Java-Dienste, RestExpress, DateAdapterJ, ältere Redis-/Ruby-Komponenten,
Squid und Teile der AngularJS-Buildkette bleiben bestehen. Nicht jede Abhängigkeit
ist auf ihrer neuesten Hauptversion. Die bisherige [Funktionsinventur](FEATURE_PARITY.md)
und historischen Baseline-CSV-Dateien bleiben als Vergleichsgrundlage erhalten.
