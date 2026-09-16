#!/usr/bin/env python3
"""Single entry point for the in-tree eBlocker workspace; no companion clones."""
import argparse
import json
import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def validate_sources(root=ROOT):
    manifest = json.loads((root / 'sources.lock.json').read_text())
    if manifest.get('schemaVersion') != 2 or manifest.get('layout') != 'monorepo':
        raise ValueError('Expected the monorepo source manifest')
    names = set()
    for source in manifest['sources']:
        name = source['name']
        if name in names or not re.fullmatch(r'[0-9a-f]{40}', source['commit']):
            raise ValueError(f'Duplicate source or invalid upstream revision: {name}')
        names.add(name)
        relative = Path(source['path'])
        path = (root / relative).resolve()
        if relative.is_absolute() or not path.is_relative_to(root.resolve()):
            raise ValueError(f'Source path escapes repository: {name}')
        if not path.exists():
            raise ValueError(f'Missing integrated source: {name}: {relative}')
        if path.is_dir() and (path / '.git').exists():
            raise ValueError(f'Nested checkout instead of integrated source: {relative}')
    return manifest['sources']


def run(*args, cwd=ROOT):
    subprocess.run([str(arg) for arg in args], cwd=cwd, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['check', 'test', 'verify', 'web'])
    parser.add_argument('--maven', default=str(ROOT / ('mvnw.cmd' if os.name == 'nt' else 'mvnw')))
    parser.add_argument('--settings', type=Path, help='Optional Maven settings for a mirror/proxy')
    args = parser.parse_args()
    sources = validate_sources()
    if args.command == 'check':
        print(f'{len(sources)} integrated companion sources; no additional clone needed')
        return
    if args.command == 'web':
        run('npm', 'ci', cwd=ROOT / 'apps/web')
        run('npm', 'run', 'build', cwd=ROOT / 'apps/web')
        return
    maven = [args.maven, '-B', '-ntp']
    if args.settings:
        maven += ['-s', str(args.settings.resolve())]
    if args.command == 'test':
        maven += ['-pl', 'apps/server,apps/certificate-validator,content/filter-lists,libs/persistence', '-am', 'test']
    else:
        maven += ['verify']
    run(*maven)


if __name__ == '__main__':
    main()
