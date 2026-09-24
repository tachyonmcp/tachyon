---
title: tachyon-core
tags: [module, core]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-core/src/main/resources/, tachyon-core/pom.xml]
updated: 2026-09-24
commit: 0b232fb4
---

# ⚙️ tachyon-core

Verdict: the runtime. ~190 main files. Deps: `slf4j-api`, `netty-codec-http`, `netty-handler`, `jackson-databind` 3, `json-schema-validator` (networknt), `tachyon-api` (`pom.xml`). Generates protocol wire models at build (ts2java).

## 🗂️ Package map (`tachyon-core/src/main/java/dev/tachyonmcp/core/`)

| Package | Holds | Page |
|---|---|---|
| `server` | `TachyonServer`, `ServerBuilder`, `DefaultServerBuilder`, `DefaultTachyonServer`, `McpDispatcher`, `RpcMethodHandler`, `OutboundSseStream*`, `AnnotationContext`, `HandlerWatchdog` | [[overview]], [[request-lifecycle]] |
| `server.annotations` | `TachyonAnnotationProvider`, `MethodInvoker`, `ResultMappers` | [[declarative-configuration]] |
| `server.internal` | `ServerEngine` SPI, `OperationTracker`, `AbstractJanitor`, `NotificationLogSupport` | [[concurrency]] |
| `server.config` | config records + builders | [[configuration]] |
| `server.domain` | `ServerErrors`, `InitializeResponse`, `MissingRequiredClientCapabilityException` | [[errors]] |
| `server.handlers` | `InitializeHandler`, `DiscoverHandler`, `PingHandler`, `EmptyResultHandler`, `LoggingHandlers`, `SubscriptionsListenHandler`, `ExtensionNegotiator` | [[feature-registries]], [[extensions]] |
| `server.features.*` | registries + `*MethodHandlers` for tools/resources/prompts/completions/tasks/subscriptions; `AbstractRegistry`, `Pagination`, `ChangeSupport`, `ListRequests` | [[feature-registries]], [[tasks]] |
| `server.session` | `SessionManager`, stores, `SessionEvent`, `DispatchContext`, `DefaultDispatchContext`, `NoopInteractionContext`, `WireClientContext` | [[sessions]] |
| `server.json` | Jackson factories, `JsonUtils`, `JsonSchemaUtils`, networknt validator, `KtSchemaResourceFactory`, `JavaTypeSchemaFactory`/`JavaTypeSchemas`, `MapJsonFactory` | [[json-layer]] |
| `server.observability` | `Observation`, listener/scope/info/outcome, `CapturedPayload` | [[observability]] |
| `runtime` | `Session`, `SessionState`, `ChannelContext`, `DefaultChannelContext`, `InteractionEvent`, `SseConnection`, `SseEvent`, `Backpressure` | [[sessions]] |
| `protocol` | `Protocol`, `Protocols`, `ProtocolRequestMapper`, `ProtocolResponseMapper`, `ProtocolMappers`, `RequestMappingException` | [[protocol-versions]] |
| `protocol.mcp` | `McpHeaderNames`, `McpHeaderValue` (SEP-2243 Base64 sentinel), `MirroredArgument` (`x-mcp-header` ↔ argument), `AbstractMcpRequestMapper` (shared request mapping); `v2025_11_25`, `v2026_07_28` (`McpProtocol`, `codecs/`, `transport/`, generated `models/`) | [[protocol-versions]] |
| `transport.jsonrpc` | `JsonRpcCodec`, `JsonRpcMessage`, `JsonRpcError`, `ValueSerializer` | [[json-layer]] |
| `transport.netty` | `NettyServer`, `NettyServerConfig`, `NettyIoEngine`, `McpChannelInitializer`, init/operation handlers, `InteractionHandler`, lifecycle coordinator, `ChannelHandlerUtils` | [[netty-pipeline]] |
| `transport.netty.http` | guards, request-scoped CORS decisions, synchronous preflight handling, body/expectation rejection headers | [[security-guards]] |
| `transport.netty.sse` | `PostSseStream`, `SseManager`, `SseHeartbeat`, `SseSerializer`, `NettySseConnection` | [[sse-streams]] |

## 🧠 Who holds what

- Native parameter binding, including explicit names and metadata injection: [MethodInvoker](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/annotations/MethodInvoker.java). Registration and proxy invocation: [[declarative-configuration]].
- Metadata-sensitive annotated resources propagate private cache isolation through resource registry entries into modern response mapping; see [[declarative-configuration]] and [[feature-registries]].

- [DefaultTachyonServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java) = state + registries + `methodHandlers` map + pending server→client requests + session manager + event store + extensions; implements [ServerEngine](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/ServerEngine.java) **and** [ExtensionContext](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java) [DefaultTachyonServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).
- [McpDispatcher](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java) = per-request flow, one per [McpChannelInitializer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpChannelInitializer.java) (i.e. per server start) `McpChannelInitializer.java` ctor.
- [DefaultDispatchContext](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/DefaultDispatchContext.java) = per request: delegates channel state, adds engine, request id, outbound stream, observation, notifications impl [DefaultDispatchContext](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/DefaultDispatchContext.java).

## 📦 Resources

- `META-INF/services/dev.tachyonmcp.core.protocol.Protocol`, `...JsonDocumentFactory`, `...JsonSchemaFactory`.
- `dev/tachyonmcp/core/server/features/resources/mime-types.csv`.

## ➕ How to add a JSON-RPC method

1. Mapper method on `ProtocolRequestMapper` (impl once in `AbstractMcpRequestMapper`, override per version only if shape differs) and `ProtocolResponseMapper` (+ both version impls).
2. `RpcMethodHandler` record in `server.features.<x>` `*MethodHandlers.register(map, …)` or `server.handlers`.
3. Wire in [DefaultTachyonServer#registerDefaults](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).
4. Method addresses a named target (`Mcp-Name` mirror)? add it to `McpHeaderNames#mirroredNameField` — one switch drives both the all-version agreement check `McpHeaderMatchHandler#matchName` and the 2026 presence rule `RequiredHeadersHandler#requireMirrors`.
5. Capability flag? `resolveCapabilities` + `ServerInfoMapper` both versions.
6. e2e test per protocol package → [[testing]].

## 🧪 Unit tests

`tachyon-core/src/test/java/dev/tachyonmcp/core/` mirrors packages: `server/McpDispatcherTest`, `ServerTest`, `ServerBuilderTest`, `ServerShutdownGraceTest`, `ObservationDispatchTest`; `transport/netty/*HandlerTest`, `ForeignThreadContinuationTest`, `NettyServerThreadingTest`; `protocol/mcp/v*/codecs/*MapperTest`; `session/SessionManagerTest`, `InMemorySessionStoreTest`, `InMemorySessionEventStoreTest`; test utils `core/test/TestUtils`, `VirtualThreads`. JMH benchmarks (not Surefire, JaCoCo-excluded `*Benchmark*`, `tachyon-core/pom.xml`): `session/InMemorySessionEventStoreBenchmark`, `session/InMemorySessionStoreBenchmark`, gated by `BenchmarkGate` (`make jmh`) — see [[testing]].
