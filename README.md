# Vollständige Originalhistorie von eBlocker-RE

Dieser Branch `archive/original-history` bewahrt das unveränderte Git-Bundle aus
dem Übergabepaket. Die Aufteilung in 35 Dateien dient ausschließlich dem Upload.
Die Rekonstruktion ergibt bytegenau das ursprüngliche Bundle einschließlich der
originalen Commit-Objekte, Elternbeziehungen, Branch-Spitzen und Tags.

Der Quellstand auf `main` wurde über die GitHub-Integration importiert. Dieser
Import-Commit hat eine andere Commit-ID und enthält die Originalhistorie nicht
als seine Elternkette. Sein Quellbaum muss dem unten angegebenen Originalbaum
entsprechen. Das Bundle in diesem Archiv erhält zusätzlich die vollständige
Originalhistorie; sie kann damit jederzeit nativ wiederhergestellt werden.

## Gesicherter Stand

| Eigenschaft | Wert |
| --- | --- |
| Originalbranch | `codex/modernize-architecture` |
| Original-HEAD | `b4124af67096fbdd8463fa531f6fa4824e42f5c0` |
| Original-Dateibaum | `c84b31b3084b27dee66f351e44f62459c46481c7` |
| Pfade im Original-Dateibaum | 8.794 |
| Commits über alle Bundle-Referenzen | 1.141 |
| Angekündigte Bundle-Referenzen | 77, einschließlich `HEAD` |
| Benannte Referenzen | 76: 2 Branches, 39 Remote-Referenzen und 35 Tags |
| Bundlegröße | 36.601.856 Bytes |
| Teile | 35, davon 34 mit 1.048.576 Bytes und ein letzter mit 950.272 Bytes |
| Bundleformat | Git-Bundle v2; vollständige Historie ohne Voraussetzungen |
| SHA256 | `baa879abb7da321bd416b57a92f0a6d174cf4515a3e46e6866ad69e729393e59` |

Die 35 ursprünglichen Tags sind unveränderte Lightweight-Tags. Die vollständige
Liste der ursprünglichen Referenzen und Objekt-IDs steht in `bundle-refs.txt`.

## Rekonstruktion und Prüfung

Benötigt werden Git und Python 3.8 oder neuer. Das Archiv zuerst vollständig
auschecken; in den folgenden Befehlen werden neue, noch nicht vorhandene
Zielverzeichnisse und Dateien verwendet:

```sh
git clone --single-branch --branch archive/original-history https://github.com/shedowe19/eBlocker-RE.git eblocker-original-history
cd eblocker-original-history
python3 reconstruct-bundle.py
```

Unter Windows kann `python` statt `python3` verwendet werden. Das Skript liest
ausschließlich die explizite, geordnete Teileliste aus `bundle-manifest.json`.
Es prüft Dateinamen, Größen und SHA256 jedes Teils sowie Größe und SHA256 des
Gesamtbundles. Vorhandene Zieldateien werden nicht überschrieben. Bei einem
Prüffehler wird die vom Skript angelegte unvollständige Zieldatei entfernt.
Mit `--output /gewünschter/pfad/original-history.bundle` ist ein anderes,
ebenfalls noch nicht vorhandenes Ziel möglich.

Auf Systemen mit `sha256sum` kann zusätzlich geprüft werden:

```sh
sha256sum -c SHA256SUMS
git bundle verify original-history.bundle
git bundle list-heads original-history.bundle
```

`SHA256SUMS` enthält sowohl die 35 Teile als auch das rekonstruierte Bundle;
deshalb den SHA256-Prüfbefehl erst nach der Rekonstruktion ausführen. Die
Skriptprüfung funktioniert auch ohne das externe Programm `sha256sum`.

## Vollständige Wiederherstellung aller Referenzen

Ein Mirror-Clone übernimmt zusätzlich die ursprünglichen Remote-Referenzen.
Diese sind für die vollständige Erhaltung aller 1.141 Commits erforderlich.

```sh
git clone --mirror original-history.bundle ../eblocker-original.git
git --git-dir=../eblocker-original.git symbolic-ref HEAD refs/heads/codex/modernize-architecture
git --git-dir=../eblocker-original.git fsck --full
git --git-dir=../eblocker-original.git rev-list --all --count
git --git-dir=../eblocker-original.git rev-parse refs/heads/codex/modernize-architecture
git --git-dir=../eblocker-original.git rev-parse 'refs/heads/codex/modernize-architecture^{tree}'
```

Die drei letzten Ausgaben müssen `1141`,
`b4124af67096fbdd8463fa531f6fa4824e42f5c0` und
`c84b31b3084b27dee66f351e44f62459c46481c7` sein.
`git for-each-ref` im Mirror zeigt die 76 benannten Referenzen; `HEAD` ist eine
zusätzliche symbolische Referenz und steht daher nicht in dieser Zählung.

Für einen separaten Arbeitsbaum:

```sh
git clone --branch codex/modernize-architecture ../eblocker-original.git ../eblocker-restored
```

Den Mirror und/oder das unveränderte Bundle als vollständiges Archiv behalten.
Ein normaler Arbeitsbaum-Clone übernimmt standardmäßig nicht alle ursprünglichen
Remote-Referenzen. Die Originalhistorie kann später mit regulärem Git-Zugang aus
dem Mirror veröffentlicht werden. Bestehende Remote-Branches dabei zunächst
vergleichen und keine fremden Änderungen überschreiben.

## Einordnung des Projektstands

Das Archiv garantiert die Erhaltung des übergebenen Quellstands und seiner
Historie. Es ist keine Behauptung, dass die gesamte Modernisierung abgeschlossen
ist. Verbleibende Arbeiten sind im Quellstand unter `docs/refactor/PROGRESS.md`
dokumentiert. Unter anderem wurden AngularJS, Redis, Ruby, RestExpress und Squid
noch nicht vollständig abgelöst. Die Archivprüfung führt keine Hardware- oder
Appliance-Tests durch.
