---
title: Netty pipeline
tags: [concept, transport, netty]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/http/]
updated: 2026-09-25
commit: cbfbcd7f
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
| – | `session-touch` | `SessionTouchHandler` | added lazily after `http` (ahead of `http-pipelining`) when session bound; every outbound write `touch()`es session `SessionTouchHandler#install` |
| 3 | `http-pipelining` | `HttpPipeliningGate` (per channel) | one request in flight per connection: a pipelined request (and its content) is queued until the previous response's `LastHttpContent` is written, so responses leave in request order (RFC 9112 §9.3.2) — errors, JSON, 202 and POST-SSE alike. `1xx` doesn't complete a request. A final non-keep-alive response drops the queue **and** every later request: nothing runs unanswered `HttpPipeliningGate#write`. Cap `NetworkConfig#maxPipelinedRequests` (default 16, `0` = no pipelining) queued requests: one more ⇒ it and every later read are released unrun, and once the ones ahead are answered it gets `429 Too Many Requests` + `Connection: close` (no CORS: it never passed the guards), then the channel closes `HttpPipeliningGate#channelRead`, `HttpPipeliningGate#drain`. Owns `autoRead` → 🌊 Backpressure |
| 4 | `http-keep-alive` | `HttpServerKeepAliveHandler` | honors `Connection`; responses set keep-alive intent |
| 5 | `dns-rebinding` | `DnsRebindingProtectionHandler` | 403 → [[security-guards]] |
| 6 | `mcp-endpoint` | `EndpointValidatorHandler` | 404 path ≠ endpoint (trailing `/`, query ignored) `EndpointValidatorHandler#channelRead`. **Only** path check: every later handler and `Protocol#matches` trust it, so a custom `endpointPath` works end to end. Ahead of CORS ⇒ other paths get no CORS grant |
| – | `cors-mcp-param` | `CorsPreflightHandler` | just before `cors`: canonicalizes the preflight Origin for Netty during the synchronous call, applies its original `CorsDecision` (including Vary on misses), then clears call-scoped state; appends requested `Mcp-Param-<token>` names to a granted preflight's `Access-Control-Allow-Headers` (no `*`) `CorsPreflightHandler#write` |
| 7 | `cors` | `TachyonCorsHandler` | always (non-null `NettyServerConfig#corsConfig`, default `NettyServerConfig#defaultCorsConfig`); answers preflights synchronously with Netty's method/header logic and `CorsPreflightHandler`'s request decision; `write()` is a pass-through — writers apply the request's own `CorsDecision` (`TachyonCorsHandler#decide`) → [[security-guards]] |
| 8 | `mcp-header-guard` | `McpHeaderGuardHandler` | 400 duplicate singleton MCP header (incl. SEP-2243 mirrors); body-independent, so it runs pre-aggregation `McpHeaderGuardHandler#hasDuplicateSingleton` |
| 9 | `protocol-version` | `ProtocolVersionHandler` | every POST: resolve protocol, bind ctx, or flag unsupported. Flag is a channel attr, cleared on **every** request: a flagged request refused before #14 (aggregator 413 keeps keep-alive open) must not reject the next one on the connection `ProtocolVersionHandler#channelRead` |
| 10 | `accept-header` | `AcceptValidationHandler#INSTANCE` | 406 |
| 11 | `content-type` | `ContentTypeValidationHandler#INSTANCE` | 415 JSON-RPC `-32600` on POST without `application/json`: a CORS "simple" request no preflight gated. Every POST past `mcp-endpoint`, no path match `ContentTypeValidationHandler#channelRead` |
| 12 | `stateless-mcp` | `StatelessValidatorHandler` | only stateless server: 404 on session/Last-Event-ID headers, 405 DELETE |
| 13 | `http-aggregator` | `CorsHttpObjectAggregator(maxContentLength)` | 413/417 with the request's CORS decision; Netty's close rule kept (`CorsHttpObjectAggregator#handleOversizedMessage`). `Expect` rejects are decorated through `CorsHttpObjectAggregator#newContinueResponse` using an independent copy; successful 100 Continue is unchanged |
| 14 | `unsupported-protocol-version` | `UnsupportedProtocolVersionHandler` | 400 JSON-RPC error with body `id` + supported list `UnsupportedProtocolVersionHandler#channelRead` |
| 15 | `interaction` | `InteractionHandler` | fallback protocol resolve for GET/DELETE; lifecycle events → ctx `InteractionHandler#userEventTriggered` |
| – | `authentication` | `AuthenticationHandler` (per channel) | only with a provider: authenticates each request off the event loop, sets `ChannelContext#setSecurityContext`; 401/400/500 → [[authentication]] |
| 16 | `idle` | `IdleStateHandler` | if reader/writer idle > 0 |
| 17 | `mcp-header-match` | `McpHeaderMatchHandler` | SEP-2243 mirror **agreement** vs body, every version, ungated `McpHeaderMatchHandler#channelRead` |
| 18 | `mcp-<ver>-*` | `Protocol.requestHandlers(server)` for each protocol | 2025: none; 2026: `RequestValidationHandler` (`_meta`/removed methods) → `RequiredHeadersHandler` (mirror **presence**) → `ExtensionNegotiationHandler` |
| 19 | `mcp-phase-init` | `McpInitializationHandler` (per channel) | first request |
| 20 | `lifecycle` | `LifecyclePipelineCoordinator` | replaces `mcp-phase-init` (phase 19) → `mcp-phase-operations` |
| – | customizer | `ServerBuilder.pipelineCustomizer` | user hook, runs last `InteractionHandler` |

