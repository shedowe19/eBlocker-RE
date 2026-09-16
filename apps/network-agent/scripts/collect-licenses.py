#!/usr/bin/env python3
"""Copy notices from pinned Go module sources; never execute downloaded code."""

import argparse
import json
from pathlib import Path
import re
import shutil
import subprocess


def collect(destination: Path) -> None:
    metadata = subprocess.check_output(["go", "list", "-m", "-json", "all"], text=True)
    decoder = json.JSONDecoder()
    modules = []
    while metadata.strip():
        module, consumed = decoder.raw_decode(metadata.lstrip())
        metadata = metadata.lstrip()[consumed:]
        if module.get("Main") or module["Path"].startswith("github.com/eblocker/eblocker/"):
            continue
        if "Replace" in module:
            raise SystemExit("Unexpected replacement of an external dependency")
        source = Path(module.get("Dir", ""))
        if not module.get("Dir") or not source.is_dir():
            raise SystemExit("Pinned dependency sources are missing; run go mod download first")
        name = module["Path"].replace("/", "_") + "@" + module["Version"]
        notices = []
        pattern = re.compile(r"^(licen[cs]e|copying|copyright|notice|patents|authors)([._-].*)?$", re.I)
        for item in sorted(source.rglob("*")):
            if not item.is_file() or item.is_symlink() or not pattern.fullmatch(item.name):
                continue
            relative = item.relative_to(source)
            target = destination / name / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(item, target)
            target.chmod(0o644)
            notices.append(relative.as_posix())
        if not notices:
            raise SystemExit("Dependency contains no discoverable license or copyright notice")
        modules.append({"path": module["Path"], "version": module["Version"], "notices": notices})
    goroot = Path(subprocess.check_output(["go", "env", "GOROOT"], text=True).strip())
    go_version = subprocess.check_output(["go", "env", "GOVERSION"], text=True).strip()
    standard = destination / go_version
    standard.mkdir(parents=True, exist_ok=True)
    for filename in ("LICENSE", "PATENTS"):
        source = goroot / filename
        if source.is_file():
            shutil.copyfile(source, standard / filename)
            (standard / filename).chmod(0o644)
    destination.mkdir(parents=True, exist_ok=True)
    (destination / "modules.json").write_text(json.dumps({"goVersion": go_version, "modules": modules}, indent=2) + "\n")
    for filename in ("go.mod", "go.sum"):
        shutil.copyfile(filename, destination / filename)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("destination", type=Path)
    collect(parser.parse_args().destination)
