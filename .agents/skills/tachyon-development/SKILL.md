---
name: tachyon-development
description: Apply Tachyon MCP project rules when designing, implementing, reviewing, or testing Java and Kotlin server code, MCP protocol behaviour, E2E fixtures, concurrency, JSON, and schemas.
---

# Prime directives

- ATDD: prefer E2E, unit only for edge cases E2E can't cover. Start e2e tests before prod code.
- No `toString()` as a serialization shortcut for structured/wire-facing content (resource
  results, prompt results, content-block mapping, anything that ends up in a JSON response).
  `toString()` on a record/POJO produces its debug form (`Foo[bar=1]`), not JSON — it silently
  corrupts the payload instead of failing loudly. Route structured values through the configured
  `PayloadSerializer`/codec, or through a proper domain-to-domain mapping (e.g. one content-block
  type to its counterpart), even for the "shouldn't normally happen" fallback branch. Reserve
  `toString()` for values it's actually correct for: `String` passthrough, numeric/boolean
  scalars, and well-specified formats like `Instant#toString()` (RFC-3339).
- Prefer an `import` over a fully-qualified name. Fall back to FQN only where two same-named
  types are genuinely both referenced in one file, and then prefer FQN-ing just the side that isn't
  this module's own domain type, importing the other, rather than FQN-ing both out of caution.
- `final var` over explicit types. Use `final` for fields/vars where possible.
- No `synchronized`: runs on java virtual threads
- Kotlin API refactors follow the adapter shapes in
  [`docs/architecture/guidance.md`](../../../docs/architecture/guidance.md#kotlin-adapter-shape).
- Java `ServerBuilder` is the implementation source of truth. Kotlin adds only thin adaptation for
  suspend lambdas and Kotlin-specific types; never duplicate validation or registration logic.
- Treat `../../../examples/weather-mcp` and `examples/weather-mcp-kotlin` as Rosetta Stone examples. Keep their
  MCP features, metadata, behaviour, and coverage functionally identical when changing either one.
- Keep Kotlin source files focused. At more than 300 lines, consider splitting by owned
  responsibility before adding code.
- A public API change (new/changed method, param, wire field, or behaviour contract like TTL/null
  semantics) is not done until its docs are done: update the relevant file under `docs/`, this
  skill, and/or `docs/architecture/guidance.md` in the same change. Don't defer it to a follow-up.

# Test Rules 🧪

- Java: AssertJ fluent. Short spec ref comment in method. JUnit6+JUnit Pioneer annotations. Prefer parametrized tests.
- Handlers that throw: test with a real checked exception thrown directly from the lambda (no try/catch) — exercises the `throws Exception` SAM contract, not just unchecked paths.
- "Omitted/null on wire" claims: serialize through the real codec (`CodecRegistry.codecFor(X.class).encodeToBytes(value)`, or `JsonRpcCodec.writeValueAsString`) and assert on the resulting JSON. Asserting a domain/model field is `null` only proves the mapper produced `null` — it trusts, but doesn't verify, that the codec omits it.
- E2E via `tachyon-testkit` clients (`Mcp20251125Client`/`Mcp20260728Client`/`McpTestClients`): assert responses through `dev.tachyonmcp.testkit.JsonRpcResponseAssert` — `import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;` (co-exists with `org.assertj.core.api.Assertions.assertThat`; overload resolution on argument type disambiguates both). `assertThat(response).isSuccess()`/`.isJsonRpcError()` narrows to branch-only assertions: success → `hasId`, `isToolError()`, `hasTextContent(expected)`, `hasStructuredContent(expectedJsonOrNode)`, `hasResultType(expected)`, `hasContentExactly(blocks...)`; error → `hasErrorCode`, `hasErrorMessage`, `hasErrorMessageContaining`, `hasErrorDataSatisfying`. Prefer these over a bare `assertThatJson(...).inPath(...)` fragment — a mistyped path silently no-ops, these don't. For the full result/content shape (not just a few fields), use `hasResult(expectedJsonNode)`/`hasContentExactly(...)` instead of chaining several targeted `hasXxx` calls, which can still miss an extra/wrong sibling field. See `docs/testkit.md` and `DiscoverResponseAssert` (2026-07-28 `server/discover`).
- Fall back to raw `JsonUnit` + AssertJ (`assertThatJson(actual).isEqualTo(expected)`, full `// language=JSON` block, dynamic values via `.formatted(...)`) where `JsonRpcResponseAssert` doesn't model the shape — nested payloads like `tasks/get`'s `result.result.*`, or clients/modules outside tachyon-testkit (official `io.modelcontextprotocol.sdk`, Kotlin's stack, etc.). Same full-payload rule applies: no `inPath(...)` fragments or field-by-field getters — both let an extra, missing, or wrong-shaped field pass silently. Derive `expected` from a real run (fail once, read the reported value, verify it's correct, lock it in) — never a hand-typed guess. `whenIgnoringPaths`/`IGNORING_ARRAY_ORDER`/`IGNORING_EXTRA_FIELDS`/`TREATING_NULL_AS_ABSENT` only for genuinely non-deterministic parts (timestamps, generated ids) — never the default style.
- `// language=json` before JSON strings.
- Kotlin: kotest-assertions, kotest-assertions-json for json payload testing. Assert full JSON

## E2E

- Use the `tachyon-testkit` clients (`McpTestClients`, `Mcp20251125Client`/`Mcp20260728Client`) and
  `JsonRpcResponseAssert` (see Test Rules above) rather than hand-rolled HTTP/JSON plumbing.
- Test the full payload (every attribute present) AND the minimal-attributes case
  (defaults/omitted-when-absent) — a single asserted attribute doesn't catch a missing/extra/wrong
  sibling field. Use `hasResult(...)`/full-body `assertThatJson`, or a few targeted `hasXxx` calls
  when the result is small and fully known (assertion-style rule: Test Rules above).

## Shared E2E servers

- Treat shared stateful/stateless singleton servers as production-parity SUTs, not just suite optimizations.
- Keep shared server config/registries immutable after startup. Tests that register or replace features use an isolated `startServer(...)`.
- Make session mode explicit and invariant: stateful fixtures enable sessions; stateless disable them. Don't switch a test's mode just to drop session-ID plumbing.
- JUnit parallel execution is background pressure, not concurrency proof. Add dedicated E2E scenarios coordinating simultaneous clients with barriers/latches, virtual threads, bounded timeouts; never fixed sleeps.
- For stateful concurrency, verify: unique active sessions, parallel requests across sessions, parallel requests within one session, response isolation even with repeated JSON-RPC IDs across sessions, and terminating one session without affecting others.
- For stateless concurrency, verify: parallel initialization returns no session ID, concurrent requests never cross responses or client data.
- Test observable server behaviour through real clients. Don't add tests for test helpers.

# JSON/JSON Schemas

- Avoid creating new helpers for parsing json/schemas, reuse `dev.tachyonmcp.api.json`
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
