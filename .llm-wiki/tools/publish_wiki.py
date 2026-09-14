#!/usr/bin/env python3
"""Renders .llm-wiki into a GitHub Wiki checkout.

Usage: .llm-wiki/tools/publish_wiki.py <wiki-dir> [--repo-url URL] [--sha SHA]

- Flattens pages (GitHub Wiki resolves [[page]] by file name), index.md -> Home.md.
- Moves YAML frontmatter into a page footer.
- Links repo-relative `path[:line[-line]]` code refs to the blob at the page's commit.
- Generates _Sidebar.md from index.md and _Footer.md.
"""

import argparse
import os
import re
import subprocess
import sys
from pathlib import Path

WIKI = Path(__file__).resolve().parent.parent
REPO = WIKI.parent
SKIP_DIRS = {"tools"}
CODE_REF = re.compile(r"`([A-Za-z0-9_.\-]+(?:/[A-Za-z0-9_.\-]+)+)(?::(\d+)(?:-(\d+))?)?`")
WIKI_LINK = re.compile(r"\[\[([^\]|]+)\]\]")
FENCE = re.compile(r"^\s*(```|~~~)")


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(REPO), *args], check=True, capture_output=True, text=True).stdout.strip()


def split_frontmatter(text: str) -> tuple[dict[str, str], str]:
    if not text.startswith("---\n"):
        return {}, text
    end = text.find("\n---\n", 4)
    if end < 0:
        return {}, text
    meta = {}
    for line in text[4:end].splitlines():
        key, sep, value = line.partition(":")
        if sep:
            meta[key.strip()] = value.strip()
    return meta, text[end + 5 :]


def link_code_refs(body: str, blob_base: str) -> str:
    def replace(match: re.Match[str]) -> str:
        path, start, end = match.group(1), match.group(2), match.group(3)
        if not (REPO / path).exists():
            return match.group(0)
        anchor = f"#L{start}" + (f"-L{end}" if end else "") if start else ""
        return f"[{match.group(0)}]({blob_base}/{path}{anchor})"

    out, in_fence = [], False
    for line in body.splitlines(keepends=True):
        if FENCE.match(line):
            in_fence = not in_fence
        out.append(line if in_fence else CODE_REF.sub(replace, line))
    return "".join(out)


def footer(meta: dict[str, str], repo_url: str, source: Path) -> str:
    parts = [f"📄 source [`{source.as_posix()}`]({repo_url}/blob/HEAD/{source.as_posix()})"]
    if meta.get("updated"):
        parts.append(f"updated {meta['updated']}")
    if meta.get("commit"):
        parts.append(f"verified at [`{meta['commit']}`]({repo_url}/commit/{meta['commit']})")
    if meta.get("tags"):
        parts.append(f"tags {meta['tags']}")
    return "\n\n---\n\n<sub>" + " · ".join(parts) + "</sub>\n"


def sidebar(index_body: str) -> str:
    lines = ["**[[Home]]**", ""]
    for line in index_body.splitlines():
        if line.startswith("## "):
            lines += ["", f"**{line[3:].strip()}**", ""]
        else:
            lines += [f"- [[{name}]]" for name in WIKI_LINK.findall(line.split("|")[1] if line.startswith("|") else "")]
    return "\n".join(lines).strip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("wiki_dir", type=Path)
    parser.add_argument("--repo-url", default=f"https://github.com/{os.environ.get('GITHUB_REPOSITORY', 'kpavlov/tachyon')}")
    parser.add_argument("--sha", default=None)
    args = parser.parse_args()
    sha = args.sha or git("rev-parse", "HEAD")

    pages = sorted(p for p in WIKI.rglob("*.md") if not SKIP_DIRS.intersection(p.relative_to(WIKI).parts))
    names: dict[str, Path] = {}
    for page in pages:
        if page.stem in names:
            print(f"duplicate page name '{page.stem}': {names[page.stem]} and {page}", file=sys.stderr)
            return 1
        names[page.stem] = page

    out = args.wiki_dir
    for old in out.glob("*.md"):
        old.unlink()

    index_body = ""
    for stem, page in names.items():
        meta, body = split_frontmatter(page.read_text(encoding="utf-8"))
        blob_base = f"{args.repo_url}/blob/{meta.get('commit') or sha}"
        rendered = link_code_refs(body.lstrip("\n"), blob_base) + footer(meta, args.repo_url, page.relative_to(REPO))
        if stem == "index":
            index_body = body
            rendered = rendered.replace("[[index]]", "[[Home]]")
        (out / ("Home.md" if stem == "index" else f"{stem}.md")).write_text(rendered, encoding="utf-8")

    (out / "_Sidebar.md").write_text(sidebar(index_body), encoding="utf-8")
    (out / "_Footer.md").write_text(
        f"<sub>🤖 Generated from [`.llm-wiki`]({args.repo_url}/tree/{sha}/.llm-wiki) at "
        f"[`{sha[:8]}`]({args.repo_url}/commit/{sha}). Edit in the repo — changes here are overwritten.</sub>\n",
        encoding="utf-8",
    )
    print(f"✅ rendered {len(names)} pages into {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
