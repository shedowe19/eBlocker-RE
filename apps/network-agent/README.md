# eBlocker Network Agent

Der Go-Dienst liest den echten Linux-Netzwerkzustand und prüft WireGuard-Profile.
Dieser standardmäßig installierte Dienst startet keine Shell und verändert kein
Interface, keine Route und keine Firewall. Das getrennte Administratorwerkzeug
`eblocker-wireguard` kann ausdrücklich freigegebene, eigene Split- und Full-Tunnel verwalten;
sein Vertrag und seine Grenzen stehen in [NATIVE_RUNTIME.md](NATIVE_RUNTIME.md).
Seine Produktion verwendet ausschließlich `network.LinuxProvider`; der Fake-Provider
existiert nur in Tests. Das Modul gehört in dieses Monorepository und verwendet
`../../libs/wireguard` als lokale, typisierte Parserbibliothek.

## Betrieb und Grenzen

- Linux amd64 und arm64, Go 1.27.1; native Netlink-Abhängigkeiten sind in
  `go.mod`/`go.sum` festgelegt. Der HTTP-Dienst nutzt weiter den read-only Provider.
- HTTP/JSON v1 ausschließlich über `/run/eblocker/network-agent.sock`, kein TCP-Port.
- Socket `0660`, Runtime-Verzeichnis `0750`, eigenes Systemkonto ohne Linux-Capabilities.
- Das Debian-Paket ergänzt `SupplementaryGroups=eblocker-network-agent` für `icapserver.service`
  (bestehender Serverbenutzer: `icapd`). Der Server benötigt nach Installation einen Neustart.
- Der Agent beobachtet seinen aktuellen Netzwerk-Namespace. Kein Namespace-Wechsel,
  `modprobe`, `wg`, `ip`, `nft`, `wg-quick` oder Ausführen importierter Hooks.
- WireGuard-Erkennung liest die registrierten Generic-Netlink-Familien als Dump. Ein
  nicht registriertes Modul wird **nicht** automatisch geladen und nicht als prinzipiell
  vom Kernel „nicht unterstützt“ bezeichnet. `management` bleibt immer `false`.
- Route-Dumps enthalten IPv4/IPv6, Tabellen, Priorität, Gateway, Source und Multipath.
  Sie simulieren keine Policy-Regelauswertung; MPLS/Encapsulation-, Metrik- und
  Nexthop-Objektdetails sind nicht Bestandteil dieses ersten Statusvertrags.
- Beobachtungen erfolgen nacheinander und sind kein atomarer Kernel-Snapshot.
  Bei Fehlern wird kein teilweiser Erfolg ausgegeben. Netlink-Dumps sind zeitlich und
  auf 4 MiB begrenzt; ein unterbrochener Dump wird als nicht verfügbar gemeldet.
- Ein validiertes WireGuard-Profil bleibt ein Plan: `applied=false`,
  `killSwitchActive=false`. Kein Schlüssel wird gespeichert, kein Tunnel gestartet,
  keine Erreichbarkeit und kein Schutz gegen DNS-/IPv6-Leaks behauptet.

Der finale Architekturplan sieht transaktionale Änderungen, Rollback und Protobuf
vor. Die HTTP-API bietet weiterhin keine Schreiboperationen; HTTP/JSON ist
die explizite lokale Beobachtungsschnittstelle während der Migration. Die separate
CLI besitzt einen Lifecycle mit Journal, kompensierendem Rollback und einer vorab
atomar gesetzten Firewall für unterstützte Full-Tunnel. DNS-Umschaltung bleibt
ausgeschlossen. Der getrennte `eblocker-network-control` verwendet einen eigenen
Unix-Socket mit `SO_PEERCRED` und bleibt ohne explizite Freigabe deaktiviert.

## API

Alle Antworten haben `Content-Type: application/json`, `Cache-Control: no-store`,
eine explizite `Content-Length` und `schemaVersion: 1`.

| Methode | Pfad | Inhalt von `data` |
| --- | --- | --- |
| GET | `/v1/health` | Prozessstatus `{status:"ready", version:"v1"}` |
| GET | `/v1/status` | `{readOnly, capabilities, interfaces, routes}` |
| GET | `/v1/capabilities` | `{readOnly, operations, wireguard, limitations}` |
| GET | `/v1/interfaces` | Interfaces mit IPv4-/IPv6-Präfixen und Flags |
| GET | `/v1/routes` | IPv4-/IPv6-Routen mit Interface-Indizes und Multipath |
| POST | `/v1/wireguard/validate` | Öffentlicher Plan aus `libs/wireguard` |

Erfolg: `{"schemaVersion":1,"data":...}`.
Fehler: `{"schemaVersion":1,"error":{"code":"...","message":"..."}}`.
GET-Anfragen akzeptieren keine Query-Parameter und keinen Body.

