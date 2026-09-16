#!/usr/bin/env python3
"""Reassemble and verify the original eBlocker Git bundle without overwriting files."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys


EXPECTED_BUNDLE_SHA256 = "baa879abb7da321bd416b57a92f0a6d174cf4515a3e46e6866ad69e729393e59"
EXPECTED_BUNDLE_SIZE = 36_601_856
EXPECTED_PART_COUNT = 35
EXPECTED_PART_SIZE = 1_048_576


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--output", type=Path,
        help="Destination bundle (default: original-history.bundle beside this script)",
    )
    args = parser.parse_args()
    base = Path(__file__).resolve().parent
    output = args.output if args.output is not None else base / "original-history.bundle"
    manifest = json.loads((base / "bundle-manifest.json").read_text(encoding="utf-8"))
    bundle = manifest["bundle"]
    parts = manifest["parts"]
    if manifest["format_version"] != 1:
        raise ValueError("Unsupported manifest format")
    if bundle["filename"] != "original-history.bundle":
        raise ValueError("Unexpected bundle filename")
    if bundle["sha256"] != EXPECTED_BUNDLE_SHA256 or bundle["size"] != EXPECTED_BUNDLE_SIZE:
        raise ValueError("Manifest does not describe the expected original bundle")
    if manifest["part_size"] != EXPECTED_PART_SIZE or len(parts) != EXPECTED_PART_COUNT:
        raise ValueError("Unexpected part size or count")

    # The manifest is the sole source of the ordered list. No wildcard matches.
    for index, part in enumerate(parts):
        expected_name = f"original-history.bundle.part-{index:03d}"
        if part["filename"] != expected_name:
            raise ValueError(f"Unexpected part name at index {index}")
        expected_size = min(EXPECTED_PART_SIZE, EXPECTED_BUNDLE_SIZE - index * EXPECTED_PART_SIZE)
        if part["size"] != expected_size:
            raise ValueError(f"Unexpected size in manifest: {expected_name}")
        if re.fullmatch(r"[0-9a-f]{64}", part["sha256"]) is None:
            raise ValueError(f"Invalid SHA256 in manifest: {expected_name}")
        path = base / expected_name
        if path.is_symlink() or not path.is_file() or path.resolve().parent != base:
            raise ValueError(f"Missing, invalid or symlinked part: {expected_name}")

    # Exclusive creation also rejects existing symlinks and prevents overwriting.
    destination = output.open("xb")
    try:
        complete_hash = hashlib.sha256()
        complete_size = 0
        with destination:
            for part in parts:
                path = base / part["filename"]
                data = path.read_bytes()
                if len(data) != part["size"]:
                    raise ValueError(f"Wrong part size: {part['filename']}")
                if hashlib.sha256(data).hexdigest() != part["sha256"]:
                    raise ValueError(f"SHA256 mismatch: {part['filename']}")
                destination.write(data)
                complete_hash.update(data)
                complete_size += len(data)
            if complete_size != EXPECTED_BUNDLE_SIZE:
                raise ValueError("Wrong reconstructed bundle size")
            if complete_hash.hexdigest() != EXPECTED_BUNDLE_SHA256:
                raise ValueError("Reconstructed bundle SHA256 mismatch")
    except BaseException:
        destination.close()
        output.unlink(missing_ok=True)
        raise
    print(f"Verified bundle: {output.resolve()}")
    print(f"SHA256: {EXPECTED_BUNDLE_SHA256}")
    print(f"Bytes: {complete_size}; parts: {len(parts)}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
