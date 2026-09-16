# Gemeinsame Netzwerkdienst-Testverträge

`status.json` und `wireguard-plan.json` entstehen aus dem echten Go-HTTP-Handler
in `apps/network-agent/internal/api/contract_test.go`. Nur die beobachteten
Kernelwerte stammen aus einem deterministischen Test-Provider. Der WireGuard-
Plan wird durch den echten Parser erzeugt; seine erkennbaren Testschlüssel sind
keine Geräteschlüssel. Private und vorausgeteilte Schlüssel dürfen in diesen
Antwortdateien nicht vorkommen.

Die Java-Bridge liest dieselben Dateien in `NetworkAgentClientTest`; die React-
Schemata prüfen sie in `features/wireguard/contracts.test.ts`. Dadurch
werden Drift bei Feldnamen, optionalen Werten, leeren Arrays, IPv6, Multipath und
vorzeichenlosen 32-Bit-Werten auch zwischen den Sprachen sichtbar. Optionale
Go-Slices mit `omitempty` sind bei `nil` ausgelassen, nicht als JSON-`null`
ausgegeben; die Java-Bridge normalisiert fehlende `nextHops` zu einer leeren Liste.

Golden-Dateien werden bei gewöhnlichen Tests nur gelesen. Nach einer bewussten
Vertragsänderung können sie aus dem Modul `apps/network-agent` neu erzeugt werden:

```sh
UPDATE_NETWORK_AGENT_CONTRACTS=1 go test ./internal/api -run '^TestSharedProtocolGoldenFixtures$'
go test -race ./internal/api
```

Anschließend vom Repository-Stamm die Java-Vertragstests und die Console-Tests
ausführen. Ein Update der Dateien allein ist kein Nachweis dafür, dass ihre
Verbraucher das neue Format unterstützen.

```sh
./mvnw -pl apps/server -Dtest=NetworkAgentClientTest test
npm --prefix apps/console test
```
