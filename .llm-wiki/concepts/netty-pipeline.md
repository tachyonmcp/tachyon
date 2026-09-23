---
title: Netty pipeline
tags: [concept, transport, netty]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/]
updated: 2026-09-23
commit: b2ac69b9
---

# 🧪 Netty pipeline

Verdict: one static-order pipeline per channel. Every registered `Protocol`'s handlers sit in it unconditionally and no-op for other versions — no dynamic surgery except the **init→operation** handler swap.

## 🔌 Server bootstrap

`NettyServer` `NettyServer`
- I/O engine `AUTO` ⇒ `NettyIoEngine.detect()` order **io_uring > epoll > kqueue > NIO**, reflective, cached per JVM `NettyIoEngine#IO_URING`, `NettyIoEngine#detect`.
- Event loops = **platform** threads `netty-io` (native transports pin anyway) `NettyServer#NettyServer`.
- `SO_BACKLOG 1024`, `TCP_NODELAY`, `SO_KEEPALIVE`, write watermark 32K/128K `NettyServer#NettyServer`.
- `stopAccepting()` closes server channel only; `close()` closes children + event loops (graceful 0..3s) `NettyServer#stopAccepting`.

## 🧱 Handler order

`McpChannelInitializer.initChannel` `McpChannelInitializer`

| # | Name | Handler | Rejects / does |
|---|---|---|---|
| 1 | `flush` | `FlushConsolidationHandler` | batch flushes |
| – | `logger` | `LoggingHandler` | only if logger `dev.tachyonmcp.transport.netty.channel` DEBUG (`McpChannelInitializer#CHANNEL_LOGGER_NAME`) |
| 2 | `http` | `HttpServerCodec` | |
| – | `session-touch` | `SessionTouchHandler` | added lazily after `http` when session bound; every outbound write `touch()`es session `SessionTouchHandler#install` |
| 3 | `http-keep-alive` | `HttpServerKeepAliveHandler` | honors `Connection`; responses set keep-alive intent |
| 4 | `dns-rebinding` | `DnsRebindingProtectionHandler` | 403 → [[security-guards]] |
| – | `cors-mcp-param` | `McpParamPreflightHandler` | only if CORS config, just before `cors`: appends requested `Mcp-Param-<token>` names to a granted preflight's `Access-Control-Allow-Headers` (no `*`) `McpParamPreflightHandler#write` |
| 5 | `cors` | `CorsHandler` | only if CORS config; answers preflights itself `NettyServerConfig#buildCorsConfig` |
| 6 | `mcp-endpoint` | `EndpointValidatorHandler` | 404 path ≠ endpoint (trailing `/`, query ignored) `EndpointValidatorHandler#channelRead` |
| 7 | `mcp-header-guard` | `McpHeaderGuardHandler` | 400 duplicate singleton MCP header (incl. SEP-2243 mirrors); body-independent, so it runs pre-aggregation `McpHeaderGuardHandler#hasDuplicateSingleton` |
| 8 | `protocol-version` | `ProtocolVersionHandler` | POST: resolve protocol, bind ctx, or flag unsupported |
| 9 | `accept-header` | `AcceptValidationHandler` | 406 |
| 10 | `content-type` | `ContentTypeValidationHandler#INSTANCE` | 415 JSON-RPC `-32600` on POST without `application/json`: a CORS "simple" request no preflight gated. Every POST past `mcp-endpoint`, no path match `ContentTypeValidationHandler#channelRead` |
| 11 | `stateless-mcp` | `StatelessValidatorHandler` | only stateless server: 404 on session/Last-Event-ID headers, 405 DELETE |
| 12 | `http-aggregator` | `HttpObjectAggregator(maxContentLength)` | 413/417; owns `Expect: 100-continue` (`EndpointValidatorHandler`) |
| 13 | `unsupported-protocol-version` | `UnsupportedProtocolVersionHandler` | 400 JSON-RPC error with body `id` + supported list `UnsupportedProtocolVersionHandler#channelRead` |
| 14 | `interaction` | `InteractionHandler` | fallback protocol resolve for GET/DELETE; lifecycle events → ctx `InteractionHandler#userEventTriggered` |
| 15 | `idle` | `IdleStateHandler` | if reader/writer idle > 0 |
| 16 | `mcp-header-match` | `McpHeaderMatchHandler` | SEP-2243 mirror **agreement** vs body, every version, ungated `McpHeaderMatchHandler#channelRead` |
| 17 | `mcp-<ver>-*` | `Protocol.requestHandlers(server)` for each protocol | 2025: none; 2026: `RequestValidationHandler` (`_meta`/removed methods) → `RequiredHeadersHandler` (mirror **presence**) → `ExtensionNegotiationHandler` |
| 18 | `mcp-phase-init` | `McpInitializationHandler` (per channel) | first request |
| 19 | `lifecycle` | `LifecyclePipelineCoordinator` | swaps 17 → `mcp-phase-operations` |
| – | customizer | `ServerBuilder.pipelineCustomizer` | user hook, runs last `InteractionHandler` |

