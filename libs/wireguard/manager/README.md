# Journalisierter WireGuard-Lifecycle

Der Linux-Manager koordiniert einen ausdrücklich übergebenen nativen Backend-Treiber.
Er enthält keine Shellbefehle, keinen produktiven Fake-Treiber und keine implizite
Aktivierung. Ohne Backend und ohne ausdrücklichen `Connect` erfolgt keine
Netzwerkänderung. Der vorhandene OpenVPN-/Tor-Betrieb gehört nicht zu diesem
Manager und wird nicht migriert oder ersetzt.

## API und Reihenfolge

```go
lifecycle, err := manager.New(privateStateDirectory, nativeBackend)
if err != nil { return err }
defer lifecycle.Close()
if err := lifecycle.Recover(ctx); err != nil { return err }

status, err := lifecycle.Connect(ctx, "home", configurationBytes)
clear(configurationBytes)
// lifecycle.Status(ctx, "home") liest die tatsächliche Backend-Beobachtung.
// lifecycle.Cancel("home") signalisiert einem laufenden Connect Abbruch.
// lifecycle.Disconnect(ctx, "home") entfernt ausschließlich dieses eigene Target.
```

`New` sperrt das private Zustandsverzeichnis exklusiv mit `flock`. `Recover` muss
vor dem ersten `Connect` erfolgreich sein. Profil-IDs entsprechen
`[a-z][a-z0-9-]{0,31}`. Pro Verbindung werden mit `crypto/rand` eine eigene
Ownership-ID und ein Interface-Name `ebwg…` erzeugt. Eine aktive Profil-ID kann
mit identischer Konfiguration wiederholt angefragt werden: Der Manager beobachtet
den vorhandenen Zustand und führt kein zweites Apply aus. Vor einer Änderung
der Konfiguration muss das Profil getrennt werden.

`Prepare` muss sämtliche nicht sicher unterstützten Semantiken ohne Mutation
abweisen. Danach schreibt der Manager die private Konfiguration und ein
versioniertes Journal atomar über temporäre Dateien, `fsync`, Rename und
Verzeichnis-`fsync`. Auch die Verzeichnisvorfahren werden beim Öffnen synchronisiert,
damit neue Zustandsverzeichnisse vor Netzwerkänderungen dauerhaft angelegt sind.
Erst ein dauerhaftes `applying`-Journal erlaubt den Aufruf von `Apply`.

`active` wird erst nach Beobachtung eines vorhandenen, eigenen, eingeschalteten
Interfaces mit genau den erwarteten Peer-Public-Keys gespeichert. Dieser Zustand
ist **kein erfolgreicher Handshake und keine nachgewiesene Erreichbarkeit**.
Bei Full-Tunnel-Profilen ist zusätzlich die frische vollständige Routing-/Firewall-
Attestation notwendig; erst dann ist das separate Laufzeitfeld `killSwitchActive`
wahr. Die öffentliche Importplanung behält `applied=false` und
`killSwitchActive=false`; Laufzeitbeobachtungen stehen getrennt in `observation`.

## Zustände und Fehlerfälle

| Zustand | Bedeutung |
| --- | --- |
| `prepared` | Unterstützte Konfiguration und Target sind dauerhaft dokumentiert. |
| `applying` | Mutation ist gestartet oder könnte vor einem Absturz gestartet worden sein. |
| `active` | Eigenes Interface und erwartete Peers wurden beobachtet. |
| `removing` | Eine Trennung kann teilweise erfolgt sein. |
| `rolling_back` | Aufräumen einer unvollständigen Operation. |
| `disconnected` | Eigene Ressourcen wurden entfernt; private Konfigurationsdatei ist gelöscht. |
| `failed` / `cancelled` | Operation fehlgeschlagen/abgebrochen; notwendiger Rollback abgeschlossen. |
| `recovery_required` | Aufräumen ist unvollständig oder beobachtete Konfiguration stimmt nicht. |

Schlägt Apply, Beobachtung oder das abschließende Journal fehl, folgt Rollback.
Für den Rollback eines abgebrochenen Connect wird ein unabhängiger Kontext mit
fünf Sekunden Frist verwendet. Ein Fehler dabei bleibt dauerhaft als
`recovery_required` sichtbar; weitere Verbindungen werden bis erfolgreichem
`Recover` abgewiesen. Fehlgeschlagene Trennungen können erneut angefragt werden.
Eine erfolgreiche Wiederholung gibt neue Verbindungen wieder frei, sofern keine
anderen unvollständigen Operationen verbleiben und die Startup-Recovery erfolgt ist.
Eine wiederholte bereits abgeschlossene Trennung ändert nichts.

Nach einem Prozessabbruch bereinigt `Recover` Zwischenzustände anhand des
journalisierten Targets. Ein dauerhaft aktives Interface wird nur erhalten, wenn
Ownership, Linkzustand und erwartete Peer-Public-Keys weiterhin stimmen. Bei
Full-Tunnel-Profilen müssen auch sämtliche Schutzressourcen erneut attestiert sein. Der
Manager führt beim Wiederanlauf kein automatisches Neu-Apply aus. Beschädigte oder
unbekannt versionierte Journale verhindern die Freigabe neuer Verbindungen.
Auch terminale Journale werden auf verbliebene private Konfigurationen geprüft:
Stürzt ein erneuter Verbindungsversuch nach dem Config-Write, aber vor dem neuen
`prepared`-Journal ab, entfernt Recovery diese Schlüsseldatei ohne Netzwerkänderung.

