# WireGuard: nativer Import und öffentlicher Netzwerkplan

Dieses eigenständige Go-Modul importiert und validiert einen bewusst begrenzten
Teil des `wg-quick`-Formats. Es erzeugt einen prüfbaren Plan, **keine Verbindung**.
Es benötigt weder Root-Rechte noch externe Go-Abhängigkeiten. Es startet keine
Shell, liest keine Schlüsseldateien, löst keine Hostnamen auf und verändert weder
Interfaces, Routen, DNS noch Firewallregeln.

## Verwendung

```go
import "github.com/eblocker/eblocker/libs/wireguard"

profile, err := wireguard.Parse(configurationBytes)
clear(configurationBytes)
if err != nil {
    // *wireguard.ValidationError: ausschließlich feste Texte und bekannte
    // Feldnamen. Weder Schlüssel noch ungültige Eingabewerte werden wiederholt.
    return err
}
defer profile.Destroy()
plan := profile.Plan() // unabhängige Kopie, ohne PrivateKey und PresharedKey
```

`Profile` besitzt keine öffentlichen Schlüsselfelder. `String`, `GoString`, alle
`fmt`-Formatierungen und JSON-Ausgabe des Profils sind redigiert. `Destroy` löscht
die vom Profil gehaltenen Schlüsselbytes und darf wiederholt sowie parallel zu
`Plan` aufgerufen werden. Kopien eines Profils teilen diese Lebensdauer.

Das ist keine Zusage, alle temporären Kopien aus dem Go-Speicher entfernen zu
können. Der Aufrufer muss auch seinen Eingabepuffer löschen und darf die originale
Konfiguration weder loggen noch in Fehlerantworten zurückgeben. Strings aus
JSON-HTTP-Anfragen lassen sich in Go nicht zuverlässig überschreiben. Der
öffentliche Plan enthält weiterhin Netzwerkmetadaten und öffentliche Peer-Schlüssel
und gehört deshalb ausschließlich in eine authentifizierte Verwaltungsoberfläche.
Wird ein privater oder vorausgeteilter Schlüssel versehentlich auch als
`PublicKey` eingefügt, wird das gesamte Profil vor der Veröffentlichung abgelehnt.

## Unterstützte Eingaben

Genau ein `[Interface]` muss vor mindestens einem `[Peer]` stehen. Die Schlüssel
sind groß-/kleinschreibungssensitiv. UTF-8, CRLF, Leerzeilen und `#`-Kommentare
werden unterstützt. Es gelten folgende Grenzen: 64 KiB Eingabe, 128 Peers,
insgesamt 1.024 erlaubte Präfixe, 64 Interface-Adressen und 16 DNS-Server.

| Abschnitt/Feld | Vertrag |
| --- | --- |
| Interface / `PrivateKey` | Erforderlich; kanonisches Base64 für 32 Bytes, nicht vollständig null. Keine Dateipfade. |
| Interface / `Address` | Mindestens eine IPv4-/IPv6-Adresse; CIDR optional, ansonsten `/32` bzw. `/128`. Hostbits bleiben erhalten. Mehrere Zeilen und Kommalisten sind erlaubt. |
| Interface / `DNS` | Optional; ausschließlich IPv4-/IPv6-Adressen. Suchdomains werden ausdrücklich abgelehnt. Mehrere Zeilen und Kommalisten sind erlaubt. |
| Interface / `ListenPort` | Optional; 0–65535, wobei 0 die spätere automatische Wahl bedeutet. |
| Interface / `MTU` | Optional; 576–65535, bei IPv6-Adressen oder IPv6-Tunnelrouten mindestens 1280. Keine automatische MTU-Ermittlung im Parser. |
| Peer / `PublicKey` | Erforderlich; kanonischer 32-Byte-Base64-Schlüssel. Null-/Low-Order-X25519-Schlüssel, doppelte Peers und der eigene Interface-PublicKey werden abgelehnt. |
| Peer / `PresharedKey` | Optional; kanonischer 32-Byte-Base64-Schlüssel, nicht vollständig null. Nur dessen Vorhandensein erscheint im Plan. |
| Peer / `AllowedIPs` | Mindestens ein IPv4-/IPv6-CIDR-Präfix. Hostbits werden für Routen maskiert. Mehrere Zeilen und Kommalisten sind erlaubt. Überlappungen innerhalb und zwischen Peers werden abgelehnt. |
| Peer / `Endpoint` | Optional; `hostname:port`, `IPv4:port` oder `[IPv6]:port`, Port 1–65535. ASCII-DNS-Namen einschließlich Punycode, keine URL, keine Shellsyntax. Ein abschließender DNS-Punkt wird entfernt; Namen werden kleingeschrieben. |
| Peer / `PersistentKeepalive` | Optional; 0–65535 Sekunden, `off` wird zu 0. |