## 🔁 Phase swap

- `InteractionEvent` sealed: `OperationStarted(session?)`, `ShutdownStarted(sessionId?)`, `ShutdownComplete` `InteractionEvent`.
- `LifecyclePipelineCoordinator` on `OperationStarted` → `pipeline.replace(init, ops, new McpOperationHandler)` `LifecyclePipelineCoordinator#userEventTriggered`; on `ShutdownStarted` → `McpHandlerManager.onShutdownStarted` removes session on executor `McpHandlerManager#onShutdownStarted`.
- POST without session id stays under the init handler during dispatch: `initialize` fires `OperationStarted(localSession)` **before** writing its response; other requests dispatch without a phase swap [McpInitializationHandler#handleRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java), [McpInitializationHandler#dispatchPreSessionRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java). Session-header requests, GET and DELETE forward to operations; OPTIONS answers directly [McpInitializationHandler#forwardToOperationHandler](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java). Both phases exempt heartbeat-enabled SSE from reader-idle closure only → [[sse-streams]].
- Init channel closed abruptly with a session ⇒ `ShutdownStarted` ⇒ session removed `InteractionEvent`.

## 🧷 Per-channel state

| Attr | Key | Proof |
|---|---|---|
| `ChannelContext` (protocol, session, lifecycle, enabled extensions, attrs) | `InteractionHandler.INTERACTION_CONTEXT_KEY` | `InteractionHandler#logger` |
| `Session` | `tachyonSession` | `ChannelHandlerUtils#SESSION_KEY` |
| rejected flag (drop rest of request) | `tachyonRequestRejected` | `ChannelHandlerUtils` |
| unsupported version | `unsupportedProtocolVersion` | `ProtocolVersionHandler.java` (`UNSUPPORTED_VERSION_KEY`) |
| first abnormal close cause | `closeFailure` (`setIfAbsent`) | [ChannelHandlerUtils#markCloseFailure](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ChannelHandlerUtils.java) |
| SSE heartbeat active/future | `sseHeartbeatActive` | `SseHeartbeat` |

Written by `PostSseStream#closeOnWriteFailure`, `SseHeartbeat#send` and `McpOperationHandler#exceptionCaught` — [[sse-streams]].

⚠️ Keep-alive socket may carry different protocol versions (proxy pooling). Stateless servers and 2026-07-28 get a fresh context per POST; stateful older protocols keep it only while the version matches. [ProtocolVersionHandler#channelRead](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ProtocolVersionHandler.java).

## 🌊 Backpressure

`McpOperationHandler.channelWritabilityChanged` toggles `autoRead` `McpOperationHandler#channelWritabilityChanged`. `Session.send` drops (returns false) when connection not writable — event stays in log for replay `Session#send`.

Sustained non-writability on a heartbeat stream ends in writer idle, which both phases honor [SseHeartbeat#ignoresIdle](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/sse/SseHeartbeat.java) → [[sse-streams]].

## 📤 Response helpers

`ChannelHandlerUtils` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ChannelHandlerUtils.java`: `sendPlainTextAndClose`, `sendResponseAndClose` (keep-alive false ⇒ `Connection: close`), `sendAcceptedAsync` (202), `completeOn(future, transportCompletion)`, `isRefused` (RejectedExecution ⇒ 503 "Server shutting down"), `captureInitRequest` (detached headers copy for custom `SessionIdGenerator`, only if `readsRequest()`) `SessionIdGenerator`.

`rejectWithServerError(ctx, req, id, error)` `ChannelHandlerUtils#rejectWithServerError` is the shared JSON-RPC rejection for post-aggregation validators: maps a `ServerError` through the **negotiated** protocol's `responseMapper`, so the same call answers 400/-32020 on 2026-07-28 and 200/-32001 on 2025-11-25. Releases `req`.

Related: [[request-lifecycle]], [[security-guards]], [[sse-streams]].
