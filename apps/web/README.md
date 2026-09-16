# eBlocker Weboberfläche

Node-Version: siehe [`.nvmrc`](.nvmrc). Alle Befehle in diesem Verzeichnis ausführen.

```sh
npm ci
npm run test:build-tools   # Build-, Server- und Routing-Verträge
npm run build             # Assets und Browser-Tests
npx gulp serve-dev        # Entwickeln mit automatischem Neubau
```

Für Browser-Tests wird Chrome/Chromium benötigt (`CHROME_BIN` bei abweichendem Pfad).
`npm run build:assets` erzeugt Assets ohne Browser-Testlauf und ist keine vollständige Verifikation.
Der Entwicklungsserver bindet standardmäßig an `127.0.0.1`; `HOST` und `PORT`
können ausdrücklich gesetzt werden. Er enthält Mock-API-Daten und ist kein Produktivserver.

| Bereich | Pfad |
| --- | --- |
| Dashboard | `src/dashboard` |
| Ersteinrichtung | `src/setup` |
| Einstellungen | `src/settings` |
| Kontrollleiste | `src/controlbar` |
| Gemeinsame Komponenten / Übersetzungen | `src/shared` |
| Bildschirm-Routen nach Fachbereich | `src/settings/app/routes` |
| Geordnete Routen-Komposition | `src/settings/app/_bootstrap/_configs/routeConfig.js` |
| Build-Helfer und browserfreie Regressionstests | `build-tools` |

Die Oberfläche verwendet noch AngularJS. Die Funktionsaufteilung ist eine
Voraussetzung für deren Ablösung; sie stellt selbst keinen Framework-Wechsel dar.
Die Verträge in `build-tools/contracts` konservieren die 85 bisherigen Bildschirm-
Routen inklusive Zugriffsregeln und Resolve-Funktionen.
