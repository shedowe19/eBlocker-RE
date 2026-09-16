# ADR 0002: Integrierte Quellen und reproduzierbarer Build

Status: umgesetzt; vollständige Appliance-Freigabe ausstehend.

## Problem

Die Java-Anwendung war von separat veröffentlichten eBlocker-Artefakten und
Cling 2.1.2 aus einem nicht erreichbaren Repository abhängig. UI-Build und CI
verwendeten widersprüchliche Werkzeugversionen. Weitere Produktteile lagen
in separaten Repositories und waren vom zentralen Code-Einstieg aus schwer auffindbar.

## Entscheidung

- Alle 22 identifizierten Begleitquellen sind im gleichen Git-Repository eingebunden.
  Der bisherige `eblocker-top` wird im lokalen Maven-Parent zusammengeführt.
- `sources.lock.json` dokumentiert Herkunft und ursprünglichen Commit. Es ist
  kein Build-Zeitpunkt-Clone-Auftrag und behauptet keine Bytegleichheit nach lokalen Patches.
- Eigene Anwendungen liegen in `apps`, gemeinsame Bibliotheken in `libs`,
  Systemteile in `platform`, Inhalte in `content`, Paketbau in `packaging` und
  benötigte Forks in `third_party`. Es gibt keine verschachtelten Git-Checkouts.
- Maven-Reaktor und Wrapper lösen die lokalen Java-Projektabhängigkeiten auf.
  Der Filterlisten-Compiler baut gegen den Server in diesem Repository.
- Cling wird durch jUPnP 3.0.5 von Maven Central ersetzt. Der Dienst wird ausdrücklich
  gestartet und beim Prozessende beendet. Interface-Auswahl bleibt kontrolliert.
- Java-Bytecode zielt auf 17 (Bookworm); CI enthält zusätzlich JDK 25.
  Die Debian-Abhängigkeit erzwingt nun eine ausreichend neue JVM.
- Mockito wird beim JVM-Start als Test-Agent geladen. JUnit BOM, Vintage und
  Launcher werden gemeinsam versioniert; JUnit-4-Tests bleiben ausführbar.
- `npm ci` verwendet den aktualisierten Lockfile, Node 24.21.0 und den korrigierten
  Browser-Test-Lebenszyklus. Browserfehler bleiben echte Build-Fehler.
- Go-DNS verwendet aktualisierte CoreDNS-/Redis-/DNS-Abhängigkeiten. Race-Tests
  und Linux-Builds für amd64/arm64 werden in CI geprüft.
- Veröffentlichungs-Signierung der Bibliotheks-Forks liegt im expliziten Profil
  `signed-release`, nicht im normalen lokalen `verify`.

## Grenzen

Dies entfernt keine bestehenden Produktfunktionen. Java-Klassen bleiben getrennte
fachliche Einheiten; der Zweck ist verständliche Zuständigkeit, nicht eine möglichst
kleine Dateizahl. AngularJS, alte Ruby-Dienste, Jedis/Redis-Persistenz und große
Java-Dienste brauchen weiterhin eigenständige, durch Paritätstests belegte Migrationen.
Das Quellen-Monorepo ersetzt keinen Hardware-, Installations- oder Upgrade-Test.

## Quellen

- [jUPnP Einführung](https://www.jupnp.org/docs/introduction/)
- [jUPnP Releases](https://github.com/jupnp/jupnp/releases)
- [Maven Distribution](https://maven.apache.org/download.cgi)
- [Express 5 Migration](https://expressjs.com/en/guide/migrating-5/)
- [JSpecify](https://jspecify.dev/docs/user-guide/)
- [Go Distribution](https://go.dev/dl/)
