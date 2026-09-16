# Native WireGuard-Runtime

`eblocker-wireguard` ist ein lokales Administratorwerkzeug. Der standardmäßige
`eblocker-network-agent.service` bleibt ohne Capabilities und bietet ausschließlich
Beobachtung und Validierung. Der zusätzlich installierte
`eblocker-network-control.service` verwendet einen getrennten privaten Socket,
prüft `SO_PEERCRED` gegen den Serveraccount `icapd` und ist ohne explizite
Bereitstellung von `/etc/eblocker/network-control-enabled` gesperrt. Das Paket
aktiviert keinen der Dienste automatisch und setzt keine Dateicapabilities.

## Native Operationen und Ownership

Kernel-WireGuard, Adressen und Routen verwenden RTNETLINK/Generic Netlink;
Firewalländerungen verwenden die nftables-Netlink-UAPI. Es werden weder Shell,
`wg`, `wg-quick`, `ip`, `nft` noch importierte Hooks ausgeführt. Es gibt keinen
Userspace-WireGuard-Fallback. WireGuard und für Full-Tunnel `nf_tables` müssen
vorher vom Administrator bereitgestellt werden; die Runtime lädt keine Module.

Ein neuer Tunnel bekommt `ebwg` plus zehn zufällige Hex-Zeichen und den Alias
`eblocker-wireguard:<32 Hex-Zeichen>` atomar beim exklusiven Anlegen. Bestehende
Interfaces werden nie übernommen. Jede weitere Operation prüft Name, Index,
Typ und Alias. Adressen verwenden `IFA_F_NOPREFIXROUTE`. Split-Routen entstehen
exklusiv in der Main-Tabelle; der Split-Modus besitzt keinen Kill-Switch.

Private/Preshared Keys werden ausschließlich als WireGuard-UAPI-Attribute
übertragen. Kernelantworten exportieren nur öffentliche Peerinformationen,
Handshakezeit und RX-/TX-Zähler. Schlüsselhaltige Puffer werden bestmöglich
gelöscht. Go garantiert keine vollständige Löschung aller temporären Kopien.
`NLDEBUG` sperrt die Runtime, da dieser Bibliotheksmodus rohe Attribute loggen könnte.

## Full-Tunnel und Kill-Switch

Der Manager journalisiert vor der ersten Mutation einen kanonischen öffentlichen
Policyplan mit Ownership-ID und SHA-256-Digest. Der native Ablauf ist:

1. Frischen Zustand lesen und Konflikte/unsupported Topologien ablehnen.
2. Eine eigene `inet`-nftables-Tabelle exklusiv in **einem atomaren Batch** anlegen:
   Basis-Chains `output` und `forward`, Priorität 0, jeweils Policy DROP.
3. Eigenes WireGuard-Interface anlegen, Schlüssel/Peer-Konfiguration und eine
   eindeutige Socket-Markierung setzen, Adressen und private Routen anlegen.
4. Für **beide** IP-Familien Main-Regel mit `suppress_prefixlength 0` bei Priorität
   11000 und `not fwmark <Mark>` zur eigenen Tabelle bei Priorität 11001 anlegen.
5. Link aktivieren und vollständigen Kernel-Readback prüfen.

Die Main-Tabelle und fremde nftables-Tabellen werden nicht ersetzt oder geleert.
Tabellen-ID und Markierung stammen aus dem Owner; die Firewall heißt
`ebwg_<OwnershipID>`. Jede Firewallregel enthält Owner und Digest. Der Readback
vergleicht Chain-Hooks, Policies, vollständige Reihenfolge, Ausdrucksbytes und
Ownership-Marker. Ein nftables-Generationswechsel während dieser Prüfung macht
die Beobachtung ungültig. Policy-Rules werden inklusive nativer Rule-Action und
sämtlicher erlaubter Selektoren geprüft; unbekannte Selektoren sind kein Erfolg.

Die Firewall erlaubt:

- OUTPUT über Loopback und Verkehr über das eigene WireGuard-Interface;
- nur in OUTPUT: äußeres UDP mit **genauer** Markierung, Endpoint-IP, Remote-Port
  und zuvor beobachtetem Uplink; keine allgemeine Freigabe markierter Pakete;
- explizite direkt verbundene LAN-/Verwaltungsnetze auf beobachteten lokalen
  Interfaces, abgeleitet aus Interfaceadressen und identischen Kernelrouten;
- auf diesen Interfaces eng begrenztes DHCPv4/DHCPv6 für Client und Server sowie
  ICMPv6-Nachbarschaftserkennung Typ 133–136 mit Hop-Limit 255 und linklokalem Ziel.

