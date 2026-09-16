# Lokale WireGuard-Verwaltung

`eblocker-network-control` ist ein eigener, ausdrücklich aktivierter Schreibdienst.
Der vorhandene `eblocker-network-agent` bleibt unverändert lesend und ohne
Netzwerk-Schreibrechte. Der Control-Dienst wird beim Paketinstallieren nicht
gestartet oder aktiviert. Seine vorbereitete systemd-Unit verlangt zusätzlich
`/etc/eblocker/network-control-enabled` und das Startargument
`--enable-native-writes`.

Der Dienst läuft als eigener Benutzer und eigene Gruppe
`eblocker-network-control`, ausschließlich mit `CAP_NET_ADMIN`. Das Java-Backend
läuft als `icapd`; seine zusätzliche Gruppe `eblocker-network-control` ermöglicht
den Zugriff auf `/run/eblocker-network-control/control.sock`. Der Socket hat Modus
`0660`, sein eigenes Verzeichnis `0750`, beide gehören dem Control-Dienst. Jede
Verbindung muss außerdem über `SO_PEERCRED` exakt die konfigurierte UID und
**primäre** GID von `icapd` nachweisen. Die zusätzliche Socketgruppe ersetzt diese
Identitätsprüfung nicht. HTTP-Header können keine Identität festlegen.

Vorhandene Socketpfade werden niemals automatisch entfernt; auch ein vermeintlich
verwaister Socket erfordert eine ausdrückliche Betreiberprüfung. Symlinks,
beschreibbare/unvertrauenswürdige Vorfahren und falsche Dateirechte verhindern den
Start. Nur der eigene tatsächlich erstellte Socket wird beim Beenden entfernt.

## HTTP-Vertrag

Alle Antworten verwenden `schemaVersion: 1`. Erfolg hat `data`, Fehler haben
`error: {code, message}`. Erfolgsstatus ist 200. Die vollständigen festen
Fehlercodes, HTTP-Statuswerte und Texte stehen in `errors.go` sowie der gemeinsam
genutzten Fixture `contracts/wireguard-control/v1/errors.json`.

| Methode und Pfad | Anfrage | `data` |
| --- | --- | --- |
| GET `/v1/profiles` | Kein Body | `{profiles: [{profileId, phase, plan}]}` |
| PUT `/v1/profiles/{id}` | `{schemaVersion: 1, configuration: "..."}` | `{profileId, phase, plan}` |
| GET `/v1/profiles/{id}` | Kein Body | `{profileId, phase, plan, runtime}` |
| POST `/v1/profiles/{id}/connect` | `{schemaVersion: 1}` | Derselbe Status-DTO |
| POST `/v1/profiles/{id}/disconnect` | `{schemaVersion: 1}` | Derselbe Status-DTO |
| POST `/v1/profiles/{id}/cancel` | `{schemaVersion: 1}` | `{profileId, cancellationRequested}` |
| DELETE `/v1/profiles/{id}` | Kein Body | `{profileId, deleted: true}` |

IDs entsprechen `[a-z][a-z0-9-]{0,31}`. `plan` ist der bereits bestehende öffentliche
WireGuard-Importplan. Ein nur importiertes Profil hat Phase `imported` und
`runtime: null`. Ansonsten enthält `runtime` einen frisch beobachteten
`manager.Status`. Der Listenzustand ist historisch; eine Liste bestätigt keine
aktuelle Schutzwirkung. Erfolgreiche Mutationen sollten anschließend über GET
beobachtet werden. `active` bestätigt keine Erreichbarkeit oder funktionierende
DNS-Auflösung. Nur `runtime.killSwitchActive` bestätigt die vollständig geprüfte
aktuelle eigene Full-Tunnel-Policy.

Profile sind maximal 64 KiB groß, HTTP-Bodies maximal 384 KiB und Antworten maximal
512 KiB. Doppelte, unbekannte und anders geschriebene JSON-Felder, weitere
JSON-Dokumente, Queryparameter, komprimierte Bodies und unbekannte Operationen
werden abgewiesen. Maximal 64 Profile und insgesamt 448 KiB öffentliche
Katalogdaten sind speicherbar. Leere Listen sind `[]`.

Eine Operation läuft höchstens 30 Sekunden; ein abgebrochener Connect erhält
zusätzlich den unabhängigen fünfsekündigen Manager-Rollback. `cancel` kann eine
laufende Operation unterbrechen. Alle anderen parallelen Operationen, einschließlich
GETs, erhalten `busy` statt einer unbeschränkten Warteschlange. Ein Clienttimeout
ist kein Beleg, dass eine Mutation nicht ausgeführt wurde; erst Status lesen.

## Private Profile und Grenzen

Importquellen werden absichtlich als eigene `.profile`-Dateien in der privaten
Manager-Ablage mit `0700`/`0600` gespeichert und bleiben für erneute Verbindungen
bis zum ausdrücklichen Delete erhalten. Laufende Verbindungen benutzen zusätzlich
die bisherigen kurzlebigen `.conf`-Dateien; diese verschwinden weiterhin nach
Disconnect/Rollback. Aktive oder unvollständige Profile können weder überschrieben
noch gelöscht werden. Es gibt keinen API-Aufruf zum Lesen privater Konfiguration.

Antworten und Fehler enthalten keine PrivateKeys, PresharedKeys, Konfigurationen
oder ungeprüften Treiber-/JSON-Fehlertexte. Übergebene Bytepuffer werden geleert;
Go-Strings, Dateisystem-Journale und Backups erlauben keine garantierte physische
Löschung. Die Profilablage ist zugriffsbeschränkt, nicht verschlüsselt.

DNS-Profile, dynamische Endpoints und sonstige nicht unterstützte native
Semantik scheitern vor Aktivierung; Import ist weiterhin zur Prüfung möglich.
Startup führt die ausdrücklich autorisierte Manager-Recovery durch, die
ausschließlich eigene journalisierte Ressourcen erhalten oder aufräumen darf.

Die normale Testsuite verwendet den echten Manager und reale private Dateien,
jedoch typisierte Kernel-Testtreiber und direkte HTTP-Handleraufrufe. Der getrennte
Transporttest benötigt ausdrücklich einen isolierten CI-Runner:

```sh
EBLOCKER_CONTROL_SOCKET_TEST=1 go test -tags=controlintegration ./internal/controlapi
```

Dieser Test prüft echte Unix-Socket-/Peer-Credentials ohne nativen Kernelbackend.
Er wird in eingeschränkten Entwicklungsumgebungen nicht als Umgehungsversuch
gestartet. Die gemeinsame JSON-Fixture-Suite benötigt keine Socketrechte.
