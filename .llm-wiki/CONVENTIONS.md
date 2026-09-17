---
title: Wiki conventions (schema)
tags: [meta]
sources: [.llm-wiki/tools/, .agents/skills/tachyon-wiki/SKILL.md]
updated: 2026-09-17
commit: b9546c38
---

# 📐 LLM Wiki — conventions

LLM owns this dir. Humans read. Source of truth = **code**, never `docs/`, never memory.

## 🗺️ Index, not spec

Every page is derivative: an index into code, written to make code findable. It specifies nothing.

| Rule | Why |
|---|---|
| Page disagrees with code → page is wrong | Code shipped, page is a note about it |
| Never change code to match a page | No page is a requirement; requirements live in issues, javadoc, `docs/`, SEPs |
| Never cite a page as proof | Proof = `Type#member` + path into code |
| Page states what code *does* | Wish/plan/bug → `findings.md` row, never page prose |
| Page can't be verified in code → delete it | Unverifiable page misleads. Dead docs go fire 🔥 |

## 🗂️ Layout

| Path | What |
|---|---|
| `index.md` | Catalog. Every page, one line. Read first. |
| `overview.md` | Modules, deps, versions, entry points |
| `concepts/*.md` | Cross-cutting mechanics (lifecycle, sessions, SSE…) |
| `modules/*.md` | One page per Maven module / module group |
| `findings.md` | 🐛/🪶/⚠️ smells, stale javadoc, open questions found while reading code |
| `tools/stale.sh` | Audits tracked pages: committed/working-tree source drift, unknown commits, links, orphan pages, and lexical symbol checks. `--check` exits 1 on findings. |
| `tools/publish_wiki.py` | Renders wiki into GitHub Wiki checkout. Run by `.github/workflows/wiki.yml` on push to `main` |
## 📄 Page format

```markdown
---
title: <name>
tags: [concept|module|meta, ...]
sources: [repo-relative paths or dirs the page is derived from]
updated: YYYY-MM-DD
commit: <short sha the claims were verified against>
---
# <emoji> Title
Verdict/summary first. Then sections. Tables > prose.
```

- Claims carry symbol proof: link repo-relative file path (clickable), label with `Type#member` (or `Type` for type-level claims), e.g. `[MethodInvoker#forArguments](../tachyon-core/.../MethodInvoker.java)`.
- 🚫 No line numbers or ranges (`File.java`, `#L42`) — they rot on every edit. Name the class/method/field instead; non-code files (POM, YAML) cite file + element/property name.
- Member renamed/moved → fix label + path.
- Link pages with Obsidian double-bracket wiki links around the file stem (no dir, no `.md`), e.g. link to `sessions.md` by its stem.
- Caveman style, emoji markers (see `AGENTS.md`). Ignore untracked files.
- No copy of javadoc. Explain *why/how it connects*, not *what signature says*.

## 🔁 Operations

**Ingest (code changed)**
1. `git diff --name-only <page commit>..HEAD` or run `.llm-wiki/tools/stale.sh`.
2. Re-read changed code. Update every page whose `sources` match.
3. Fix renamed/moved `Type#member` citations, bump `updated` + `commit`.
4. New concept/module with no page → create page, add to `index.md`.

**Query**
1. Read `index.md` → drill pages → verify in code before answering.
2. Good synthesized answer (comparison, trace, decision) → file as new page, index it.

**Lint**
- Run `tools/stale.sh`. Check: orphan pages (no inbound wiki link), dead paths or `Type#member` symbols, leftover line-number citations, contradictions between pages, concepts mentioned w/o page, `findings.md` items fixed in code → 🗑️ remove.
- The audit reads only frontmatter metadata and skips fenced examples. It ignores untracked pages and sources. Working-tree drift remains visible after a page refresh until code is committed; unknown commits mean unverifiable history, never “fresh.” Symbol checks only detect absent names, not incorrect semantic claims ([audit.py](tools/audit.py)).
- Run script regressions: `python3 -m unittest discover -s .llm-wiki/tools -p 'test_*.py'`.
- Publication rewrites repository-relative Markdown citations to commit-pinned source URLs ([publish_wiki.py](tools/publish_wiki.py)).

Based on https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f
