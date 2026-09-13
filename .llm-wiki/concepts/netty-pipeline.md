---
title: Netty pipeline
tags: [concept, transport, netty]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/]
updated: 2026-09-13
commit: 582f9c52
---

# 🧪 Netty pipeline

Verdict: one static-order pipeline per channel. Every registered `Protocol`'s handlers sit in it unconditionally and no-op for other versions — no dynamic surgery except the **init→operation** handler swap.

## 🔌 Server bootstrap

`NettyServer` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/NettyServer.java:51-99`
- I/O engine `AUTO` ⇒ `NettyIoEngine.detect()` order **io_uring > epoll > kqueue > NIO**, reflective, cached per JVM `NettyIoEngine.java:22-35`, `:95`.
- Event loops = **platform** threads `netty-io` (native transports pin anyway) `NettyServer.java:57-62`.
- `SO_BACKLOG 1024`, `TCP_NODELAY`, `SO_KEEPALIVE`, write watermark 32K/128K `:65-73`.
- `stopAccepting()` closes server channel only; `close()` closes children + event loops (graceful 0..3s) `:118-138`.

## 🧱 Handler order

`McpChannelInitializer.initChannel` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpChannelInitializer.java:131-209`

| # | Name | Handler | Rejects / does |
|---|---|---|---|
| 1 | `flush` | `FlushConsolidationHandler` | batch flushes |
| – | `logger` | `LoggingHandler` | only if logger `me.kpavlov.tachyon.transport.netty.channel` DEBUG (`:52-55`) |
| 2 | `http` | `HttpServerCodec` | |
| – | `session-touch` | `SessionTouchHandler` | added lazily after `http` when session bound; every outbound write `touch()`es session `SessionTouchHandler.java:31-49` |
| 3 | `http-keep-alive` | `HttpServerKeepAliveHandler` | honors `Connection`; responses set keep-alive intent |
| 4 | `dns-rebinding` | `DnsRebindingProtectionHandler` | 403 → [[security-guards]] |
| 5 | `cors` | `CorsHandler` | only if CORS config |
| 6 | `mcp-endpoint` | `EndpointValidatorHandler` | 404 path ≠ endpoint (trailing `/`, query ignored) `http/EndpointValidatorHandler.java:30-47` |
| 7 | `mcp-header-guard` | `McpHeaderGuardHandler` | 400 duplicate singleton/mirror headers; SEP-2243 mirrors w/o 2026-07-28 |
| 8 | `protocol-version` | `ProtocolVersionHandler` | POST: resolve protocol, bind ctx, or flag unsupported |
| 9 | `accept-header` | `AcceptValidationHandler` | 406 |
| 10 | `stateless-mcp` | `StatelessValidatorHandler` | only stateless server: 404 on session/Last-Event-ID headers, 405 DELETE |
| 11 | `http-aggregator` | `HttpObjectAggregator(maxContentLength)` | 413/417; owns `Expect: 100-continue` (`:163-167`) |
| 12 | `unsupported-protocol-version` | `UnsupportedProtocolVersionHandler` | 400 JSON-RPC error with body `id` + supported list `UnsupportedProtocolVersionHandler.java:33-48` |
| 13 | `interaction` | `InteractionHandler` | fallback protocol resolve for GET/DELETE; lifecycle events → ctx `InteractionHandler.java:37-76` |
| 14 | `idle` | `IdleStateHandler` | if reader/writer idle > 0 |
| 15 | `mcp-<ver>-*` | `Protocol.requestHandlers(server)` for each protocol | 2025: no-op; 2026: validation + extension negotiation |
| 16 | `mcp-phase-init` | `McpInitializationHandler` (per channel) | first request |
| 17 | `lifecycle` | `LifecyclePipelineCoordinator` | swaps 16 → `mcp-phase-operations` |
| – | customizer | `ServerBuilder.pipelineCustomizer` | user hook, runs last `:206-208` |

## 🔁 Phase swap

- `InteractionEvent` sealed: `OperationStarted(session?)`, `ShutdownStarted(sessionId?)`, `ShutdownComplete` `tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/InteractionEvent.java:8-31`.
- `LifecyclePipelineCoordinator` on `OperationStarted` → `pipeline.replace(init, ops, new McpOperationHandler)` `LifecyclePipelineCoordinator.java:21-38`; on `ShutdownStarted` → `McpHandlerManager.onShutdownStarted` removes session on executor `McpHandlerManager.java:49-57`.
- Init handler: session-less POST `initialize` ⇒ dispatch, then fire `OperationStarted(localSession)` **before** writing response `McpInitializationHandler.java:252-312`. Everything else (has session id, GET, DELETE, pre-session non-init) ⇒ `forwardToOperationHandler` `:321-338`.
- Init channel closed abruptly with a session ⇒ `ShutdownStarted` ⇒ session removed `:353-363`.

## 🧷 Per-channel state

| Attr | Key | Proof |
|---|---|---|
| `ChannelContext` (protocol, session, lifecycle, enabled extensions, attrs) | `InteractionHandler.INTERACTION_CONTEXT_KEY` | `InteractionHandler.java:27` |
| `Session` | `tachyonSession` | `ChannelHandlerUtils.java:35` |
| rejected flag (drop rest of request) | `tachyonRequestRejected` | `ChannelHandlerUtils.java:39-91` |
| unsupported version | `unsupportedProtocolVersion` | `ProtocolVersionHandler.java` (`UNSUPPORTED_VERSION_KEY`) |
| SSE heartbeat active/future | `sseHeartbeatActive` | `sse/SseHeartbeat.java:31-33` |

⚠️ Keep-alive socket may carry different protocol versions (proxy pooling). 2026-07-28 always gets fresh ctx; older version keeps ctx only while version matches `ProtocolVersionHandler.java:65-75`.

## 🌊 Backpressure

`McpOperationHandler.channelWritabilityChanged` toggles `autoRead` `McpOperationHandler.java:550-554`. `Session.send` drops (returns false) when connection not writable — event stays in log for replay `tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java:273-283`.

## 📤 Response helpers

`ChannelHandlerUtils` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ChannelHandlerUtils.java`: `sendPlainTextAndClose`, `sendResponseAndClose` (keep-alive false ⇒ `Connection: close`), `sendAcceptedAsync` (202), `completeOn(future, transportCompletion)`, `isRefused` (RejectedExecution ⇒ 503 "Server shutting down"), `captureInitRequest` (detached headers copy for custom `SessionIdGenerator`, only if `readsRequest()`) `:146-158`.

Related: [[request-lifecycle]], [[security-guards]], [[sse-streams]].
