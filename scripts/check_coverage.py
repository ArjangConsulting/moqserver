#!/usr/bin/env python3
"""Fail CI when server line coverage drops below a floor.

Reads the llvm-cov JSON export that `swift test --enable-code-coverage` writes (its path comes from
`swift test --show-codecov-path`) and counts only the package's own `Sources/` files, so
dependencies under `.build/` and the test targets don't skew the number.
"""

import argparse
import json
import sys


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("codecov_json", help="Path printed by `swift test --show-codecov-path`")
    parser.add_argument("--minimum", type=float, required=True, help="Minimum line coverage, in percent")
    parser.add_argument("--sources", default="/Sources/", help="Path fragment that marks first-party files")
    args = parser.parse_args()

    with open(args.codecov_json, encoding="utf-8") as handle:
        export = json.load(handle)

    covered = 0
    total = 0
    for data in export.get("data", []):
        for entry in data.get("files", []):
            name = entry.get("filename", "")
            if args.sources not in name or "/.build/" in name:
                continue
            lines = entry["summary"]["lines"]
            covered += lines["covered"]
            total += lines["count"]

    if total == 0:
        print(f"No files matching {args.sources!r} in {args.codecov_json}", file=sys.stderr)
        return 1

    percent = 100.0 * covered / total
    print(f"Server line coverage: {percent:.2f}% ({covered}/{total} lines), floor {args.minimum:.2f}%")
    if percent < args.minimum:
        print(f"Coverage {percent:.2f}% is below the {args.minimum:.2f}% floor.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
