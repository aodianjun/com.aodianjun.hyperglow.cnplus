#!/usr/bin/env python3
"""Verify the HyperLyric plugin API contract fingerprint.

plugins/api/src/main/kotlin/com/lidesheng/hyperlyric/plugin/api/PluginApi.kt is a
FQCN-compatible copy of HyperLyric's plugin contract (hyperlyric.plugin.api v1).
Precompiled HyperLyric plugin ZIPs load without recompilation only because the
package name and every public declaration stay byte-identical to upstream; a
rename or reorder (enum constants, params, methods) silently breaks the plugin
ecosystem.

This computes a deterministic fingerprint over the source with comments and
whitespace removed, so documentation/comment and formatting edits are allowed,
while any rename/reorder/removal/add of a declaration changes the hash. It then
compares against the checked-in golden value in api-contract.fingerprint.

Update the golden value ONLY when the upstream HyperLyric contract itself
changes (and verify against upstream first), never to silence a rename here.

Usage:
    check-api-fingerprint.py            # compare against the golden value
    check-api-fingerprint.py --emit     # print the current fingerprint hash
"""

import hashlib
import os
import re
import sys

API_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "src/main/kotlin/com/lidesheng/hyperlyric/plugin/api/PluginApi.kt",
)
GOLDEN_FILE = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "api-contract.fingerprint"
)


def strip_comments_and_preserve_literals(source: str) -> str:
    """Return source with Kotlin comments removed, literals preserved verbatim.

    Removes //-line comments and nested /* */ block comments. Strings ("...", raw
    \"\"\"...\"\"\") and char literals are emitted as-is so comment-like text
    inside them is not mistaken for a comment.
    """
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""

        # Line comment // ...
        if c == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
            continue

        # Block comment /* ... */ (Kotlin nests them)
        if c == "/" and nxt == "*":
            depth = 1
            i += 2
            while i < n and depth > 0:
                if source[i] == "*" and i + 1 < n and source[i + 1] == "/":
                    depth -= 1
                    i += 2
                elif source[i] == "/" and i + 1 < n and source[i + 1] == "*":
                    depth += 1
                    i += 2
                elif source[i] == "\n":
                    out.append(" ")
                    i += 1
                else:
                    i += 1
            continue

        # Raw (triple-quoted) string """..."""
        if c == '"' and nxt == '"' and i + 2 < n and source[i + 2] == '"':
            out.append('"""')
            i += 3
            while i < n:
                if source[i] == '"' and i + 2 < n and source[i + 1] == '"' and source[i + 2] == '"':
                    out.append('"""')
                    i += 3
                    break
                out.append(source[i])
                i += 1
            continue

        # Regular string "..."
        if c == '"':
            i += 1
            out.append('"')
            while i < n:
                if source[i] == "\\":
                    out.append(source[i])
                    i += 1
                    if i < n:
                        out.append(source[i])
                        i += 1
                    continue
                if source[i] == '"':
                    out.append('"')
                    i += 1
                    break
                out.append(source[i])
                i += 1
            continue

        # Char literal 'x'
        if c == "'":
            i += 1
            out.append("'")
            while i < n:
                if source[i] == "\\":
                    out.append(source[i])
                    i += 1
                    if i < n:
                        out.append(source[i])
                        i += 1
                    continue
                if source[i] == "'":
                    out.append("'")
                    i += 1
                    break
                out.append(source[i])
                i += 1
            continue

        out.append(c)
        i += 1

    return re.sub(r"\s+", " ", "".join(out)).strip()


def fingerprint(source: str) -> str:
    normalized = strip_comments_and_preserve_literals(source)
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def main() -> int:
    with open(API_FILE, "r", encoding="utf-8") as fh:
        source = fh.read()
    current = fingerprint(source)

    if "--emit" in sys.argv:
        print(current)
        return 0

    if not os.path.exists(GOLDEN_FILE):
        print(
            f"error: golden fingerprint file missing: {GOLDEN_FILE}\n"
            f"Inspect the live API file for legitimate upstream contract changes, then:\n"
            f"  python3 {os.path.basename(__file__)} --emit > {GOLDEN_FILE}",
            file=sys.stderr,
        )
        return 1

    with open(GOLDEN_FILE, "r", encoding="utf-8") as fh:
        golden = fh.read().splitlines()

    golden_hash = None
    for line in golden:
        line = line.strip()
        if line and not line.startswith("#"):
            golden_hash = line.split()[0]
            break
    if golden_hash is None:
        print(f"error: no fingerprint value found in {GOLDEN_FILE}", file=sys.stderr)
        return 1

    if current == golden_hash:
        print(f"OK: PluginApi.kt matches the HyperLyric contract fingerprint "
              f"({current[:12]}...).")
        return 0

    print(
        f"error: PluginApi.kt contract fingerprint changed.\n"
        f"  expected (golden): {golden_hash}\n"
        f"  current           {current}\n"
        f"The package name and public declarations must stay byte-identical so\n"
        f"precompiled HyperLyric plugin ZIPs keep loading. Revert the rename/reorder,\n"
        f"or update the golden ONLY if the upstream HyperLyric contract changed:\n"
        f"  python3 {os.path.basename(__file__)} --emit > {GOLDEN_FILE}",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())