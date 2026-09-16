# Full-Tunnel-Vertrag

Dieses Paket erzeugt einen deterministischen öffentlichen Ressourcenplan und
prüft die dazugehörige Laufzeit-Attestation. Es enthält keine Schlüssel, startet
keine Programme und verändert keine Kernelkonfiguration. `Build` benötigt einen
bereits importierten WireGuard-Plan sowie eine frische native Beobachtung der
ursprünglichen Endpoint-Uplinks und direkt verbundenen lokalen Netze.

Der Plan bindet eine zufällige Ownership-ID an ein eigenes nftables-Table, eine
eigene Routingtabelle, den WireGuard-Socket-Mark und beide IP-Familien. Ein
SHA-256-Digest erfasst sämtliche Felder einschließlich der expliziten lokalen
Ausnahmen. Er ist eine Integritätskennung, keine kryptografische Autorisierung.
`Validate` baut den kanonischen Plan erneut auf und vergleicht alle Felder.

## Routing und Firewall

Für IPv4 und IPv6 gelten eine Main-Table-Regel mit unterdrückter Standardroute
bei Priorität 11000 und eine invertierte Socket-Mark-Regel zur eigenen Tabelle
bei Priorität 11001. Die eigene Tabelle enthält ausschließlich die importierten
`AllowedIPs`. Die bestehende Main-Tabelle wird nicht verändert. Der Backend muss
fremde Policy-Regeln, Ressourcen- oder Prioritätskollisionen und unbeherrschte
Topologien vor Erstellung ablehnen. Ein nur für IPv4 konfigurierter Full-Tunnel
gibt deshalb IPv6 außerhalb seiner lokalen Ausnahmen nicht frei und umgekehrt.

Die eigenen nftables-Output- und Forward-Ketten haben Drop als Grundregel:

1. Output über Loopback und Output/Forward über das eigene WireGuard-Interface
   sind erlaubt.
2. Nur Output erlaubt äußere UDP-Pakete mit dem exakten Socket-Mark, Ziel-IP,
   Ziel-Port und beobachteten Uplink des Peers. Diese Ausnahme gilt auch für
   WireGuard auf Port 53 oder 853.
3. TCP-/UDP-Zielports 53 und 853 außerhalb des Tunnels werden gesperrt.
4. Explizite lokale Zielnetze auf expliziten lokalen Interfaces bleiben für
   Verwaltung und LAN-Verkehr erreichbar.
5. Nur Output erlaubt eng begrenzte lokale DHCPv4-Client-/Server-Broadcasts,
   DHCPv6-Client-/Server-Kommunikation im Link und IPv6-Nachbarerkennung mit
   Hoplimit 255. Keine dieser Ausnahmen erlaubt beliebigen Forward-Verkehr.

Es gibt keine allgemeine Mark- oder Established-Connection-Ausnahme. Bestehende
außertunnelige Internetverbindungen werden daher ebenso von der Guard erfasst.
`Evaluate` beschreibt diese Paketsemantik für deterministische Tests; es ersetzt
weder den nativen Regelcompiler noch dessen vollständige Kernel-Rückleseprüfung.

## Aktivierung und Grenzen

Der Manager journalisiert den vollständigen Plan vor der ersten Mutation.
Der native Backend installiert die Firewall atomar zuerst und entfernt sie bei
Rollback/Disconnect zuletzt. `Attestation.Matches` verlangt passende Ownership
und Digest sowie positive Einzelprüfungen für Routing, Firewall, Socket-Mark,
Endpoints und die Regeln beider IP-Familien. Eine reine Table-/Alias-Kennung
genügt nicht. Ein Manager-Neustart muss diese Prüfung erneut durchführen.

Profile mit DNS-Einträgen sind ohne Resolveradapter ausdrücklich nicht
aktivierbar. Peers benötigen feste Literal-IP-Endpoints; dynamische Auflösung
oder Roaming ist nicht enthalten. Der Guard verhindert klassischen DNS-Verkehr
auf den genannten Ports außerhalb des Tunnels, kann jedoch keine beliebigen
verschlüsselten Anwendungsprotokolle in explizit freigegebenen LAN-Netzen als DNS
erkennen. Die LAN-Ausnahmen sind sichtbar im Plan und begrenzen den Schutzumfang.
Maximal 512 Kombinationen aus lokalen Interfaces und Netzen werden akzeptiert.

Eine positive Attestation belegt den beobachteten eigenen Kernelzustand, keinen
erfolgreichen Handshake, funktionierenden Resolver oder ständige Kontrolle gegen
gleichberechtigte Administratoren. Unit-, Race- und semantische Pakettests laufen
ohne Kerneländerungen. Echte Linux-Appliance-/Namespace-Tests für IPv4/IPv6,
Handshake, Ausfall, Firewallintegration und Leaks bleiben separate Nachweise.
