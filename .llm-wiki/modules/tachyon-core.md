---
title: tachyon-core
tags: [module, core]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-core/src/main/resources/, tachyon-core/pom.xml]
updated: 2026-09-13
commit: 5821ad56
---

# ⚙️ tachyon-core

Verdict: the runtime. ~190 main files. Deps: `slf4j-api`, `netty-codec-http`, `netty-handler`, `jackson-databind` 3, `json-schema-validator` (networknt), `tachyon-api` (`tachyon-core/pom.xml:29-46`). Generates protocol wire models at build (ts2java).

## 🗂️ Package map (`tachyon-core/src/main/java/dev/tachyonmcp/core/`)

| Package | Holds | Page |
|---|---|---|
| `server` | `TachyonServer`, `ServerBuilder`, `DefaultServerBuilder`, `DefaultTachyonServer` (engine impl, 1039 lines), `McpDispatcher`, `RpcMethodHandler`, `OutboundSseStream*`, `AnnotationContext`, `HandlerWatchdog` | [[overview]], [[request-lifecycle]] |
| `server.internal` | `ServerEngine` SPI, `OperationTracker`, `AbstractJanitor`, `NotificationLogSupport` | [[concurrency]] |
| `server.config` | config records + builders | [[configuration]] |
| `server.domain` | `ServerErrors`, `InitializeResponse`, `MissingRequiredClientCapabilityException` | [[errors]] |
| `server.handlers` | `InitializeHandler`, `DiscoverHandler`, `PingHandler`, `EmptyResultHandler`, `LoggingHandlers`, `SubscriptionsListenHandler`, `ExtensionNegotiator` | [[feature-registries]], [[extensions]] |
| `server.features.*` | registries + `*MethodHandlers` for tools/resources/prompts/completions/tasks/subscriptions; `AbstractRegistry`, `Pagination`, `ChangeSupport`, `ListRequests` | [[feature-registries]], [[tasks]] |
| `server.session` | `SessionManager`, stores, `SessionEvent`, `DispatchContext`, `DefaultDispatchContext`, `NoopInteractionContext`, `WireClientContext` | [[sessions]] |
| `server.json` | Jackson factories, `JsonUtils`, `JsonSchemaUtils`, networknt validator, `KtSchemaResourceFactory`, `MapJsonFactory` | [[json-layer]] |
| `server.observability` | `Observation`, listener/scope/info/outcome, `CapturedPayload` | [[observability]] |
| `runtime` | `Session`, `SessionState`, `ChannelContext`, `DefaultChannelContext`, `InteractionEvent`, `SseConnection`, `SseEvent`, `Backpressure` | [[sessions]] |
| `protocol` | `Protocol`, `Protocols`, `ProtocolRequestMapper`, `ProtocolResponseMapper`, `ProtocolMappers`, `RequestMappingException` | [[protocol-versions]] |
| `protocol.mcp` | `McpHeaderNames`, `AbstractMcpRequestMapper` (shared request mapping); `v2025_11_25`, `v2026_07_28` (`McpProtocol`, `codecs/`, `transport/`, generated `models/`) | [[protocol-versions]] |
| `transport.jsonrpc` | `JsonRpcCodec`, `JsonRpcMessage`, `JsonRpcError`, `ValueSerializer` | [[json-layer]] |
| `transport.netty` | `NettyServer`, `NettyServerConfig`, `NettyIoEngine`, `McpChannelInitializer`, init/operation handlers, `InteractionHandler`, lifecycle coordinator, `ChannelHandlerUtils` | [[netty-pipeline]] |
| `transport.netty.http` | guards | [[security-guards]] |
| `transport.netty.sse` | `PostSseStream`, `SseManager`, `SseHeartbeat`, `SseSerializer`, `NettySseConnection` | [[sse-streams]] |

## 🧠 Who holds what

- `DefaultTachyonServer` = state + registries + `methodHandlers` map + pending server→client requests + session manager + event store + extensions; implements `ServerEngine` **and** `ExtensionContext` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java:94-124`.
- `McpDispatcher` = per-request flow, one per `McpChannelInitializer` (i.e. per server start) `McpChannelInitializer.java` ctor.
- `DefaultDispatchContext` = per request: delegates channel state, adds engine, request id, outbound stream, observation, notifications impl `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/DefaultDispatchContext.java:30-273`.

## 📦 Resources

- `META-INF/services/dev.tachyonmcp.core.protocol.Protocol`, `...JsonDocumentFactory`, `...JsonSchemaFactory`.
- `dev/tachyonmcp/core/server/features/resources/mime-types.csv`.

## ➕ How to add a JSON-RPC method

1. Mapper method on `ProtocolRequestMapper` (impl once in `AbstractMcpRequestMapper`, override per version only if shape differs) and `ProtocolResponseMapper` (+ both version impls).
2. `RpcMethodHandler` record in `server.features.<x>` `*MethodHandlers.register(map, …)` or `server.handlers`.
3. Wire in `DefaultTachyonServer.registerDefaults` `:537-551`.
4. 2026-07-28 name/uri header rule? update `RequestValidationHandler.NAME_REQUIRED_METHODS`.
5. Capability flag? `resolveCapabilities` + `ServerInfoMapper` both versions.
6. e2e test per protocol package → [[testing]].

## 🧪 Unit tests

`tachyon-core/src/test/java/dev/tachyonmcp/core/` mirrors packages: `server/McpDispatcherTest`, `ServerTest`, `ServerBuilderTest`, `ServerShutdownGraceTest`, `ObservationDispatchTest`; `transport/netty/*HandlerTest`, `ForeignThreadContinuationTest`, `NettyServerThreadingTest`; `protocol/mcp/v*/codecs/*MapperTest`; `session/SessionManagerTest`, `InMemorySessionEventStoreTest`; test utils `core/test/TestUtils`, `VirtualThreads`.
