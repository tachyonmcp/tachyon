---
title: Testing
tags: [module, testing, e2e, conformance]
sources: [e2e/src/test/, conformance/, Makefile, tachyon-core/src/test/, .github/workflows/build.yml]
updated: 2026-09-18
commit: 1c15ddda
---

# ✅ Testing

Verdict: E2E-first (AGENTS.md). Real server on port 0, clients = official MCP Java SDK (`mcp-core` 2.0.1, `pom.xml`) **and** raw testkit clients. E2E packages split by protocol version. Conformance via `@modelcontextprotocol/conformance` with baselines.

## 🏃 Run

| Want | Command |
|---|---|
| all + lint + revapi + jmh (CI) | `make ci` (CI matrix runs this `.github/workflows/build.yml`) |
| unit + e2e | `make test` |
| one module | `mvn -q test -pl tachyon-core -am` |
| Kotlin | `mvn test -pl tachyon-kotlin -am` |
| conformance | `make conformance` |
| JMH perf gate | `make jmh` → `-Pjmh` profile runs `BenchmarkGate` (`tachyon-core/src/test/java/dev/tachyonmcp/core/BenchmarkGate.java`): every `*Benchmark` via JMH, fails below per-benchmark ops/sec floors; accepts JMH CLI (`-prof gc`, `-t`, regex) via `-Dexec.args` |
| format/lint | `make format` / `make lint` (Spotless + Detekt; SpotBugs in build) |

`e2e`, `conformance`, `reports` are profile modules in root `pom.xml` — not in default module list.

## 🗂️ e2e layout (`e2e/src/test/java/dev/tachyonmcp/e2e/mcp/`)

| Dir | Content |
|---|---|
| root | version-agnostic + abstract contracts: `AbstractMcpE2eTest`, `AbstractStatelessMcpE2eTest`, `Abstract*ContractTest` (resource, schema validation, string schema, tool capabilities, tool errors), `SharedE2eServer`, `SharedStatelessE2eServer`, `AcceptHeaderValidationTest`, `DnsRebindingTest`, `MaxContentLengthTest`, `ListPaginationE2eTest`, `ProgressKeepAliveTest`, `SseHeartbeatTest`, `ShutdownDrainTest`, `PostStartRegistrationTest`, `TypedToolRegistrationTest`, `DeclarativeFeaturesTest` (`@McpTool/@McpResource/@McpPrompt` over wire; unit edge cases in core `server/annotations/TachyonAnnotationProviderTest`, `server/json/JavaTypeSchemaFactoryTest`), `PayloadSerdeTest`, `NativeTransportDetectionTest`, `McpSdkContract` |
| `v2025_11_25/` | stateful: sessions lifecycle, janitor, SSE polling/retry/replay-per-stream/POST reconnect redelivery, cancellation, logging, tasks (augmented, core, extension, optional ops), custom session id, extensions, input-required, SDK tests; concrete subclasses of abstract contracts. Stateless exception: `HeaderValidationTest` — SEP-2243 mirrors optional but checked, mcp-remote `initialize` preflight |
| `v2026_07_28/` | stateless: discover, meta validation, header validation — required + matching (+custom `Mcp-Param`), removed methods, unsupported version, extension negotiation, missing capability, log-level gating, subscriptions/listen, tasks extension, caching hints, structured output schema shape, contracts |
| `e2e/src/test/kotlin/dev/tachyonmcp/e2e/` | Kotlin DSL e2e |

Pattern: abstract contract in root, one subclass per protocol package → same behavior asserted on both wires.

## 📏 Conformance

`conformance/src/test/java/dev/tachyonmcp/conformance/`: `DefaultConformanceServer` + `EdgeConformanceServer`, `*ServerConformanceTest`, `ConformanceRunner`, `ConformanceReportWriter`. Baselines `conformance/conformance-baseline-0.1.yml`, `-0.2.yml` (known failures).

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

`PostSseStreamTest` exercises out-of-order write completion and preservation of the first transport failure. `SubscriptionsListenObservationTest` covers shutdown fallback, ack timestamp retention, and exception-detail gating. `McpOpenTelemetryListenerTest#subscriptionCompletionRunsOffNettyEventLoop` checks the completion thread over real HTTP; `subscriptionStreamFailureFailsSpan` checks optional exception export.

## 🧪 Unit tests

Resource registration rejects missing and extra URI-template parameters at build time ([TachyonAnnotationProviderTest#rejectsTemplateVariablesWithoutParametersAtBuildTime](../../tachyon-core/src/test/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProviderTest.java)).

Declarative completion wire coverage runs on both protocols (`DeclarativeCompletionsTest#explicitRequestPreservesContextAndCompleteResult`): explicit request + metadata, named partial text + sibling binding, nullability, resource refs, checked failures. Invalid declarations and missing parameter-name metadata are build-time edge tests (`UnknownTemplateVariable`); Spring completion-only proxy regression lives in [[spring-boot]].

See module pages: [[tachyon-core]], [[tachyon-api]], [[tachyon-kotlin]], [[tachyon-extensions]]. Rules (AGENTS.md): JUnit 6, AssertJ (Java) / Kotest (Kotlin), Awaitility, `@TempDir`, `TachyonServer` as SUT, many asserts per test, no tautologies; drop unit test when e2e covers.

Related: [[tachyon-testkit]].

Parameter names, whole-object binding alongside metadata, nullable/missing metadata, and metadata across feature kinds have wire coverage ([DeclarativeParameterMetadataTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeParameterMetadataTest.java)).

The same declarative metadata suite proves 2026-07-28 resource cache isolation for both static and templated `@Meta` handlers, plus the public-cache default for a metadata-free annotated resource ([DeclarativeParameterMetadataTest#injectsMetadataAcrossPromptsResourcesAndCompletionShapes](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeParameterMetadataTest.java)).

Explicit-name fixtures use Java parameter `arg0` with a different `@McpParam` name in schema and wire tests. This verifies name precedence, not absent reflection parameter metadata ([TachyonAnnotationProviderTest#mcpParamRenamesAndDescribesToolArguments](../../tachyon-core/src/test/java/dev/tachyonmcp/core/server/annotations/TachyonAnnotationProviderTest.java), [DeclarativeParameterMetadataTest#bindsWholeAndNamedObjectsWithSeparateMetadata](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/DeclarativeParameterMetadataTest.java)).