TCP/UDP-Zielports 53 und 853 außerhalb des WireGuard-Interfaces werden **vor** den
LAN-Ausnahmen verworfen. Das gilt ausdrücklich auch für einen bisherigen
**LAN-Router als DNS-Server**. Es wird keine Resolverkonfiguration geändert.
Wer diesen DNS-Pfad weiter verwendet, erhält daher möglicherweise keine
Namensauflösung. Ein genau passender verschlüsselter WireGuard-UDP-Endpoint darf
selbst Port 53/853 verwenden; diese eng begrenzte Ausnahme steht davor.

Es gibt keine pauschale `established`-Freigabe, keine bloße Markierungsfreigabe und
keine Ausnahme für eine ungetunnelte IP-Familie. Ein IPv4-only Full-Tunnel blockiert
sonstigen externen IPv6-Verkehr. LAN-Ausnahmen sind ausdrücklich unverschlüsselte
Verwaltungs-/Gerätekommunikation. Die Runtime richtet kein NAT oder Forwarding ein.

## Grenzen und Vorbedingungen

Vor Mutationen werden abgewiesen:

- DNS-Umschaltung (`DNS=`), Hostname-/Link-Local-/zonenbehaftete Endpoints;
- fehlende Endpoints in Full-Tunnel-Profilen und nicht eindeutig beobachtbare Uplinks;
- fremde Policy-Rules, reservierte Prioritäts-/Tabellenkollisionen;
- Bridges, VRFs, andere VPN-/Tunnelinterfaces, XDP-/TC-Filter, Flowtables und
  erkannte Hardware-Switching-/VF-Konfigurationen;
- striktes IPv4-Reverse-Path-Filtering (`rp_filter=1` wirksam); dessen Unterstützung
  würde zusätzliche globale sysctl-/Conntrack-Änderungen verlangen;
- kollidierende Interface-Adressnetze; im Split-Modus zusätzlich Kollisionen mit
  nicht-default Main-Routen und spezielle/Loopback-/Link-Local-Präfixe;
- mehr als 512 Kombinationen lokaler Interfaces und lokaler Netz-Ausnahmen.

Full-Tunnel verwendet feste globale Policy-Prioritäten und ist deshalb auf ein
aktives Full-Tunnel-Profil in einem unterstützten Namespace begrenzt. Roaming
auf einen anderen Endpoint/Uplink wird nicht automatisch freigegeben. Vorhandene
OpenVPN-/Tor-Konfigurationen werden nicht verändert; nicht gemeinsam unterstützte
Topologien scheitern ausdrücklich im Preflight.

`phase=active` bedeutet beim Split-Modus: eigener Link ist UP und besitzt das
exakte Peer-Public-Key-Set. Full-Tunnel verlangt zusätzlich passende native
Readbacks für Mark, beide Regelpaare, private Routen, Firewall und Endpointpfade.
Nur dann setzt der Manager `killSwitchActive=true`. **Dieser Status bestätigt
keinen Handshake, keine Internet-Erreichbarkeit und keine DNS-Auflösung.** Der
öffentliche Parserplan bleibt `applied=false`/`killSwitchActive=false`; tatsächlicher
Runtimezustand steht separat in Status und Observation.

Die Kernelabfragen sind kein atomarer Gesamtsnapshot aller Netzwerksubsysteme.
Privilegierte fremde Netzwerkverwalter dürfen parallel keine Netz-/Firewallobjekte
verändern. Eine solche Änderung kann eine Beobachtung unmittelbar veralten lassen;
der nächste Readback meldet Drift. Diese Runtime schützt nicht vor einem fremden
Prozess mit `CAP_NET_ADMIN`, der den Guard aktiv entfernt oder Markierungen fälscht.

## Rollback und Wiederanlauf

Der private Lifecycle-Journalzustand wird vor jeder relevanten Mutation dauerhaft
geschrieben. Bei Fehler/Abbruch werden eigene Policy-Rules, private Routen und das
eigene Interface entfernt; der vollständige Guard wird **zuletzt** entfernt.
Fehlt der Guard nach einem Crash, wird er vor dem Aufräumen exklusiv neu errichtet.
Fremde oder manipulierte Ressourcen verhindern die Bereinigung. Ein fehlgeschlagener
Cleanup behält den Guard und führt zu `recovery_required`.

Die Guard-Löschung ist zusätzlich an die **vor dem Readback beobachtete nftables-
Generation** gebunden: ein zwischenzeitlicher Tabellenersatz oder eine neue fremde
Regel lässt den atomaren Löschbatch scheitern. Es gibt keinen globalen Flush.
Interfaceverlust lässt den unabhängigen Guard bestehen; ungetunnelter Rückfall auf
die vorherige Default-Route wird dadurch weiter blockiert. Ein ausdrücklicher
erfolgreicher Disconnect entfernt schließlich den Guard und beendet diesen Schutz.

## Bedienung und Speicherung

Die Programme liegen unter `/usr/lib/eblocker-network-agent/`. CLI-Mutationen
verlangen `--enable-native-writes`; nur `status` ist ohne diese Freigabe erlaubt.

