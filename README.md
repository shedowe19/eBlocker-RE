# eBlocker – gemeinsames Repository

Server, Weboberfläche, DNS, Systemwerkzeuge und Paketbau liegen in einem Checkout.
Die Quellbasis stammt aus `feature/debian_bookworm`; die importierten Komponenten
und ihre ursprünglichen Commit-IDs stehen in [sources.lock.json](sources.lock.json).
Zusätzliche eBlocker-Checkouts und GitHub-Paketzugangsdaten sind für den lokalen
Java-Build nicht erforderlich.

## Orientierung

| Verzeichnis | Inhalt |
| --- | --- |
| `apps/server` | Java-Anwendung: HTTP, ICAP, Geräte, Filter, VPN, Verwaltung |
| `apps/web` | Bestehende Webanwendungen und noch nicht migrierte Funktionen |
| `apps/console` | React-Verwaltung mit vierzehn Bereichen und Anmeldung (DE/EN) |
| `apps/network-agent` | Lesender Netzwerkdienst, getrennte WireGuard-Control-Schicht und native CLI |
| `apps/certificate-validator` | Zertifikatsprüfung für Squid |
| `libs` | Kryptografie, Registrierung, WireGuard-Parser/-Policy/-Lifecycle und Offline-RDB-/SQLite-Migration |
| `platform/dns` | CoreDNS mit eBlocker-Plugins und dynamisches DNS |
| `platform/native` | Netzwerkwerkzeuge sowie bisherige Ruby-Dienste |
| `platform/baseconfig` | Betriebssystemkonfiguration |
| `content` | Filterlisten und Anwendungskompatibilität |
| `packaging` | Bootstrap und Image-Bau |
| `third_party` | Benötigte angepasste Fremdquellen mit Lizenz/Herkunft |
| `build/parent` | Gemeinsame Java-Abhängigkeiten und Build-Regeln |
| `tools` | Entwicklungswerkzeuge und Umgebungssimulator |
| `docs` | Architektur, Code-Landkarte, Prüfstatus und Migration |

Zum Finden einer Funktion: [Code-Landkarte](docs/CODE_MAP.md).
Technische Zusammenhänge: [Architektur](docs/ARCHITECTURE.md).
Erledigt/offen und tatsächlich ausgeführte Tests: [Prüfstatus](docs/refactor/PROGRESS.md).

## Entwickeln

Voraussetzungen: vollständiges JDK 17+, Python 3.10+, für die Oberfläche Node
**24.21.0** und Chrome/Chromium, für DNS Go **1.27.1**. Der Maven-Wrapper lädt
**3.9.16** mit festgelegter SHA-256-Prüfsumme. Internet wird zum erstmaligen
Herunterladen öffentlicher Bibliotheken benötigt.

Vom Repository-Hauptverzeichnis:

```sh
make check              # Struktur, HTTP-Routenverträge und Erhalt alter UI-Funktionen prüfen
make test               # Java-Anwendungen samt lokaler Bibliotheken testen
make console            # Neue React-Oberfläche testen und bauen
make web                # npm ci, UI bauen und Browser-Tests ausführen
make dns                # Beide Go-DNS-Anwendungen testen
make persistence        # SQLite-Migrations-CLI testen und paketieren
make wireguard          # WireGuard-Parser und Lifecycle mit Race-Prüfung testen
make network-agent      # Agent und nativen Backend-Vertrag prüfen
make verify             # Maven-Reaktor prüfen und Debian-Pakete erzeugen
```

Ohne Make: `python3 scripts/workspace.py check|test|web|verify`.
Mit Proxy oder privatem Mirror: `python3 scripts/workspace.py test --settings /pfad/settings.xml`.
`CHROME_BIN` kann auf ein lokal installiertes Chromium zeigen.

Frontend-Entwicklung: in `apps/web` mit `npm ci` installieren und
`npx gulp serve-dev` starten. `npm run test:build-tools` prüft Build-Lebenszyklus,
Entwicklungsserver und alle 85 Bildschirm-Routen ohne Browser.
`npm run build:assets` baut ausschließlich Assets und ersetzt keinen Testlauf.

`make verify` baut die Maven-Debian-Pakete. Ein bootfähiges Appliance-Image umfasst
zusätzlich native Pakete, Systemdienste und Hardwaretests; der historische
Image-Bau unter `packaging/images` ist noch nicht als neuer Releaseprozess freigegeben.

## Stand der Modernisierung

Cling ist durch jUPnP ersetzt. Maven, Node, Testwerkzeuge, Netty, gemeinsame
Hilfsbibliotheken, Express und DNS-Abhängigkeiten wurden aktualisiert. HTTP- und
Bildschirm-Routen sind nach Funktionen gegliedert und durch eingefrorene Verträge
abgesichert. Die bestehende Java-11-Zielversion wurde auf Java 17 angehoben,
passend zur Bookworm-Basis; CI enthält zusätzlich Prüfungen für die Ausführung mit JDK 25.

