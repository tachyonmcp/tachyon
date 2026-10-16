# Tech stack

What Tachyon is built with, and the rules that come with each choice. Versions live in the root
`pom.xml` `<properties>`; this page names only baselines. API and registry design: [guidance.md](guidance.md).

## Runtime

| Layer | Choice | Where | Rule |
|---|---|---|---|
| Language | Java 21+ (`java.version`) | every module | Java first. Virtual threads run handlers (below). |
| Kotlin | Kotlin 2.2 (`kotlin-language.version`), kotlinx-coroutines | `tachyon-kotlin`, `tachyon-kotlin-kt-schema` | Thin adapter over the Java API: [guidance.md § Kotlin adapter shape](guidance.md#kotlin-adapter-shape). |
| Transport | Netty 4.2 (`netty-codec-http`, `netty-handler`) | `tachyon-core` | HTTP + SSE. Never block the event loop. Native transport auto-detected: `io_uring` → `epoll` → `kqueue` → NIO. |
| JSON | Jackson 3 (`tools.jackson`) | `tachyon-core` | Streaming `JsonParser`/`JsonGenerator` via `CodecSupport` on wire paths, no databind. Annotations keep the `com.fasterxml.jackson.annotation` package; the Jackson 2 BOM only pins what other libraries pull in. |
| JSON Schema | networknt `json-schema-validator` | `tachyon-core` | Default input/output validator (`NetworkntJsonSchemaValidator`), behind `JsonSchemaValidator`. |
| Value types | Immutables (`value-annotations`) | `tachyon-api`, `tachyon-core` | `@Value.Immutable` + `@Value.Check` for invariants at construction. |
| Nullability | JSpecify | every module | `@NullMarked` per package (below). |
| Logging | SLF4J 2 API | every module | Levels: [tachyon-development § Logging policy](../.agents/skills/tachyon-development/SKILL.md#logging-policy). |
| Protocol models | Generated from `protocol/*.ts` by `ts2java.py` (Python 3) | `tachyon-core`, `generate-sources` | Generated types stop at the protocol method handler: [guidance.md § Protocol boundary](guidance.md#-protocol-boundary-map-at-the-method-handler). |

## Testing

| Tool | Use |
|---|---|
| JUnit 6 + JUnit Pioneer | Java tests, parameterized where it fits |
| AssertJ, JsonUnit (`json-unit-assertj`) | Java assertions; full JSON payloads |
| Kotest assertions (`kotest-assertions-json`) | Kotlin assertions |
| Awaitility | Async waits; never fixed sleeps |
| Mockito | Test doubles |
| `tachyon-testkit` | E2E clients and `JsonRpcResponseAssert` |
| `io.modelcontextprotocol.sdk:mcp-core` v2 | Independent client in `e2e` |
| `@modelcontextprotocol/conformance` (npx) | Official suites in `conformance` |
| JMH | `*Benchmark` for hot paths, `make jmh` gate |

Test rules: [tachyon-development § Test Rules](../.agents/skills/tachyon-development/SKILL.md#test-rules-).

## Build and quality

| Tool | Use |
|---|---|
| Maven 3.9 wrapper (`./mvnw`) | Build; `maven-enforcer-plugin` checks the version |
| Spotless (palantir-java-format, ktlint), Detekt | `make lint` / `make format` |
| SpotBugs + FindSecBugs | Static analysis during build |
| Revapi | Binary/source compatibility against `api.oldVersion` |
| JaCoCo, CycloneDX, Dokka, Javadoc | Coverage, SBOM, API docs |
| snips (Rust) | `make docs-check`: doc snippets from real source |

Setup and `make` targets: [CONTRIBUTING.md](../CONTRIBUTING.md).

## 🪶 Sync-first, virtual-thread contract

Blocking for I/O in `handle`/`read` is intended — handlers run on a server-executor virtual thread:

- `HandlerFutures.assumeVirtualThread()` guardrail at the top of every sync dispatch. Don't remove it.
- Never `synchronized`/native calls in a handler (pins the carrier thread) — use `ReentrantLock`.
- `HandlerFutures.joinInterruptibly(stage)` to block on a `CompletionStage` (restores interrupt flag, unwraps `ExecutionException`). Don't hand-roll `.get()`.

## ⚠️ `Optional<T>` vs `@Nullable` — pick by contract, not habit

JSpecify `@Nullable` is the baseline (`@NullMarked` at package level). Reach for `Optional<T>` only when absence is a first-class, must-handle part of the contract — not as a blanket null-replacement. Oracle's own `Optional` docs: primarily a method return type for a clear "no result" case where `null` would likely cause bugs, not a general substitute for every nullable value.

- `@Nullable T` — ordinary nullable state, cache/map-style `get`, DTO fields: `@Nullable String description()`, `@Nullable Value get(Key key)`, `session()`, `sessionId()`.
- `Optional<T>` — a lookup/search where "no result" is the point: `Optional<User> findUserById(id)`, `Optional<Path> resolveConfigFile()`.
- Collections: return empty, never wrap a `List`/`Map` in `@Nullable` or `Optional`.
- Avoid `Optional` for plain field-style getters (`Optional<String> getName()`), setters (`void setName(Optional<String>)`), or class fields — usually just awkward, rarely earns its ceremony.
- Parameters: `@Nullable T`, never `Optional<T>` — including optional arguments of `@McpTool`/`@McpPrompt`/`@McpResource` methods in docs, examples, and tests. `Optional` parameters stay supported for users; tests keep one only to cover that binding.
- Applied here: `InteractionContext.get(AttributeKey<T>)` is `Optional<T>` (map-style lookup, absence is a real branch); `sessionId()` stays `@Nullable String` (ordinary status field, same shape as `lifecycle()`/`session()`).
