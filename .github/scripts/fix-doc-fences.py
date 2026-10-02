#!/usr/bin/env python3
"""Restore fence language tags that snips 0.0.6 rewrites to CodeMirror mode names.

snips replaces the info string of every fence it fills with the CodeMirror mode of the
snippet's source file: `clike` for Java and Kotlin, `javascript` for JSON. GitHub and the docs
site highlight `java`, `kotlin` and `json` but not `clike`. `snips --check` ignores the tag, so
running this after `snips` keeps docs highlighted without making the check fail.

Usage: fix-doc-fences.py FILE.md [FILE.md ...]
"""

import re
import sys
from pathlib import Path

FENCE_BY_EXTENSION = {
    ".java": "java",
    ".kt": "kotlin",
    ".kts": "kotlin",
    ".json": "json",
    ".xml": "xml",
    ".md": "markdown",
}

MARKER = re.compile(r"^<!--\s*snips:\s*(?P<path>[^#>]+?)(?:#[^>]*?)?\s*-->\s*$")
FENCE = re.compile(r"^(?P<ticks>`{3,}|~{3,})(?P<tag>\S*)\s*$")


def fix(text: str) -> str:
    lines = text.split("\n")
    for i in range(len(lines) - 1):
        marker = MARKER.match(lines[i])
        fence = FENCE.match(lines[i + 1])
        if not marker or not fence:
            continue
        wanted = FENCE_BY_EXTENSION.get(Path(marker["path"]).suffix)
        if wanted and fence["tag"] != wanted:
            lines[i + 1] = fence["ticks"] + wanted
    return "\n".join(lines)


def main(paths: list[str]) -> int:
    for name in paths:
        path = Path(name)
        original = path.read_text(encoding="utf-8")
        fixed = fix(original)
        if fixed != original:
            path.write_text(fixed, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