Die neue React-/TypeScript-Oberfläche auf `/next/` umfasst vierzehn Bereiche:
Geräte, Schutz, HTTPS, Netzwerk, DNS, WireGuard, Zugangsschutz, System, Familie,
VPN/Tor, Updates, Sicherung, Diagnose und Funktionskatalog. Der Katalog ordnet alle
85 bestehenden Settings-Zustände genau zu. Statische Prüfungen sichern Links,
Komponenten, Templates und die fünf bestehenden Webanwendungen; vollständige
Laufzeitparität ist damit noch nicht nachgewiesen.
[Bedienung und genaue Migrationsgrenzen](docs/development/console-migration.md).

Gezielte Schreiboperationen umfassen Geräte-, Netzwerk- und DNS-Einstellungen,
Benutzer-/Familienprofilverwaltung, Gerätezuordnungen, bestehende OpenVPN-/Tor-
Zuordnungen und Updateanforderungen. Sicherung unterstützt den vorhandenen
Export-/Upload-/Prüf-/Wiederherstellungspfad; Diagnose erzeugt und lädt lokale
Berichte. Netzwerkänderungen können die Verwaltungsverbindung unterbrechen.

Die Konsole verwendet HTTPS-Cookie-Sitzungen mit HttpOnly, Secure, SameSite=Strict,
Origin-/CSRF-Prüfung und serverseitigem Logout der aktuellen Sitzungsfamilie.
HTTP-Entwicklung nutzt weiterhin ausschließlich im Speicher gehaltene Bearer-Tokens.
Neue Passwörter und PINs verwenden Argon2id; erfolgreich geprüfte alte PINs werden
mit einem atomaren Vergleich des gespeicherten Werts aktualisiert. Benutzer-CAS
und versionierte Benutzer-/Gerätecache-Veröffentlichung verhindern konkrete
veraltete Überschreibungen. Das ist keine umfassende Redis-/Netzwerktransaktion.

Der [lesende Go-Netzwerkagent](apps/network-agent/README.md) behält seine getrennte
Java-Brücke. Zusätzlich gibt es einen standardmäßig deaktivierten
[Control-Dienst](apps/network-agent/internal/controlapi/README.md) für private
Profilimporte, Status, Verbinden, Trennen, Abbruch und Löschen. Nur dieser eigene
Dienst erhält nach ausdrücklicher Aktivierung CAP_NET_ADMIN; sein privater
Unix-Socket prüft die Java-Prozessidentität über SO_PEERCRED. Die native CLI bleibt
vorhanden. Split- und Full-Tunnel verwenden einen
[journalisierten Lifecycle](libs/wireguard/manager/README.md); Full-Tunnel ergänzt
Policy-Routing für beide IP-Familien und eine eigene atomare nftables-Firewall.
Eine Schutzanzeige verlangt vollständiges natives Readback. DNS-Verwaltung und
dynamische Endpoints bleiben abgewiesen; echte Tunnel-/Leak-/Appliance-Nachweise
stehen aus. Bestehendes OpenVPN/Tor bleibt erhalten.

Die [SQLite-Migrationsbibliothek und CLI](libs/persistence/README.md) konvertiert
inzwischen unveränderliche Offline-RDB-Dateien innerhalb eines ausdrücklich
begrenzten Formats und importiert/exportiert versionierte logische Snapshots mit
SQL-Transaktionen, Binärdaten, unveränderten Ablaufzeiten und Herkunftsdaten.
Laufzeitumstellung, Pub/Sub-Ersatz, kontrollierter Rückweg und Redis-Abschaltung
sind weiterhin offen.

AngularJS bleibt für noch nicht migrierte Funktionen erforderlich. Browser-,
Installations-, Hardware- und Upgrade-Nachweise müssen vor einem produktiven
Wechsel vorliegen. Bestehende Redis-/Ruby-/Squid-Pfade bleiben aktiv; die neue
Updateoberfläche ist keine transaktionale Appliance-Upgrade-Lösung.

## Herkunft und Lizenzen

Die Lizenzdateien bleiben bei den jeweiligen Komponenten erhalten. `third_party`
ist bewusst getrennt, damit eigene Produktlogik und benötigte Forks erkennbar sind.
Importierte alte CI-Konfigurationen, Paket-Zugangsdaten, ein gemeinsamer privater
Geräteschlüssel und vorgebaute Bootstrap-Pakete sind nicht Teil des neuen Builds.
Historische Dokumentation steht unter `docs/upstream` und
`docs/development/legacy-setup.md`; dort genannte alte Pfade/Befehle sind kein
aktueller Build-Einstieg.

Bootstrap gezielt bauen (ohne Installation auf dem Build-Host):
`./mvnw -Pbootstrap,amd64-bookworm -pl packaging/bootstrap -am package`.
Für Raspberry Pi das Profil `raspios-bookworm` verwenden.
