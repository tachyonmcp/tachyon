#!/usr/bin/env python3
"""Audit tracked wiki pages. Default reports; --check fails on findings."""

import argparse
import re
import subprocess
from pathlib import Path
from urllib.parse import unquote, urlsplit

from publish_wiki import split_frontmatter

LINK = re.compile(r"(?<!!)\[([^\[\]]+)\]\(([^\s)]+)\)")
LINE_REF = re.compile(r"(?:\.(?:java|kt|kts|xml|ts|ya?ml|properties|sh|py)(?::|,)|`:)\d+|#L\d+")


def prose(body):
    fence = None
    for number, line in enumerate(body.splitlines(), 1):
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker:
            token = marker[1]
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            continue
        if fence is None:
            yield number, line


def git(repo, *args):
    return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True).stdout


def audit(repo):
    pages = sorted(repo / name for name in git(repo, "ls-files", "-z", "--", ".llm-wiki").split("\0")
                   if name.endswith(".md") and "/tools/" not in name and (repo / name).exists())
    names = {}
    issues = []

    def report(kind, page, detail):
        issues.append(f"{kind} {page.relative_to(repo)}: {detail}")

    for page in pages:
        if page.stem in names:
            report("🔗 duplicate page", page, page.stem)
        names[page.stem] = page

    inbound = set()
    for page in pages:
        meta, body = split_frontmatter(page.read_text(encoding="utf-8"))
        if not meta:
            report("⚠️ metadata", page, "missing frontmatter")
        sources_text = meta.get("sources", "")
        sources = [s.strip().strip("\"'") for s in sources_text.strip("[]").split(",") if s.strip()]
        commit = meta.get("commit", "")
        valid_commit = False
        if commit:
            try:
                git(repo, "rev-parse", "--verify", "--end-of-options", commit + "^{commit}")
                valid_commit = True
            except subprocess.CalledProcessError:
                report("⚠️ unknown commit", page, commit)
        elif sources:
            report("⚠️ metadata", page, "sources require commit")
        if sources and valid_commit:
            for label, revisions in [("🔴 committed drift", [commit, "HEAD"]), ("🪶 working-tree drift", ["HEAD"])]:
                changed = git(repo, "diff", "--name-only", "-z", *revisions, "--", *sources).split("\0")
                changed = sorted(filter(None, changed))
                if changed:
                    report(label, page, ", ".join(changed))

        for number, line in prose(body):
            for target in re.findall(r"\[\[([^\]]+)\]\]", line):
                target = target.split("|", 1)[0].split("#", 1)[0]
                if target not in names:
                    report("🔗 dead wiki link", page, f"[[{target}]] (body line {number})")
                elif target != page.stem:
                    inbound.add(target)
            # Inline examples in conventions are prose code, not actual links.
            for match in LINK.finditer(line):
                if line[:match.start()].count("`") % 2:
                    continue
                label, target = match.groups()
                label = label.strip("`")
                url = urlsplit(target.strip("<>"))
                if url.scheme or url.netloc or not url.path:
                    continue
                source = (page.parent / unquote(url.path)).resolve()
                if not source.is_relative_to(repo) or not source.exists():
                    report("🔗 dead source link", page, target)
                    continue
                if source.suffix in {".java", ".kt", ".kts"} and "#" in label:
                    symbol = label.rsplit("#", 1)[1]
                    if re.fullmatch(r"[A-Za-z_$][\w$]*", symbol) and not re.search(
                            r"\b" + re.escape(symbol) + r"\b", source.read_text(encoding="utf-8")):
                        report("⚠️ missing symbol", page, f"{label} in {target} (lexical check)")
            if page.name != "CONVENTIONS.md" and LINE_REF.search(line):
                report("🔢 line citation", page, f"body line {number}; cite Type#member")

    for name, page in names.items():
        if name != "index" and name not in inbound:
            report("🔗 orphan page", page, "no inbound [[link]]")
    return issues


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="exit 1 when any finding remains")
    args = parser.parse_args()
    repo = Path(git(Path(__file__).resolve().parent, "rev-parse", "--show-toplevel").strip()).resolve()
    issues = audit(repo)
    print("\n".join(issues) if issues else "✅ wiki fresh")
    return 1 if args.check and issues else 0


if __name__ == "__main__":
    raise SystemExit(main())
