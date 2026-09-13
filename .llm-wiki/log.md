# 📜 Wiki log

Append-only. Newest last. `grep "^## \[" .llm-wiki/log.md | tail -5`

## [2026-09-13] ingest | initial sweep of code @ 582f9c52

- Read main sources of `tachyon-api`, `tachyon-core` (server, transport, protocol, session, features, json, observability), `tachyon-extensions`, `tachyon-kotlin`, `tachyon-kotlin-kt-schema`, `tachyon-testkit`, `integrations/*` (tracked modules), test layout of `e2e`, `conformance`.
- Skipped: generated `models/` + codecs (ts2java output under `target/`), `docs/` (by rule), untracked files.
- Created: CONVENTIONS, index, overview, 15 concept pages, 7 module pages, findings, `tools/stale.sh`.
- Linked from `AGENTS.md` (§ LLM Wiki) with upkeep rules.

## [2026-09-13] ingest | 5821ad56 request mapper consolidation (#336)

- New `AbstractMcpRequestMapper`; both version mappers now `final`, only `convert` differs (2026 falls back to 2025 codecs). Non-object params ⇒ `invalid_params`.
- `ExtensionNegotiationHandler` swallows mapping errors, dispatcher reports them.
- Updated: protocol-versions, extensions, tachyon-core. Commit bump only: findings, testing.