## Backend-Vertrag und Grenzen

`Backend` implementiert `Prepare`, `Apply`, `Observe`, `Rollback` und `Remove`.
Der Linux-Treiber muss Name **und** persistente Ownership-ID vor jeder Löschung
prüfen, die exklusive Erstellung sicherstellen und unbekannte Ressourcen ablehnen.
Dieser Manager kann die Korrektheit eines beliebigen injizierten Treibers nicht
erzwingen. `ErrUnsupportedProfile`, `ErrOwnershipMismatch` und `ErrUnavailable`
werden in feste öffentliche Fehlermeldungen umgesetzt; Treiberfehlertexte mit
möglichen Geheimnissen werden nicht weitergereicht oder geloggt.

Die Runtime-Schlüssel sind nur über `Profile.WithRuntime` für die Dauer eines
vertrauenswürdigen Callbacks zugänglich. `RuntimeProfile` ist bei JSON und `fmt`
redigiert. Der Snapshot wird auch bei Fehler/Panic gelöscht; vom Backend explizit
angeforderte Schlüsselkopien muss dieses selbst zeitnah löschen.

Das Zustandsverzeichnis muss dem Prozess gehören und Modus `0700` haben; Dateien
haben `0600`. Symlinks, Hardlinks, fremde Besitzer und unsichere Vorfahren werden
abgewiesen. Unter einem vertrauenswürdigen Sticky-Verzeichnis wie `/tmp` ist ein
privates eigenes Unterverzeichnis für Tests erlaubt. Verwaiste private temporäre
Dateien werden bei Recovery entfernt. Konfigurationen liegen absichtlich als
zugriffsbeschränkte Dateien vor, **nicht verschlüsselt**. Go, Backups, Journaling-
Dateisysteme und SSDs bieten hier keine garantierte physische Schlüssellöschung.

Full-Tunnel-Profile werden ausschließlich über das zusätzliche Interface
`FullTunnelBackend` verarbeitet: `PrepareFullTunnel`, `ApplyFullTunnel`,
`ObserveFullTunnel`, `RollbackFullTunnel`, `RemoveFullTunnel`. Ein reiner Split-
Backend erhält niemals einen Full-Tunnel-Apply. Der vollständige öffentliche
[`policy.Plan`](../policy/README.md) wird vor jeder Mutation journalisiert und bei
Recovery/Remove unverändert verwendet. Der Backend-Vertrag verlangt Firewall
zuerst, dann Interface/Policy-Routing; bei Bereinigung wird die Firewall zuletzt
entfernt. Ohne geeigneten Backend bleibt die Recovery gesperrt.

Die Attestation bindet Ownership und Plandigest an tatsächlich ausgelesene
Firewallregeln, Routingtabellen, beide Policy-Rule-Paare, Socket-Mark und
Endpoint-Ausnahmen. Fehlende Teilprüfungen verhindern den Aktivzustand. Historische
Journalbeobachtungen dürfen keinen aktuellen Kill-Switch-Nachweis liefern; bei
fehlgeschlagenem Status-Readback ist das Schutzfeld stets falsch. DNS-Profile
werden mangels Resolver-Verwaltung weiterhin vor Mutation abgelehnt. Der native
Backend muss alle weiteren nicht unterstützten Topologien vor Apply abweisen.
Diese Kontrolle schützt nicht gegen gleichberechtigte Administratoren oder
Kerneländerungen unmittelbar nach einer Beobachtung.

## Verifikation

`go test -race ./...` verwendet reale temporäre Dateien, jedoch ausschließlich
typisierte Test-Backends. Tests prüfen Journal-vor-Mutation, partielle Applyfehler,
Abbruch, unabhängigen Rollback-Kontext, simulierten Prozessabbruch mit Wiederöffnen,
Rollback-/Remove-Retries, Fremdressourcen, Peer-Drift, beschädigte Journale,
Dateirechte, Symlinks, exklusive Prozesssperre und Secret-Redaktion. Die Full-Tunnel-
Tests prüfen zusätzlich Policy-vor-Mutation, Pflichtattestation, historische
Schutzclaims, Guard-Recovery, unvollständige Entfernung, fehlende Backend-Fähigkeit
und manipulierte Policy-Journale.

Diese Tests ersetzen keine Linux-Appliance-Tests für native UAPI-Aufrufe,
Kernel-Routing, Tunnelhandshakes, echte Stromausfälle oder DNS-/IPv6-Leaks.

## Dauerhaft importierte Profile

`ImportProfile`, `Profiles`, `Profile`, `ConnectProfile` und `DeleteProfile`
ergänzen den expliziten Verwaltungsvertrag. Import speichert die validierte
Quelle separat als private `.profile`-Datei, ohne Netzwerkänderung. Diese Quelle
überlebt Recovery und Disconnect bis zum ausdrücklichen Delete; die bisherige
kurzlebige `.conf` wird weiterhin nach Disconnect/Rollback gelöscht. Aktive oder
unvollständige Profile können weder ersetzt noch gelöscht werden.

Der Katalog enthält höchstens 64 Profile und 448 KiB öffentliche Metadaten.
Listen geben ausschließlich öffentliche Pläne und historische Phasen aus, keine
Schlüssel oder aktuellen Schutzclaims. `ConnectProfile` liest und aktiviert die
Quelle unter derselben Managersperre, damit ein paralleler Import keine andere
Konfiguration unterschieben kann.
