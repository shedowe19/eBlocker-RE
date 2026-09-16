// SPDX-License-Identifier: EUPL-1.2
import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../', import.meta.url));
const lock = JSON.parse(await readFile(path.join(root, 'package-lock.json'), 'utf8'));
const sections = ['eBlocker console – third-party notices\n'];
for (const [relative, entry] of Object.entries(lock.packages).sort(([a], [b]) =>
    a.localeCompare(b),
)) {
    if (!relative || entry.dev) continue;
    const directory = path.resolve(root, relative);
    if (!directory.startsWith(path.join(root, 'node_modules') + path.sep))
        throw new Error('Invalid dependency path');
    const metadata = JSON.parse(await readFile(path.join(directory, 'package.json'), 'utf8'));
    const licenses = (await readdir(directory))
        .filter((name) => /^(licen[sc]e|copying|notice)([._-].*)?$/i.test(name))
        .sort();
    if (!licenses.length) throw new Error(`Missing license text for ${metadata.name}`);
    sections.push(`${metadata.name} ${entry.version}\n${'='.repeat(60)}`);
    for (const name of licenses) sections.push(await readFile(path.join(directory, name), 'utf8'));
}
await writeFile(path.join(root, 'dist/THIRD_PARTY_NOTICES.txt'), sections.join('\n\n'));
await writeFile(
    path.join(root, 'dist/LICENSE.txt'),
    await readFile(path.resolve(root, '../../LICENSE.md')),
);
