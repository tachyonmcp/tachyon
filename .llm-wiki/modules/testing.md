---
title: Testing
tags: [module, testing, e2e, conformance]
sources: [pom.xml, e2e/src/test/, conformance/, Makefile, reports/pom.xml, tachyon-core/src/test/, .github/workflows/build.yml, .github/workflows/release.yml]
updated: 2026-11-14
commit: 734f9997
---

# ✅ Testing

Verdict: E2E-first (AGENTS.md). Real server on port 0, clients = official MCP Java SDK (`mcp-core` 2.0.1, `pom.xml`) **and** raw testkit clients. E2E packages split by protocol version. Conformance via `@modelcontextprotocol/conformance` with baselines.

## 🏃 Run

[ResourcePromptAttributesE2eTest](../../e2e/src/test/kotlin/dev/tachyonmcp/e2e/ResourcePromptAttributesE2eTest.kt) checks binary icon data URIs and metadata on resource lists; template/prompt cases retain URL icon coverage.

| Want | Command |
|---|---|
| all + lint + revapi + jmh (CI) | `make ci` — one reactor (`clean verify -Plint -Drevapi.skip=false`) plus the JMH gate; primary JDK only |
| compat JDKs | `make ci-lite` — same build with javadoc/sources/JaCoCo/SpotBugs/Spotless skipped (~3x faster) |
| unit + e2e | `make test` |
| one module | `mvn -q test -pl tachyon-core -am` |
| Kotlin | `mvn test -pl tachyon-kotlin -am` |
| conformance | `make conformance` |
| JMH perf gate | `make jmh` → `-Pjmh` profile runs `BenchmarkGate` (`tachyon-core/src/test/java/dev/tachyonmcp/core/BenchmarkGate.java`): runs every `*Benchmark` via JMH, but gates only those with a floor in `BenchmarkGate#MIN_OPS_PER_SEC` (fails below it). Floors today: `InMemorySessionEventStoreBenchmark.append`, `InMemorySessionStoreBenchmark.find` / `touch` / `compareAndSet`; the rest (e.g. `CodecBenchmark`, `ResponseMapperBenchmark`) only report; accepts JMH CLI (`-prof gc`, `-t`, regex) via `-Djmh.args` (not `exec.args`, which would also hit the ts2java `exec:exec` runs) |
| format/lint | `make format` / `make lint` (Spotless + Detekt; SpotBugs in build) |
| release build | `make deploy` — `clean deploy -P release,lint -Drevapi.skip=false`; uploads to Maven Central only with `PUBLISH=true`, otherwise `-DskipPublishing=true` (dry run) |

🔴 Every target routes through `MAVEN_TEST_ARGS` (`Makefile`): with `CI=true` it caps `-Dsurefire.forkCount=1` (pom default is `1C`) and `-Dio.netty.eventLoopThreads=2`. Without the caps a runner forks one JVM per CPU, each sizing its Netty groups at `availableProcessors * 2`, and the build dies with `failed to create a child event loop`. Bare `./mvnw` in a workflow bypasses this — call `make`.

`e2e`, `conformance`, `reports` are profile modules in root `pom.xml` — not in the default module list. The `tests` profile that adds them is activated by the absence of `skipTestModules`, so a `-P` flag no longer drops them.

These modules run in release verification but their POMs skip Maven install and deploy. Root release `excludeArtifacts` keeps them out of the Central bundle (`pom.xml`).

