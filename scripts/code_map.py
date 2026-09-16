#!/usr/bin/env python3
"""Generate a compact navigation map from the current source tree."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    lines = ['# Code-Landkarte', '',
             'Generiert mit `python3 scripts/code_map.py`. Produktionscode und Tests werden getrennt gezählt.', '',
             '## Java-Server nach Fachbereich', '',
             '| Bereich | Produktionsdateien | Testdateien |', '| --- | ---: | ---: |']
    base = ROOT / 'apps/server/src'
    production = base / 'main/java/org/eblocker/server'
    tests = base / 'test/java/org/eblocker/server'
    for directory in sorted(production.iterdir()):
        if not directory.is_dir():
            continue
        areas = sorted(p for p in directory.iterdir() if p.is_dir()) if directory.name == 'common' else [directory]
        if directory.name == 'common':
            count = len(list(directory.glob('*.java')))
            lines.append(f'| [common (Komposition)](../{directory.relative_to(ROOT)}) | {count} | {len(list((tests / "common").glob("*.java")))} |')
        for area in areas:
            relative = area.relative_to(production)
            lines.append(f'| [{relative}](../{area.relative_to(ROOT)}) | {len(list(area.rglob("*.java")))} | {len(list((tests / relative).rglob("*.java")))} |')
    lines += ['', '## Einstiegspunkte', '',
              '| Aufgabe | Einstieg |', '| --- | --- |',
              '| HTTP-Endpunkt finden | `apps/server/src/main/java/org/eblocker/server/http/server/routes/` |',
              '| Bestehende Bildschirm-Routen | `apps/web/src/settings/app/routes/` |',
              '| Neue React-Oberfläche | `apps/console/src/` |',
              '| Geräte in Redis speichern | `apps/server/src/main/java/org/eblocker/server/common/data/JedisDeviceRepository.java` |',
              '| Anwendung starten / Initialisierung | `apps/server/src/main/java/org/eblocker/server/app/` |',
              '| Gemeinsame Java-Versionen | `build/parent/pom.xml` |',
              '| DNS-Filter und Statistiken | `platform/dns/coredns/` |',
              '| Dynamisches DNS | `platform/dns/dynamic/` |',
              '| Netzwerkkonfiguration / native Werkzeuge | `platform/native/network-tools/` |',
              '| Kryptografie | `libs/crypto/` |',
              '| Herkunft der importierten Quellen | `sources.lock.json` |',
              '', '## Größte verbleibende Java-Klassen', '',
              'Diese Liste zeigt weitere Zerlegungskandidaten. Sie ist keine Liste unbenutzter Dateien.', '',
              '| Datei | Zeilen |', '| --- | ---: |']
    sizes = sorted(((len(p.read_text().splitlines()), p) for p in production.rglob('*.java')), reverse=True)
    for count, path in sizes[:12]:
        lines.append(f'| [{path.name}](../{path.relative_to(ROOT)}) | {count} |')
    output = ROOT / 'docs/CODE_MAP.md'
    # Keep the hand-maintained navigation guide when refreshing generated metrics.
    marker = '## Fachgrenzen und neue Module'
    existing = output.read_text() if output.exists() else ''
    guide = existing[existing.index(marker):] if marker in existing else ''
    output.write_text('\n'.join(lines) + '\n' + ('\n' + guide if guide else ''))


if __name__ == '__main__':
    main()
