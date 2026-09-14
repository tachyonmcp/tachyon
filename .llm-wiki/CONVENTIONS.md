---
title: Wiki conventions (schema)
tags: [meta]
updated: 2026-09-13
commit: 582f9c52
---

# 📐 LLM Wiki — conventions

LLM owns this dir. Humans read. Source of truth = **code**, never `docs/`, never memory.

## 🗂️ Layout

| Path | What |
|---|---|
| `index.md` | Catalog. Every page, one line. Read first. |
| `overview.md` | Modules, deps, versions, entry points |
| `concepts/*.md` | Cross-cutting mechanics (lifecycle, sessions, SSE…) |
| `modules/*.md` | One page per Maven module / module group |
| `findings.md` | 🐛/🪶/⚠️ smells, stale javadoc, open questions found while reading code |
| `tools/stale.sh` | Lists pages whose `sources` changed since page `commit` |
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

- Claims carry `path:line` proof. Repo-relative paths (clickable).
- Line numbers rot. On edit near a cited line → re-verify, fix number.
- Link pages with Obsidian double-bracket wiki links around the file stem (no dir, no `.md`), e.g. link to `sessions.md` by its stem.
- Caveman style, emoji markers (see `AGENTS.md`). Ignore untracked files.
- No copy of javadoc. Explain *why/how it connects*, not *what signature says*.

## 🔁 Operations

**Ingest (code changed)**
1. `git diff --name-only <page commit>..HEAD` or run `.llm-wiki/tools/stale.sh`.
2. Re-read changed code. Update every page whose `sources` match.
3. Fix `path:line`, bump `updated` + `commit`.
4. New concept/module with no page → create page, add to `index.md`.

**Query**
1. Read `index.md` → drill pages → verify in code before answering.
2. Good synthesized answer (comparison, trace, decision) → file as new page, index it.

**Lint**
- Run `tools/stale.sh`. Check: orphan pages (no inbound wiki link), dead `path:line`, contradictions between pages, concepts mentioned w/o page, `findings.md` items fixed in code → 🗑️ remove.

Based on https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f