Validierung akzeptiert genau `{"config":"[Interface]\\n..."}` als JSON. Zusätzliche,
doppelte, anders geschriebene Schlüssel, mehrere JSON-Dokumente, `null` und
komprimierte Bodies werden abgewiesen. Decodierte Profile sind auf 64 KiB begrenzt;
der HTTP-Body auf `6*64 KiB+32 Bytes`, um JSON-Escaping zu berücksichtigen.
Private/Preshared Keys und Parser-Eingaben werden nicht zurückgegeben oder geloggt.
Die zurückgegebene Peer-Public-Key-Information ist öffentliches Konfigurationsmaterial.
Go garantiert keine vollständige Löschung temporärer Speicherkopien; dieser Prozess
ist kein kryptografisch geschützter Secret-Vault.

Wichtige Fehler: `invalid_json` (400), `invalid_request` (400),
`method_not_allowed` (405), `request_too_large`/`profile_too_large` (413),
`invalid_content_type`/`invalid_content_encoding` (415),
`invalid_wireguard_profile` (422), `busy`/`observation_unavailable` (503),
`observation_timeout` (504). Fehlertexte sind fest und enthalten keine Eingaben.

```sh
curl --unix-socket /run/eblocker/network-agent.sock http://agent/v1/status
# Datei enthält {"config":"..."}; Keys nicht als Kommandozeilenargument übergeben.
curl --unix-socket /run/eblocker/network-agent.sock \
  -H 'Content-Type: application/json' --data-binary @request.json \
  http://agent/v1/wireguard/validate
```

## Bauen und prüfen

```sh
go test -race ./...
go vet ./...
# Paketbau benötigt außerdem Python 3 und dpkg-deb.
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build ./cmd/eblocker-network-agent
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build ./cmd/eblocker-network-agent
./scripts/build-deb.sh amd64
./scripts/build-deb.sh arm64
```

Die Integrationstests verlangen absichtlich eine explizite Aktivierung. In einer
Linux-VM beziehungsweise CI-Umgebung mit AF_UNIX und AF_NETLINK:

```sh
EBLOCKER_AGENT_INTEGRATION=1 go test -race ./...
```

Die Entwicklungsumgebung hat beide Socket-Familien mit `EPERM` abgewiesen.
Daher sind die echten Kernel-/Socket-/Shutdown-Tests hier **nicht bestanden**;
sie werden ohne obige Variable sichtbar übersprungen. API-Verträge, Fehler- und
Secret-Redaktion, strikte JSON-Grenzen, IPv4/IPv6-Netlink-Parsing, Multipath und
Pfadablehnung sind ohne privilegierte Hostoperationen prüfbar. Ein lokaler Build
ist kein Nachweis für Installation, systemd-Härtung oder Betrieb auf einer Appliance.

Das Paket enthält Beobachtungsdienst, Administrator-CLI, getrennten Control-Dienst
und Lizenzhinweise der eingebundenen
Go-Abhängigkeiten unter `usr/share/doc/eblocker-network-agent/third-party/`.
Es wird nur gebaut, nicht installiert oder aktiviert. Für eine kontrollierte
Installation sind Paketinstallation, `systemctl enable --now eblocker-network-agent`
und ein Serverneustart separate Deployment-Schritte. Keine automatische Migration
der bisherigen Netzwerksteuerung findet statt.

## Socket-Lebenszyklus

`--socket` akzeptiert nur einen absoluten, normalisierten Pfad in einem vorhandenen,
vertrauenswürdig besessenen Verzeichnis ohne Gruppen-/Weltschreibrecht. Symlink-
Verzeichnisse und bereits vorhandene Dateien/Sockets werden abgewiesen. Auch ein
alter Socket wird nie automatisch überschrieben. Vor manueller Entfernung muss
der Betreiber prüfen, dass kein aktiver Dienst mehr gehört. Beim normalen Stop
entfernt der Agent nur den von ihm angelegten Socket-Inode. systemd räumt sein
Runtime-Verzeichnis bei Service-Ende auf; `/run/eblocker` ist derzeit diesem Paket
zugeordnet und darf nicht ohne Anpassung mit weiteren Runtime-Daten geteilt werden.

SIGINT/SIGTERM beendet HTTP mit bis zu fünf Sekunden Graceful-Shutdown. HTTP besitzt
Header-/Read-/Write-/Idle-Timeouts, 16 KiB Headerlimit und 32 gleichzeitig bearbeitete
Anfragen. Für spätere Schreiboperationen sind gesonderte Ownership-, Autorisierungs-,
Desired-State-, Journal-, Rollback- und Appliance-Leaktests notwendig.
