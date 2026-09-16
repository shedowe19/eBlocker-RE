# Abhängigkeits- und Build-Änderungen

Stand: 16. September 2026. Versionsangaben wurden gegen die offiziellen
Release-Seiten bzw. Maven Central, npm und die Go-Modulmetadaten geprüft.
Dies ist eine Änderungsübersicht, keine vollständige Sicherheitsbewertung.

| Bereich | Vorher | Jetzt |
| --- | --- | --- |
| UPnP | Cling 2.1.2 / separates Repository | jUPnP 3.0.5 / Maven Central |
| Java-Zielversion | 11 | 17; zusätzlicher CI-Lauf mit JDK 25 |
| Maven | lokal vorausgesetzt | Wrapper 3.9.16 mit SHA-256 |
| Node im Maven-Build | 16.13.0 | 24.21.0 |
| npm-Installation im Maven-Build | `npm install` | `npm ci` |
| Frontend Maven Plugin | 1.4 | 2.0.2 |
| Maven Compiler / Surefire | 3.8.1 / 3.2.1 | 3.16.0 / 3.6.0 |
| Maven Resources / Dependency | verschiedene alte Versionen | zentral 3.5.0 / 3.11.0 |
| Maven Jar / Source / Javadoc | verschiedene alte Versionen | zentral 3.5.1 / 3.4.0 / 3.12.0 |
| Debian-Paketplugin | jdeb 1.3 | jdeb 1.14 |
| JUnit Jupiter/Vintage | 5.10.1 | 6.1.3 mit BOM und Launcher |
| JUnit 4 | 4.13.1 | 4.13.2 |
| Mockito | 5.10.0 / ältere Modulversionen | 5.23.0 mit Start-Agent |
| Netty | 4.1.84.Final | 4.1.138.Final mit BOM |
| SLF4J | 1.7.x | 2.0.19 und passende Log4j-Brücke |
| Guava | 33.2.1-jre | 33.7.1-jre |
| Commons IO / Codec / CLI / CSV | 2.14.0 / 1.10 / 1.3.1 / 1.4 | 2.22.0 / 1.22.1 / 1.11.0 / 1.14.1 |
| HTTP Client 4 | 4.5.13 | 4.5.14 |
| Nullbarkeits-Annotationen | indirekt geliefertes JSR-305 | explizites JSpecify 1.0.0 |
| Entwicklungsserver | Express 4.17.1 | Express 5.2.1; neue Routing-Syntax, eingebaute Body-Parser |
| AngularJS-Kern | 1.8.2 | 1.8.3 (weiterhin abzulösen) |
| UI Router / Angular Material | 1.0.29 / 1.1.26 | 1.1.2 / 1.2.5 |
| Karma | 6.3.16 | 6.4.4 |
| Go | 1.22.5 in den Modulen | geprüft mit 1.27.1; Mindestversion durch Abhängigkeiten bestimmt |
| CoreDNS / Caddy | 1.11.3 / 1.1.1 | 1.14.7 / 1.1.4 |
| Go DNS / Redis Client | 1.1.58 / 9.6.1 | 1.1.73 / 9.22.0 |

Unbenutzte direkte npm-Abhängigkeiten `fs`, `path`, `babel-loader`,
`uglifyjs-webpack-plugin`, `body-parser` und `gulp-util` wurden entfernt.
Der Standardlogger ersetzt die bisherige `gulp-util`-Logausgabe.

Nicht alle Abhängigkeiten sind damit auf ihrer neuesten Hauptversion. Insbesondere
AngularJS samt Babel-/Gulp-Kette, RestExpress, DateAdapterJ, Jedis, Ruby-Dienste und
der mitgelieferte Squid-Fork benötigen weitere Migrationen. Ihre funktionalen
Verträge dürfen dabei nicht allein zugunsten einer Versionsnummer entfallen.

Die Netty-ICAP-Javadoc-Fehler wurden korrigiert; Fehler beim Erzeugen dieser
Dokumentation werden jetzt nicht mehr über `failOnError=false` unterdrückt.

## Ergänzungen der fortgesetzten Migration

- Die neue Konsole verwendet React 19.3.0, React Router 7.18.4, Zod 4.6.5,
  TypeScript 7.0.2 und Vite 8.3.0; die genaue transitive Auflösung steht in
  `apps/console/package-lock.json`. Die fünf alten Webanwendungen bleiben für
  die noch nicht ersetzten Abläufe enthalten.
- Der offline ausführbare RDB-Decoder verwendet `redis-replicator` 3.11.0
  (Apache-2.0). Er läuft in einem zeit- und speicherbegrenzten Unterprozess;
  importiert wird ausschließlich nach erfolgreicher Dateiprüfung. Das ist
  noch kein Austausch der laufenden Redis-Dienste.
- Das native Full-Tunnel-Backend verwendet `github.com/google/nftables` 0.3.0
  für Netlink-Transaktionen. Abhängigkeiten und Prüfsummen sind in
  `apps/network-agent/go.mod` und `go.sum` festgelegt. Echte Kerneloperationen
  bleiben durch gesonderte CI-Tests und ausdrückliche Dienstaktivierung begrenzt.
- Die neuen Cookie-Sitzungen und feldweisen Datenänderungen benötigen keine
  zusätzliche Browser-Persistenz oder Authentifizierungsbibliothek.