Unbekannte Felder, unbekannte Abschnitte und wiederholte Einzelwerte sind Fehler.
`PreUp`, `PostUp`, `PreDown`, `PostDown`, `SaveConfig`, `Table` und `FwMark` werden
ausdrücklich abgelehnt. Es existiert kein Modus, der sie dennoch ausführt.
IPv4-gemappte IPv6-Adressen, Zonenkennungen und mehrdeutige IP-Schreibweisen werden
abgelehnt. DNS-/Interface-Adressen dürfen weder unspecified noch multicast sein;
Endpoints dürfen zusätzlich keine Loopback-Adressen sein.

Die Einschränkung bei überlappenden `AllowedIPs` ist absichtlich strenger als
WireGuards mögliche Präfixauswahl. Vor einer späteren Unterstützung ist ein
explizites Modell für diese Routingabsicht erforderlich; ein Import darf sie nicht
stillschweigend vereinfachen. Profile ohne Interface-Adresse oder ohne erlaubte
Peer-Präfixe sind für diesen Verwaltungsvertrag ebenfalls nicht unterstützt.

## Was der Plan aussagt

`Plan` enthält normalisierte Interface-Adressen, DNS-Adressen, optionale Port-/MTU-
Werte sowie öffentliche Peer-Metadaten. `defaultRouteIPv4` und `defaultRouteIPv6`
erkennen auch vollständig abdeckende geteilte Routen, etwa zwei `/1`-Präfixe.

Ein Endpoint, der genau einer eigenen Interface-Adresse entspricht, wird als
statischer Routingfehler abgelehnt. Literal-IP-Endpoints innerhalb von Tunnel-
oder verbundenen Interface-Präfixen erscheinen in `endpointExclusions`: Vor einer
Aktivierung müssen dafür Ausnahmerouten über den ursprünglichen Uplink geplant
und geprüft werden. Das ist insbesondere bei einer Standardroute relevant.
Hostnamen werden nicht aufgelöst und erhalten einen entsprechenden Warnhinweis.
Peers ohne Endpoint sind für eingehende Handshakes zulässig und werden als solche
gekennzeichnet.

`requiredCapabilities` beschreibt `CAP_NET_ADMIN` für einen **zukünftigen** Apply-
Schritt. Der Parser benötigt diese Capability nicht. `leakRisks` enthält feste
Kennungen für fehlenden Kill-Switch, unvollständig getunnelte Adressfamilien,
fehlende DNS-Konfiguration oder DNS-Server außerhalb der Tunnelrouten. Diese
Kennungen beschreiben die Konfiguration; sie sind keine Messung des Geräts.
`applied` und `killSwitchActive` sind immer `false`.

## Prüfungen und verbleibende Arbeit

```sh
go test -race -cover ./...
go test -run='^$' -fuzz=FuzzParse -fuzztime=10s -parallel=2 .
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build ./...
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build ./...
```

Tests prüfen beide IP-Familien, volle und geteilte Routen, mehrere Peers,
Grenzwerte, ungültige Schlüssel/Endpoints, Hooks, statische Routingkonflikte,
redigierte Fehler/Formatierungen/JSON, unabhängige Plankopien und paralleles
Löschen. Fuzzing überprüft auch ungültige Eingaben und Planinvarianten.

Das Subpackage [`manager`](manager/README.md) ergänzt inzwischen explizite
Runtime-Schlüsselleihen, private Konfigurationsablage, atomare Journale,
Abbruch/Rollback und Recovery für einen injizierten nativen Backend-Treiber.
Das Subpackage [`policy`](policy/README.md) definiert außerdem vollständige,
journalisierbare Full-Tunnel-Pläne mit dualem Policy-Routing, engen Endpoint-/LAN-
Ausnahmen und einer attestierten Firewall. Die reine `Parse`-/`Plan`-API führt
weiterhin keine Netzwerkoperation aus. Produktive Betriebsgrenzen bleiben:
ausdrücklich unterstützte Backend-Semantik, DNS-/Endpoint-Auflösung mit erneuter
Prüfung, explizite Geräteschlüsselgenerierung und Handshake-/Reconnect-/Leak-Tests
auf echten Geräten. Ein validierter Plan ist
weiterhin kein Nachweis einer erfolgreichen VPN-Verbindung oder des Schutzes
laufenden Netzwerkverkehrs.

Grundlagen: [wg-quick-Format](https://git.zx2c4.com/wireguard-tools/about/src/man/wg-quick.8),
[WireGuard-Konfiguration](https://git.zx2c4.com/wireguard-tools/about/src/man/wg.8),
[Go crypto/ecdh](https://pkg.go.dev/crypto/ecdh).
