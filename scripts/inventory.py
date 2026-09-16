#!/usr/bin/env python3
"""Reproduce source inventories from a Git commit, without executing appliance code."""
import argparse
import collections
import csv
import hashlib
import io
import json
import re
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}


def snapshot(repository, ref):
    listing = subprocess.check_output(['git', '-C', str(repository), 'ls-tree', '-rz', ref])
    entries = []
    for record in listing.split(b'\0'):
        if not record:
            continue
        info, name = record.split(b'\t', 1)
        mode, kind, sha = info.decode().split()
        if kind == 'blob':
            entries.append((name.decode(), mode, sha))
    result = subprocess.run(['git', '-C', str(repository), 'cat-file', '--batch'],
                            input=('\n'.join(e[2] for e in entries) + '\n').encode(),
                            stdout=subprocess.PIPE, check=True)
    stream = io.BytesIO(result.stdout)
    for name, mode, sha in entries:
        header = stream.readline().decode().split()
        if len(header) != 3 or header[1] != 'blob':
            raise RuntimeError(f'Cannot read {name}: {header}')
        data = stream.read(int(header[2]))
        if stream.read(1) != b'\n':
            raise RuntimeError(f'Invalid Git blob framing: {name}')
        yield name, mode, sha, data


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n')


def write_csv(path, columns, rows):
    with path.open('w', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=columns, lineterminator='\n')
        writer.writeheader()
        writer.writerows(rows)


def inventory(repository, ref, output):
    output.mkdir(parents=True, exist_ok=True)
    commit = subprocess.check_output(['git', '-C', str(repository), 'rev-parse', ref], text=True).strip()
    manifest, metrics, settings, integrations, dependencies, ui = [], [], [], [], [], []
    for name, mode, sha, data in snapshot(repository, commit):
        path = Path(name)
        try:
            source = data.decode('utf-8')
        except UnicodeDecodeError:
            source = None
        language = {'.java': 'Java', '.js': 'JavaScript', '.html': 'HTML', '.scss': 'SCSS',
                    '.sh': 'Shell', '.py': 'Python', '.go': 'Go', '.c': 'C', '.h': 'C',
                    '.cc': 'C++', '.cpp': 'C++', '.hh': 'C++', '.hpp': 'C++',
                    '.json': 'JSON', '.xml': 'XML', '.md': 'Markdown'}.get(path.suffix, 'other')
        role = 'test' if '/test/' in name or '.spec.' in name else 'source'
        if any(part in path.parts for part in ('surrogates', 'npm_integrations', 'img', 'img-src')):
            role = 'bundled-content'
        manifest.append(dict(path=name, module=path.parts[0] if len(path.parts) > 1 else 'root',
                             language=language, role=role, bytes=len(data), mode=mode,
                             git_blob=sha, sha256=hashlib.sha256(data).hexdigest()))
        if source is None:
            continue
        if language in ('Java', 'JavaScript', 'Shell', 'Python', 'Go', 'C', 'C++'):
            metrics.append(dict(path=name, lines=len(source.splitlines()),
                                note='Physical lines; not cyclomatic complexity or semantic review coverage'))
        for line, text in enumerate(source.splitlines(), 1):
            keys = re.findall(r'@Named\("([^"]+)"\)', text)
            if path.suffix == '.properties' and not text.lstrip().startswith(('#', '!')):
                match = re.match(r'\s*([A-Za-z0-9_.-]+)\s*[=:]', text)
                if match:
                    keys.append(match[1])
            settings.extend(dict(key=key, path=name, line=line,
                                 usage='injection' if '@Named' in text else 'definition') for key in keys)
            for category, pattern in {
                'process': r'ProcessBuilder|Runtime\.getRuntime\(\)\.exec|scriptRunner\.',
                'redis': r'\bjedis\.|\bredis\.|JedisDataSource',
                'service': r'^(?:ExecStart|ExecStop|ExecReload|User|Group|After|Before|Requires|Wants)=',
                'privilege': r'\bsudo\b|CAP_NET_ADMIN|CAP_NET_RAW',
            }.items():
                if re.search(pattern, text):
                    integrations.append(dict(category=category, path=name, line=line))
            for match in re.finditer(r'\.state\(\s*[\'"]([^\'"]+)', text):
                ui.append(dict(state=match[1], path=name, line=line))
        if path.name == 'pom.xml':
            tree = ET.fromstring(source)
            for kind in ('dependency', 'plugin', 'parent'):
                for item in tree.findall('.//m:' + kind, NS):
                    dependencies.append(dict(path=name, ecosystem='maven', kind=kind,
                                             group=item.findtext('m:groupId', default='', namespaces=NS),
                                             artifact=item.findtext('m:artifactId', default='', namespaces=NS),
                                             version=item.findtext('m:version', default='inherited', namespaces=NS)))
        if path.name == 'package.json':
            package = json.loads(source)
            for scope in ('dependencies', 'devDependencies'):
                for artifact, version in package.get(scope, {}).items():
                    dependencies.append(dict(path=name, ecosystem='npm', kind=scope,
                                             group='', artifact=artifact, version=version))
    write_json(output / 'repository-manifest.json', {'commit': commit, 'files': manifest})
    write_json(output / 'code-metrics.json', {'commit': commit, 'files': sorted(metrics, key=lambda row: -row['lines']),
                                            'languages': dict(collections.Counter(r['language'] for r in manifest))})
    write_csv(output / 'configuration-inventory.csv', ['key', 'path', 'line', 'usage'], settings)
    write_csv(output / 'integration-inventory.csv', ['category', 'path', 'line'], integrations)
    write_csv(output / 'dependency-inventory.csv', ['path', 'ecosystem', 'kind', 'group', 'artifact', 'version'], dependencies)
    write_csv(output / 'ui-states.csv', ['state', 'path', 'line'], ui)
    package_files = [r for r in manifest if '/package/' in r['path'] or '/deb/' in r['path']]
    write_json(output / 'package-manifest.json', {'commit': commit, 'files': package_files})
    print(f'{repository.name}@{commit[:12]}: {len(manifest)} files, {len(settings)} configuration anchors, '
          f'{len(integrations)} integration anchors, {len(package_files)} package files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repository', type=Path, default=ROOT)
    parser.add_argument('--ref', default=json.loads((ROOT / 'sources.lock.json').read_text())['baseline'])
    parser.add_argument('--output', type=Path, default=ROOT / 'docs' / 'baseline' / 'source')
    args = parser.parse_args()
    inventory(args.repository, args.ref, args.output)


if __name__ == '__main__':
    main()