```sh
/usr/lib/eblocker-network-agent/eblocker-wireguard connect \
  --profile work --config /root/work.conf --enable-native-writes
/usr/lib/eblocker-network-agent/eblocker-wireguard status --profile work
/usr/lib/eblocker-network-agent/eblocker-wireguard disconnect \
  --profile work --enable-native-writes
/usr/lib/eblocker-network-agent/eblocker-wireguard recover --enable-native-writes
```

Konfigurationen müssen eigene reguläre Dateien mit Modus `0600` sein oder über stdin
kommen, niemals als Kommandozeilenschlüssel. CLI-State liegt standardmäßig unter
`/var/lib/eblocker-network-agent/wireguard` (`0700`, Dateien `0600`); der getrennte
Control-Dienst besitzt `/var/lib/eblocker-network-control/wireguard`.
Schlüsselkonfigurationen liegen während eines aktiven Profils unverschlüsselt mit
Dateirechteschutz dort; dies ist kein Secret-Vault. Backup und Hostzugriff müssen
sie schützen. Erfolgreicher Disconnect entfernt die gespeicherte Konfiguration.

Die Paketinstallation legt separate Systemaccounts an und ergänzt nur für
`icapserver.service` die Socket-Zugangsgruppen. `icapd` benötigt dafür einen
Serverneustart. Der Control-Daemon besitzt ausschließlich `CAP_NET_ADMIN` und eine
eigene, deaktivierte Unit; der Beobachtungsdienst erhält diese Capability nicht.
Vor Paketentfernung aktive Profile ausdrücklich trennen. Ein gestoppter Prozess
entfernt keine aktiven Kernel-Guards und keine Recovery-Journals automatisch.

Mutationen führen zuerst Recover aus. CLI-Operationen haben standardmäßig 30 Sekunden
Timeout, maximal zwei Minuten; kompensierender Rollback erhält unabhängig fünf
Sekunden. Der Read-only-Status löst keine Kernelbereinigung aus.

## Verifikation

Unit-/Race-Tests benutzen explizite Fake-Transporte. Sie prüfen komplette und
teilweise Apply-/Cleanup-Verläufe, Abbruch, Drift, Ownership, native UAPI-Attribute,
Generationsbindung, native nftables-Marshal/Readback und manipulierte Regeln.
5.760 Paketkombinationen plus NDP-Randfälle werten die **tatsächlich kompilierten
nftables-Ausdrücke** gegen den unabhängigen reinen Policyvertrag aus.

Zusätzliche echte Kerneltests sind nur in einem wegwerfbaren privilegierten Runner
mit bereits bereitgestelltem WireGuard und `nf_tables` freigeschaltet:

```sh
EBLOCKER_WIREGUARD_INTEGRATION=1 go test -race ./internal/wgkernel \
  -run '^TestNative(Lifecycle|FullTunnel)InIsolatedNamespace$'
```

Sie isolieren ihren OS-Thread vor jeder Mutation in einem neuen Netzwerk-Namespace.
Der Full-Tunnel-Test umfasst native Readbacks, IPv4-only/Dual-Stack/IPv6-Endpoint,
erzwungenen Linkverlust und echte UDP-Sendeprüfungen für verbotenen Verkehr,
DNS, genaue Endpointausnahmen und LAN-Verwaltung. Der CI-Job `wireguard-native`
isoliert zusätzlich den gesamten Testprozess; Paketbau hängt an diesem Gate.
Die lokale Entwicklungsumgebung erlaubt diese Kernel-/Socketprüfungen nicht;
hier bleiben sie sichtbar übersprungen. Eine CI-Konfiguration ist kein Nachweis
bereits erfolgreich ausgeführter CI. Externe Peer-Handshakes, Weiterleitung/NAT,
Appliancebetrieb und DNS-/Leakparität benötigen weitere reale Abnahmetests.

## Versionen und Primärquellen

Versionen/Prüfsummen stehen in `go.mod`/`go.sum`. Das Debian-Paket enthält
Lizenz-/Copyright-/Patenthinweise und Modulprovenienz unter
`usr/share/doc/eblocker-network-agent/third-party/`, einschließlich der Apache-2.0-
Lizenz von `github.com/google/nftables v0.3.0`.

- [WireGuard-UAPI](https://git.zx2c4.com/wireguard-linux/tree/include/uapi/linux/wireguard.h)
- [WireGuard Policy Routing](https://www.wireguard.com/netns/)
- [nftables Handbuch](https://netfilter.org/projects/nftables/manpage.html)
- [nftables-Netlink-Bibliothek](https://github.com/google/nftables)
- [RTNETLINK-Bibliothek](https://github.com/vishvananda/netlink)
- [Generic-Netlink-Bibliothek](https://github.com/mdlayher/genetlink)
