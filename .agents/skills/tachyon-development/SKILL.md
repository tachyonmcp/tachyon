---
name: tachyon-development
description: >-
    Apply Tachyon MCP project rules when designing, implementing, reviewing,
    or testing Java and Kotlin server code, MCP protocol behaviour,
    E2E fixtures, concurrency, JSON, and schemas.
---

# Prime directives

- Apply SOLID. Model invalid states out of types/builders; validate remaining invariants at construction.
- Use TDD. Start observable protocol changes with an E2E acceptance test.
- Never serialize structured/wire content with generic `toString()`; records/POJOs emit debug text,
  not JSON. Use `PayloadSerializer`, the correct codec, or domain mapping, including fallbacks.
  `toString()` is valid only for strings, scalar numbers/booleans, and specified formats such as
  `Instant` RFC-3339.
- Prefer imports. Use an FQN only for a genuine name collision, and only on the non-domain type.
- Prefer `final` fields/locals and `final var` when the right-hand side makes the type obvious;
  use an explicit type when clearer.
- Avoid `synchronized` on Java 21 virtual-thread paths. Prefer `ReentrantLock`; offload unavoidable
  pinning or native calls to a suitable executor.
- Kotlin API refactors follow the adapter shapes in
  [`docs/architecture/guidance.md`](../../../docs/architecture/guidance.md#kotlin-adapter-shape).
- Java `ServerBuilder` is the implementation source of truth. Kotlin adds only thin adaptation for
  suspend lambdas and Kotlin-specific types; never duplicate validation or registration logic.
- Keep [`weather-mcp`](../../../examples/weather-mcp) and
  [`weather-mcp-kotlin`](../../../examples/weather-mcp-kotlin) functionally identical.
- Keep files focused. Above 300 lines, consider splitting by responsibility; generated code is exempt.
- Write self-documenting code. Do not add docs or comments that repeat the code.
- A public API change (new/changed method, param, wire field, or behaviour contract like TTL/null
  semantics) is not done until its docs are done: update the relevant file under `docs/`, this
  skill, and/or `docs/architecture/guidance.md` in the same change. Don't defer it to a follow-up.

# Test Rules 🧪

- Prefer E2E; use unit tests only for edge cases E2E cannot cover.
- Test intent, not line coverage. No tautologies or abstract-base-only tests; exercise shared behavior
  through concrete implementations or E2E.
- Java: JUnit 6 + Pioneer, AssertJ fluent, short spec-reference comments, parameterized tests when appropriate.
- For throwing handlers, throw a real checked exception directly from the lambda; do not hide the
  `throws Exception` SAM contract behind try/catch or test only unchecked failures.
- Optional parameters in fixtures (e.g. `@McpTool` methods): use JSpecify `@Nullable`, not `Optional<T>` —
  `Optional` is for result types. Keep one `Optional` parameter per suite only to verify that binding path.
- Test wire behavior through real codecs or clients; use concrete mapper tests for mapper-only edge cases.
  For omitted/null claims, serialize with the real codec and assert the JSON; a null model field is insufficient.
- E2E: use `McpTestClients`/versioned testkit clients and `JsonRpcResponseAssert` (its static
  `assertThat` coexists with AssertJ). Narrow with `isSuccess()`/`isJsonRpcError()`, then use typed
  assertions (`hasId`, content/result assertions, or error code/message/data assertions). Prefer them
  over `inPath`, which can silently no-op. Assert full and minimal payloads; use `hasResult` or
  `hasContentExactly` for complete shapes, and targeted assertions only when exhaustive. See
  `docs/testkit.md` and `DiscoverResponseAssert`.
- Use JsonUnit + AssertJ only when testkit cannot model the shape (for example nested task results or
  non-testkit clients). Assert the full `// language=JSON` payload; avoid path fragments and field
  getters. Confirm expected JSON against a real run. Ignore paths/order/extras/null only for genuine
  nondeterminism, never by default.
- `// language=json` before JSON strings.
- Kotlin: use Kotest assertions and `kotest-assertions-json`; assert full JSON.

## Shared E2E servers

- Shared singleton servers are production-parity SUTs. Keep configuration/registries immutable after
  startup; tests that mutate registration use isolated `startServer(...)` instances.
- Keep session mode explicit and invariant: stateful fixtures enable sessions; stateless fixtures do not.
- Parallel JUnit is background pressure, not concurrency proof. Coordinate simultaneous E2E clients
  with barriers/latches, virtual threads, and bounded timeouts; never fixed sleeps.
- Stateful concurrency must prove unique sessions, parallelism across/within sessions, isolation for
  repeated IDs, and independent termination. Stateless concurrency must prove no session ID and no
  response/client-data crossover.
- Test observable server behaviour through real clients. Don't add tests for test helpers.

# JSON/JSON Schemas

- Reuse `dev.tachyonmcp.api.json`; do not add JSON/schema parsing helpers.
- Static schema → parse text block. `ObjectMapper.readTree("""...""")` or shared `parseJson(String)` helper. `// language=json` for IDE.
- Imperative `JsonNodeFactory` only for runtime-computed schemas.

# Logging policy

| Level                       | Use                                                                                |
|-----------------------------|------------------------------------------------------------------------------------|
| ERROR                       | Immediate action — ops enables Rollbar+PagerDuty alerting                          |
| WARN                        | Action needed, can wait to next business day                                       |
| INFO (default on)           | Normal-operation info                                                              |
| DEBUG (off on PROD, on DEV) | Trace business logic                                                               |
| TRACE (off by default)      | Raw request/response dump — leaks confidential data and untrusted input if left on |

[Logging policy explanation](https://kpavlov.me/blog/logging-policy/)
