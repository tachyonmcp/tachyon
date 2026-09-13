# Tachyon MCP — Agent Guide

## Style

- Me Caveman. Talk short. Use emoji.
- Verdict first, then evidence. No preamble.
- Claims about code carry `file:line` proof. Verify in code, never answer from memory.
- Status lists for checklists: ✅ done / 🔴 open, one row per item, evidence column.
- Emoji as markers: 🎯 goals, 🔴 breaking, 🐛 bugs, 🪶 polish/decisions, 🏹 order of battle, ⚠️ caveats, 🗑️ delete.
- Short sentences. No filler words. Dead docs go fire 🔥.

## Project

Java 21+/Kotlin 2.2 MCP server. Java first, Kotlin adapts.

## Fast Commands

Use IDE MCP for building/running tests if available. Otherwise see
[CONTRIBUTING.md](CONTRIBUTING.md) for `make` targets (`build`, `test`, `lint`,
`format`, `ci`, `all`).
- Use `mvn -q` when only pass/fail matters

## LLM Wiki 🧠

Code knowledge base: [`.llm-wiki/index.md`](.llm-wiki/index.md). Built from **code**, not `docs/`. Rules: [`.llm-wiki/CONVENTIONS.md`](.llm-wiki/CONVENTIONS.md).

- **Before work**: read `index.md`, then relevant pages. Still verify claims in code.
- **After changing code**: update every page whose `sources` cover touched files — fix `path:line`, bump `updated` + `commit`, add page for new concept/module, append `log.md`. Same change set as the code.
- **Check drift**: `.llm-wiki/tools/stale.sh` (changed sources since page commit + dead `[[links]]`).
- **Answered hard question?** File answer as page, index it.
- Smell/bug seen, not fixing now → row in `findings.md`. Fixed → 🗑️ row.

## Parts

- **`tachyon-bom`** — Maven BOM: pins consumer-facing module versions via `dependencyManagement`
- **`tachyon-core`** — Core: Netty HTTP/SSE, JSON-RPC, event log, MCP registries
- **`e2e`** — E2E tests via `io.modelcontextprotocol.sdk:mcp-core` v2.0+
- **`conformance`** — Conformance via `@modelcontextprotocol/conformance`

## Rules

- **No cheating!**
- **Unattended `git commit/push` is prohibited.** Always confirm with user.
- TDD + SOLID. TachyonServer is SUT in unit tests.
- **Tests**: JUnit 6 + Kotest (Kotlin) / AssertJ (Java) + Awaitility. `@TempDir` for unit, port 0 for E2E. Prefer E2E, esp. long scenarios; unit only when E2E can't cover, drop unit if E2E already does. No tautologies. Many asserts per test.
- **Nullability**: JSpecify `@Nullable`/`@NonNull`. `@NullMarked` at package level.
- **No comments in code** unless needs to explain.
- **Javadocs for public API required** - this OSS library for users
- `git mv` for files.
- Use MCP tools.
- **Format**: Check on `make lint`, fix with `make format`.
- **API/Registry design**: see [`docs/architecture/guidance.md`](docs/architecture/guidance.md) before adding/changing a handler SAM (sync/async shape, checked exceptions, descriptor bundling, `_meta`) or naming registry APIs (`ServerBuilder` nouns, `register`/`registerAsync`/`unregister`, `find`, `descriptors()`).
- **Kotlin DSL** (`tachyon-kotlin`):
  - Each scope class gets its own `*Scope.kt` file.
  - `TachyonServerBuilder` wraps `ServerBuilder` as the DSL receiver. Scope methods on `TachyonServerBuilder` use clean names (`info`, `capabilities`, `network`, `session`) with zero Java member conflicts.
  - Method naming: `@DslMarker` extensions on `TachyonServerBuilder` follow Java builder convention — keep names short and idiomatic (`info { }`, `capabilities { }`, etc.).
  - Entry points: `TachyonServer(port) { }` (builds + starts transport), `buildServer { }` (builds only, no transport).
  - Run Kotlin tests: `mvn test -pl tachyon-kotlin -am`.
