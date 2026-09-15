---
name: tachyon-wiki
description: >-
  Maintain Tachyon's code-derived .llm-wiki: add or reorganize pages, deduplicate
  explanations, refresh source citations after code changes, and check wiki drift.
---

# Tachyon wiki

Read [CONVENTIONS.md](../../../.llm-wiki/CONVENTIONS.md) completely before wiki work.
It owns page format, evidence rules, layout, and ingest/query/lint procedures;
do not copy those rules into this skill. Then read the
[index](../../../.llm-wiki/index.md) and the relevant pages before verifying their sources.

## Page ownership

- Keep each behavior's explanation on one canonical page. Module catalogs and
  related concepts should link to it, not repeat its binding rules or examples.
- Native Tachyon annotations belong in `concepts/declarative-configuration.md`;
  Spring Boot discovery/configuration/lifecycle in `modules/spring-boot.md`;
  external library adapters in `modules/integrations.md`.
- When splitting pages, move the relevant `sources` with the content and update
  the index and inbound links. Preserve distinct information, not duplicate prose.

## Drift and scope

Run `.llm-wiki/tools/stale.sh` from the repository root. Its drift output includes
tracked working-tree changes and does not certify that cited symbols or
claims are correct. Inspect citations separately; include task-owned new files
when checking sources. Report unrelated drift without expanding a focused edit.

Use `--check` when a failing exit status is needed. Committed drift, working-tree
drift, and unknown commits are reported separately. Citation member checks are
lexical only; verify their meaning in code. Script changes require running
`python3 -m unittest discover -s .llm-wiki/tools -p 'test_*.py'`.

Edit the repository wiki only. Publication is handled by the existing workflow;
wiki maintenance does not authorize commits, pushes, or direct GitHub Wiki edits.
