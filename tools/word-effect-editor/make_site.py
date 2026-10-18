#!/usr/bin/env python3
"""Stage the static Word Effect Studio site for GitHub Pages / a static host.

The editor needs exactly four files; tests, README and build artifacts are not
part of the runtime. This script copies them into a clean directory that can be
uploaded to a separate GitHub repository or served by any static host.

Usage:
    python tools/word-effect-editor/make_site.py
    python tools/word-effect-editor/make_site.py --out build/word-effect-studio
"""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

SITE_FILES = ("index.html", "engine.js", "templates.js", "app.js")


def main() -> int:
    here = Path(__file__).resolve().parent
    repo_root = here.parents[1]
    parser = argparse.ArgumentParser(
        description="Stage the four static files of Word Effect Studio."
    )
    parser.add_argument(
        "--out",
        default=str(repo_root / "build" / "word-effect-studio"),
        help="output directory (default: <repo>/build/word-effect-studio)",
    )
    args = parser.parse_args()

    missing = [name for name in SITE_FILES if not (here / name).is_file()]
    if missing:
        print("missing site files: " + ", ".join(missing), file=sys.stderr)
        return 1

    out = Path(args.out).resolve()
    out.mkdir(parents=True, exist_ok=True)
    for name in SITE_FILES:
        shutil.copy2(here / name, out / name)

    total = sum((out / name).stat().st_size for name in SITE_FILES)
    print(f"staged {len(SITE_FILES)} files ({total / 1024:.1f} KiB) -> {out}")
    for name in SITE_FILES:
        print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