[ProgressKeepAliveTest#commentKeepAliveUpgradesWithoutProgressToken](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/ProgressKeepAliveTest.java) exercises both 2025-11-25 session and 2026-07-28 sessionless POSTs: 1s reader idle, 250ms heartbeats, 3s tool runtime. It asserts scheduled comments and the complete successful result. [ProgressKeepAliveTest#sessionlessCommentStreamClosesOnReaderIdleWhenHeartbeatsDisabled](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/ProgressKeepAliveTest.java) disables heartbeats on an isolated server, holds the tool pending, and asserts the initial comment followed by a broken stream with no result. See [[sse-streams]].

[McpInitializationBackpressureTest](../../tachyon-core/src/test/java/dev/tachyonmcp/core/transport/netty/McpInitializationBackpressureTest.java) dispatches a pending tool through the init handler behind an `IdleStateHandler`, then uses controlled writability and virtual channel time to verify writer-idle close on stall, zero writer idle disabling it, heartbeats holding a writable stream open, and recovery. [McpOperationHandlerTest#writerIdleOnSseStreamClosesChannel](../../tachyon-core/src/test/java/dev/tachyonmcp/core/transport/netty/McpOperationHandlerTest.java) covers the operation phase.

## 📊 Coverage gate

JaCoCo `prepare-agent` + `report` are bound once in root `pom.xml` `<plugins>`, so every module writes `target/jacoco.exec`. `reports` merges them (`**/target/jacoco.exec`) and gates the bundle.

`jacoco:check` has no `classDirs` parameter — it only analyses `${project.build.outputDirectory}` — so `reports` unpacks every published module's classes there with `dependency:unpack-dependencies` before the check. Floors live in `reports/pom.xml`; generated protocol classes are excluded in root `pom.xml`.

## 🗂️ e2e layout (`e2e/src/test/java/dev/tachyonmcp/e2e/mcp/`)

| Dir | Content |
|---|---|
| root | version-agnostic + abstract contracts: `AbstractMcpE2eTest`, `AbstractStatelessMcpE2eTest`, `Abstract*ContractTest` (resource, schema validation, string schema, tool capabilities, tool errors), `SharedE2eServer`, `SharedStatelessE2eServer`, `AcceptHeaderValidationTest`, `AbstractDnsRebindingTest`, `AbstractCorsOriginAllowlistTest`, `CorsPipeliningTest` (slow A + instant B from different origins: socket silent until A released, then A→B each with its own CORS grant), `HttpPipeliningOrderTest` (raw socket: sync 415 waits for async 200; POST-SSE not interleaved, closes, pipelined request never dispatched; `Connection: close` drops the pipelined request), `HttpPipeliningLimitTest` (`maxPipelinedRequests(0)` + a decoded-request latch behind the codec ⇒ second request gets in-order `429` + close, never runs; overflow past a non-zero limit depends on per-read decoding, so it lives in unit `HttpPipeliningGateTest`), `CorsExpectationTest` (413/417 headers and keep-alive reuse), `AbstractContentTypeValidationTest`, `AbstractBrowserClientTest` (each subclassed in both version packages), `MaxContentLengthTest`, `ListPaginationE2eTest`, `ProgressKeepAliveTest`, `SseHeartbeatTest`, `ShutdownDrainTest`, `PostStartRegistrationTest`, `TypedToolRegistrationTest`, `DeclarativeFeaturesTest` (`@McpTool/@McpResource/@McpPrompt` over wire; unit edge cases in core `server/annotations/TachyonAnnotationProviderTest`, `server/json/JavaTypeSchemaFactoryTest`), `PayloadSerdeTest`, `NativeTransportDetectionTest`, `McpSdkContract` |
| `v2025_11_25/` | stateful: sessions lifecycle, janitor, SSE polling/retry/replay-per-stream/POST reconnect redelivery, cancellation, logging, tasks (augmented, core, extension, optional ops), custom session id, extensions, input-required, SDK tests; concrete subclasses of abstract contracts. Stateless exception: `HeaderValidationTest` — SEP-2243 mirrors optional but checked, mcp-remote `initialize` preflight |
| `v2026_07_28/` | stateless: discover, meta validation, header validation — required + matching (+custom `Mcp-Param`), removed methods, unsupported version, extension negotiation, missing capability, log-level gating, subscriptions/listen, tasks extension, caching hints, structured output schema shape, contracts |
| `e2e/src/test/kotlin/dev/tachyonmcp/e2e/` | Kotlin DSL e2e |

Pattern: abstract contract in root, one subclass per protocol package → same behavior asserted on both wires.

## 📏 Conformance

`conformance/src/test/java/dev/tachyonmcp/conformance/` holds the shared parts: [AbstractServerConformanceTest](../../conformance/src/test/java/dev/tachyonmcp/conformance/AbstractServerConformanceTest.java), [AbstractConformanceServer](../../conformance/src/test/java/dev/tachyonmcp/conformance/AbstractConformanceServer.java), [ConformanceRunner](../../conformance/src/test/java/dev/tachyonmcp/conformance/ConformanceRunner.java), [ConformanceReportWriter](../../conformance/src/test/java/dev/tachyonmcp/conformance/ConformanceReportWriter.java). There is one package per spec version, the e2e scheme: `ServerConformanceTest` + [ConformanceServer](../../conformance/src/test/java/dev/tachyonmcp/conformance/v2026_07_28/ConformanceServer.java) (2025-11-25 has [its own](../../conformance/src/test/java/dev/tachyonmcp/conformance/v2025_11_25/ConformanceServer.java)).

| Class | Upstream ref | Run | Judges | Server |
|---|---|---|---|---|
| `v2025_11_25.ServerConformanceTest` | npm `0.1.16` | `--suite all --spec-version 2025-11-25` | all 32 | own, stateful |
| `v2026_07_28.ServerConformanceTest` | npm `0.2.0-alpha.12` | `--suite all --spec-version 2026-07-28` | all 40 | own, stateless |
| `v2026_07_28.ExtensionConformanceTest` | upstream `main` SHA | bare `--suite all` | the 13 `[extension]` scenarios | `ExtensionConformanceServer`, stateful |
| `v2027_03_30.ServerConformanceTest` | upstream `main` SHA | `--spec-version 2027-03-30` | — `@Disabled`: upstream rejects the version | reuses 2026 |

- One `npx … server --suite all` per class, run against one long-lived server, then one `list`. This is the TypeScript/Python SDK pattern (Python CI: legs `all@2025-11-25`, `all@2026-07-28`, bare `all`). `AbstractServerConformanceTest#serverConformance` parses the `=== SUMMARY ===` lines and emits DynamicTests:
  - `every listed scenario ran`: executed ⊇ `list --server [--spec-version v]`.
  - Then one test per judged scenario: failed and not in the baseline → fail, with check details from `server-<scenario>-<ts>/checks.json` (`ConformanceReportWriter#failedChecks`); failed and in the baseline → abort; passes but in the baseline → fail (stale entry).
- The bare run is the only one that reaches the `[extension]` scenarios: `--spec-version` drops them, and upstream runs them on the 2026-07-28 wire. It also replays the timeline scenarios at upstream's default wire (non-draft → 2025-11-25). The spec-version runs already judge those, and they share the baseline file, so the bare run judges only the extension ones.
- [ExtensionConformanceServer](../../conformance/src/test/java/dev/tachyonmcp/conformance/v2026_07_28/ExtensionConformanceServer.java) extends the 2026 [ConformanceServer#builder](../../conformance/src/test/java/dev/tachyonmcp/conformance/v2026_07_28/ConformanceServer.java) with `TasksExtension` (an in-memory connector), `SkillsExtension` (classpath `skills/greeting`), and the task fixtures upstream names (`greet`, `slow_compute`, `failing_job`, `protocol_error_job`, `confirm_delete`, `multi_input`, `test_tool_with_task`). It stays separate: REQUIRED-task tools in the 2026 server's `tools/list` break `http-header-validation`, which probes a listed tool. Stateful, so the bare run's 2025-wire replays get sessions; the 2026 wire has none either way (`McpProtocol#supportsSessions`).
- `conformance/conformance-baseline-<suite>.yml` holds known failures by scenario name. The 2026 file baselines 3 Tachyon gaps, one row each in [[findings]].
- The ref is a literal in each test class, so runs are deterministic from the IDE and in CI. `ConformanceRunner#packageSpec`: a version → npm `@modelcontextprotocol/conformance@<v>`; anything else (SHA, branch) → `github:modelcontextprotocol/conformance#<ref>`, which npx clones and builds via `prepare`.
- Each npx call has a 2 min deadline (`ConformanceRunner#exec` kills the process tree and throws), under the 5 min `@Timeout` on `serverConformance`. A cold git install plus the suite took about 1 min on CI.
- The suite name is derived from the package (`v2026_07_28` → `2026-07-28`).
- `list` failing or listing nothing fails the test, so the suite can't pass on zero tests (`ConformanceRunner#listScenarios`).
- No upstream branch knows `2027-03-30`. `main` still has `DRAFT_PROTOCOL_VERSION = '2026-07-28'`.
- Upstream has no resource-server auth scenarios: `authorization` tests an AS, and `auth/*` scenarios target clients. The TS SDK's `authTestServer.ts` is manual only.

## 🏷️ Declarative result coverage

[DeclarativeResultsTest#binaryResourceEncodesRawBytesOnce](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeResultsTest.java)
reads a PNG from `@TempDir` through an annotated method. Modern-wire assertions cover complete binary
resource contents, mixed prompt roles, explicit success/error tool results, invalid inputs, and an
input-required form with request state. Each test owns a stateless port-0 server.

[DeclarativeElicitationTest#annotatedToolCompletesClientElicitationRoundTrip](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeElicitationTest.java)
uses the MCP SDK and an isolated stateful server. All three client actions run through an annotated
method with injected context; assertions cover the form request, tool result, and exclusion of
context from the advertised input schema. Requests use `ElicitationRequest.builder()` and wire
decoding constructs `ElicitationResult` through its builder.

## 📡 Subscription regression coverage

[TaskSubscriptionRoutingTest#taskReachesItsRouteSessionAndEveryAuthorizedListenerNamingIt](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/TaskSubscriptionRoutingTest.java) covers modern listeners opened before/after a legacy task is created: the routed task reaches its session and every authorized listener naming it (also after the session is deleted); a listener never gets an id it did not name (fenced by a later event). Legacy [TaskRoutingTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/TaskRoutingTest.java) covers route-from-tool-call, set-once routes and reads never routing; [TaskConnectorAccessTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/TaskConnectorAccessTest.java) the session-scoping connector recipe for `tasks/*` and for listeners (`TaskConnectorAccessTest#refusedListenerNeverReceivesTheTaskEvenAfterEviction`: ack omits the id, nothing arrives after eviction; `TaskConnectorAccessTest#authorizedListenerReceivesARoutedTaskAlongsideItsSession`).

`PostSseStreamTest` exercises out-of-order write completion and preservation of the first transport failure. `SubscriptionsListenObservationTest` covers shutdown fallback, ack timestamp retention, and exception-detail gating. `McpOpenTelemetryListenerTest#subscriptionCompletionRunsOffNettyEventLoop` checks the completion thread over real HTTP; `subscriptionStreamFailureFailsSpan` checks optional exception export.

## 🧪 Unit tests

Resource registration rejects missing and extra URI-template parameters at build time ([TachyonAnnotationProviderTest#rejectsTemplateVariablesWithoutParametersAtBuildTime](../../tachyon-core/src/test/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProviderTest.java)).

Declarative completion wire coverage runs on both protocols (`DeclarativeCompletionsTest#explicitRequestPreservesContextAndCompleteResult`): explicit request + metadata, named partial text + sibling binding, nullability, resource refs, checked failures. Invalid declarations and missing parameter-name metadata are build-time edge tests (`UnknownTemplateVariable`); Spring completion-only proxy regression lives in [[spring-boot]].

See module pages: [[tachyon-core]], [[tachyon-api]], [[tachyon-kotlin]], [[tachyon-extensions]], [[tachyon-extensions-skills]]. Rules (AGENTS.md): JUnit 6, AssertJ (Java) / Kotest (Kotlin), Awaitility, `@TempDir`, `TachyonServer` as SUT, many asserts per test, no tautologies; drop unit test when e2e covers.

Related: [[tachyon-testkit]].

Parameter names, whole-object binding alongside metadata, nullable/missing metadata, and metadata across feature kinds have wire coverage ([DeclarativeParameterMetadataTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeParameterMetadataTest.java)).

The same declarative metadata suite proves 2026-07-28 resource cache isolation for both static and templated `@Meta` handlers, plus the public-cache default for a metadata-free annotated resource ([DeclarativeParameterMetadataTest#injectsMetadataAcrossPromptsResourcesAndCompletionShapes](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeParameterMetadataTest.java)).

Explicit-name fixtures use Java parameter `arg0` with a different `@McpParam` name in schema and wire tests. This verifies name precedence, not absent reflection parameter metadata ([TachyonAnnotationProviderTest#mcpParamRenamesAndDescribesToolArguments](../../tachyon-core/src/test/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProviderTest.java), [DeclarativeParameterMetadataTest#bindsWholeAndNamedObjectsWithSeparateMetadata](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeParameterMetadataTest.java)).

Stateless 2025 extension isolation has real-HTTP coverage for reused/fresh connections and a request pipelined behind a blocked `initialize` on one socket (held until `initialize` answers, then sees its own context, not the initializer's) ([StatelessExtensionNegotiationTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/StatelessExtensionNegotiationTest.java)). Stateful declarations survive reconnect ([ExtensionNegotiationPolicyTest#requiredDeclarationSurvivesConnectionChange](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/ExtensionNegotiationPolicyTest.java)).

Slow-reader regression: `PostSseSlowReaderTest` (real TCP: non-reading client parks producer until writer idle close, event-loop barrier, client isolation; reading client gets full burst; slow reader behind a full budget still gets the final result; interrupted producer ends the stream with a gap-free prefix) and `PostSseStreamTest` (pending-flush accounting, deferred overflow close, final response past a full budget, buffer release via tracking allocator) → [[sse-streams]].