## 🔁 Phase swap

- `InteractionEvent` sealed: `OperationStarted(session?)`, `ShutdownStarted(sessionId?)`, `ShutdownComplete` `InteractionEvent`.
- `LifecyclePipelineCoordinator` on `OperationStarted` → `pipeline.replace(init, ops, new McpOperationHandler)` `LifecyclePipelineCoordinator#userEventTriggered`; on `ShutdownStarted` → `McpHandlerManager.onShutdownStarted` removes session on executor `McpHandlerManager#onShutdownStarted`.
- POST without session id stays under the init handler during dispatch: `initialize` fires `OperationStarted(localSession)` **before** writing its response; other requests dispatch without a phase swap [McpInitializationHandler#handleRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java), [McpInitializationHandler#dispatchPreSessionRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java). Session-header requests, GET and DELETE forward to operations; OPTIONS answers directly [McpInitializationHandler#forwardToOperationHandler](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java). Both phases exempt heartbeat-enabled SSE from reader-idle closure only → [[sse-streams]].
- A pipelined request can't race the swap: `http-pipelining` holds it until `initialize`'s response is written, after `OperationStarted` fired.
- Init channel closed abruptly with a session ⇒ `ShutdownStarted` ⇒ session removed `InteractionEvent`.

## 🧷 Per-channel state

| Attr | Key | Proof |
|---|---|---|
| `ChannelContext` (protocol, session, lifecycle, enabled extensions, attrs, current request's security context) | `InteractionHandler.INTERACTION_CONTEXT_KEY` | `InteractionHandler#logger` |
| `Session` | `tachyonSession` | `ChannelHandlerUtils#SESSION_KEY` |
| rejected flag (drop rest of request) | `tachyonRequestRejected` | `ChannelHandlerUtils` |
| unsupported version | `unsupportedProtocolVersion` | `ProtocolVersionHandler.java` (`UNSUPPORTED_VERSION_KEY`) |
| first abnormal close cause | `closeFailure` (`setIfAbsent`) | [ChannelHandlerUtils#markCloseFailure](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ChannelHandlerUtils.java) |
| SSE heartbeat active/future | `sseHeartbeatActive` | `SseHeartbeat` |

Written by `PostSseStream#closeOnWriteFailure`, `SseHeartbeat#send` and `McpOperationHandler#exceptionCaught` — [[sse-streams]].

⚠️ Keep-alive socket may carry different protocol versions (proxy pooling). Stateless servers and 2026-07-28 get a fresh context per POST; stateful older protocols keep it only while the version matches. [ProtocolVersionHandler#channelRead](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ProtocolVersionHandler.java).

## 🌊 Backpressure

`HttpPipeliningGate` is the single `autoRead` owner: `autoRead = writable && nothing queued` `HttpPipeliningGate#channelWritabilityChanged`. Reads stop on the first queued pipelined request, so the queue holds at most one read's worth; clients that don't pipeline keep `autoRead` (and disconnect detection) untouched `HttpPipeliningGate#channelRead`. ⚠️ While a request is queued a client FIN is noticed only when the in-flight response completes or fails. Stuck in-flight request ⇒ reader-idle closes the channel as before. `Session.send` drops (returns false) when connection not writable — event stays in log for replay `Session#send`.

Sustained non-writability on a heartbeat stream ends in writer idle, which both phases honor [SseHeartbeat#ignoresIdle](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/sse/SseHeartbeat.java) → [[sse-streams]].

## 📤 Response helpers

`ChannelHandlerUtils` `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ChannelHandlerUtils.java`: `sendPlainTextAndClose`, `sendResponseAndClose` (keep-alive false ⇒ `Connection: close`), `sendAcceptedAsync` (202), `completeOn(future, transportCompletion)`, `isRefused` (RejectedExecution ⇒ 503 "Server shutting down"), `captureInitRequest` (detached headers copy for custom `SessionIdGenerator`, only if `readsRequest()`) `SessionIdGenerator`.

`rejectWithServerError(ctx, req, id, error)` `ChannelHandlerUtils#rejectWithServerError` is the shared JSON-RPC rejection for post-aggregation validators: maps a `ServerError` through the **negotiated** protocol's `responseMapper`, so the same call answers 400/-32020 on 2026-07-28 and 200/-32001 on 2025-11-25. Releases `req`.

Related: [[request-lifecycle]], [[security-guards]], [[sse-streams]].
