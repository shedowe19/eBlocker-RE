# Source baseline

The root source baseline is pinned in `sources.lock.json`. `source/` describes the
3,114 tracked files at that commit. `companions/` contains the same inventory for
19 additional source repositories, including the DateAdapterJ dependency discovered
while reproducing the build. The aggregate is 8,222 tracked files.

Each inventory directory contains:

- `repository-manifest.json`: path, module, language, role, byte count, Git blob and SHA-256.
- `code-metrics.json`: physical source line counts and language totals.
- `dependency-inventory.csv`: declared Maven dependencies/plugins/parents and direct npm packages.
- `configuration-inventory.csv`: property definitions and `@Named` injection locations, without values.
- `integration-inventory.csv`: source anchors for process execution, Redis, privileges and systemd.
- `package-manifest.json`: files under package/deb paths.
- `ui-states.csv`: statically matched UI state registration anchors.

These are reproducible static inventories. They are not a claim of a line-by-line
semantic audit, full transitive dependency resolution, dynamic call graph, measured
cyclomatic complexity, production behavior, or complete CVE coverage. Regex-based
anchors may require manual interpretation. C/C++ native repositories have packaging
outside `/package/` and `/deb/`; their complete files remain in the repository manifest.

The full ordered HTTP declaration contract is in
`eblocker-icapserver/src/test/resources/contracts/http-routes.json` (from the repo root).
`docs/refactor/api-parity.csv` makes it easy to inspect methods, names and flags.
Request/response body schemas and handler side effects require separate characterization.

Reproduce a companion inventory with:

```bash
python3 scripts/inventory.py --repository .workspace/sources/RestExpress \
  --ref <commit-from-sources.lock.json> --output docs/baseline/companions/RestExpress
```

The inventory intentionally reports the original baseline, not edited working-tree
files. Re-run with `--ref HEAD` after committing if a new baseline is required.
