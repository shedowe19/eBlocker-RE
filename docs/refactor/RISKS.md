# Verbleibende Grenzen und Risiken

| Bereich | Stand / notwendiger Nachweis |
| --- | --- |
| Browser-Tests | Lokaler Chromium-Start ist durch Socket-Berechtigungen der Ausführungsumgebung blockiert. CI führt die echten Browser-Tests aus; ein grüner CI-Lauf wurde hier noch nicht beobachtet. |
| Oberfläche | AngularJS und mehrere zugehörige Erweiterungen sind trotz ihrer letzten verfügbaren Versionen nicht mehr regulär gepflegt. Der erste React-Ersatz deckt Anmeldung und lesende Geräteverwaltung ab. Übrige Funktionen sowie echter Browser-/Appliance-Nachweis fehlen. |
| Java-Dienste | Große Persistenz-, Start- und Konfigurationsdienste bestehen weiterhin. Die neue Code-Landkarte und Routenmodule erleichtern die weitere Zerlegung. |
| Dependency-Migration | Netty ist auf dem aktualisierten 4.1-Zweig; RestExpress, DateAdapterJ, Jedis 2 und mehrere historische Bibliotheken bleiben Ablösungskandidaten. Die Änderungen sind keine vollständige CVE-Freigabe. |
| JDK | Lokal wurde mit JDK 17 getestet. Die CI-Matrix enthält JDK 25; dieser Lauf wurde hier noch nicht beobachtet. |
| Native Komponenten | C-/Ruby-/Squid-Quellen sind integriert. Ihre vollständigen Paket-, Laufzeit- und Hardwaretests sind noch nicht abgeschlossen. |
| Appliance | Kein Boot-, Installations-, Upgrade-, Rollback- oder Lasttest auf einer echten amd64-/arm64-Appliance. Die Go-Binärdateien wurden für beide Architekturen kompiliert. |
| Geräteschlüssel | Der importierte gemeinsame private Schlüssel wurde entfernt. Ein Image darf keine gemeinsam genutzte private Geräteidentität enthalten. Die alte Image-Automatisierung ist noch kein freigegebener neuer Releaseprozess. |
| Neue Funktionen | Native WireGuard-Integration, moderner UI-Ersatz, typisierter Netzwerk-Agent und Redis-/Datenmigration sind weiterhin eigenständige offene Migrationen. |

Der frühere Cling-Download-Blocker ist durch jUPnP behoben. Der aktuelle
[Prüfstatus](PROGRESS.md) trennt ausgeführte Tests von vorbereiteten CI-Prüfungen.
