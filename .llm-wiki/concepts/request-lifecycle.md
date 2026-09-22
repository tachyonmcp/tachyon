---
title: Request lifecycle
tags: [concept, dispatch]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/RpcMethodHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/PeekedBody.java]
updated: 2026-09-21
commit: a5bf0b18
---

# 🔄 Request lifecycle

Verdict: event loop parses nothing heavy. Body hop → worker executor (VT) → `McpDispatcher` → `RpcMethodHandler.decode` → `handleAsync` → result mapped by protocol's response mapper → marshalled back to event loop → JSON (`application/json`) **or** SSE if handler pushed anything first.

## 🧵 Trace: POST `tools/call`

| # | Step | Thread | Proof |
|---|---|---|---|
| 1 | Pipeline guards (host, endpoint, headers, Accept, aggregate) | event loop | [[netty-pipeline]] |
| 2 | `ProtocolVersionHandler` binds `ChannelContext` for negotiated `Protocol` | event loop | `ProtocolVersionHandler#channelRead` |
| 3 | Every version: `McpHeaderMatchHandler` peeks body via `PeekedBody#peek` (first peek parses, the rest reuse it), compares SEP-2243 mirrors to it | event loop | [[protocol-versions]] |
| 3b | 2026-07-28 only: `RequestValidationHandler` (`_meta`, removed methods) → `RequiredHeadersHandler` (mirror presence) → `ExtensionNegotiationHandler`, same cached peek | event loop | [[protocol-versions]] |
| 4 | First request on channel hits `McpInitializationHandler`; non-`initialize` → fires `OperationStarted.STATELESS`, forwards to `McpOperationHandler` | event loop | `McpInitializationHandler#handleRequest`, `McpInitializationHandler#forwardToOperationHandler` |
| 5 | `handlePost`: capture interaction ctx **and** `PeekedBody#cached` **synchronously** (pipelined next request may rebind/overwrite), `body.retain()`, `runAsync(parseAndDispatchPost, executor)` | EL → VT | `McpOperationHandler#handlePost` |
| 6 | Session header ⇒ `server.getSession` (may hydrate from store) → 404 plain text if unknown | VT | `McpOperationHandler#parseAndDispatchPost` |
| 7 | Reuse the peeked parse if one was made, else parse here via `McpDispatcher#parseBody` → `Request` / `Response` / `Error` / `Notification`. Both routes yield a `JsonRpcCodec.Parse`, so a malformed body earns the same code either way — [[errors]] | VT | `McpOperationHandler#parseAndDispatchPost`, `McpOperationHandler#dispatchPostMessage` |
| 8 | New `PostSseStream` per request, `dispatchRequestAsync(... transportCompletion)` | VT | `McpOperationHandler#handlePostRequest` |
| 9 | `OperationTracker.execute` admission (refuse when closing) | VT | `McpDispatcher#dispatchRequestAsync` |
| 10 | Observation start; permitted log level from `_meta`; route | VT | `McpDispatcher` |
| 11 | `invokeHandlerAsync`: dup in-flight id ⇒ reject; `FutureTask` on executor; ThreadLocal dispatch ctx; `decode` then `handleAsync` | VT | `McpDispatcher`, `McpDispatcher#decodeAndHandleAsync` |
| 12 | `ToolsCallHandler.handleAsync`: name length, lookup, extension gate, input schema, task gate, invoke user fn | VT | `ToolsCallHandler` |
| 13 | Result → `mapResult` (task handoff / structured serialize / output schema) → `responseMapper.callToolResult` | VT | `ToolsCallHandler#mapResult` |
| 14 | `handleSuccessOrError` → `DispatchResult.Response(bytes, sessionId, 200)` | VT | `McpDispatcher#handleSuccessOrError` |
| 15 | `completePostRequest` on event loop: `Status` ⇒ plain HTTP; stream started ⇒ finalize on VT (append event log, write final SSE event, close); else JSON response | EL (+VT) | `McpOperationHandler`, `McpOperationHandler#finalizePostSseResponse` |
| 16 | `transportCompletion` completes when write flushes → `OperationTracker` releases | EL | `ChannelHandlerUtils.completeOn` `ChannelHandlerUtils#completeOn` |

## 🚦 Routing rules in `dispatchTrackedRequestAsync`

`McpDispatcher`:

- `initialize` + no session id ⇒ `dispatchInitializeAsync` (creates session unless stateless) `McpDispatcher#dispatchInitializeAsync`. With session id ⇒ `invalidRequest("Session already initialized")`.
- **Session bypass** when `server.isStateless()` **or** `!protocol.supportsSessions()` (2026-07-28) **or** pre-session `ping` `McpDispatcher#dispatchTrackedRequestAsync`.
- Stateful, no `MCP-Session-Id` ⇒ `DispatchResult.Status(400)` `McpDispatcher#dispatchTrackedRequestAsync`; unknown ⇒ `Status(404)` `McpDispatcher#dispatchTrackedRequestAsync`.
- Session `CLOSED` ⇒ invalid request; `INITIALIZING` ⇒ only `ping` `McpDispatcher#dispatchTrackedRequestAsync`.
- Handler resolved first (`server.getHandler`, none ⇒ `methodNotFound`); then `extensionNegotiationRejection` for extension-owned methods: `REQUIRED` + undeclared ⇒ missing required client capability; declared ⇒ dispatch (no `_meta` envelope); `OPTIONAL` ⇒ no check `McpDispatcher#dispatchTrackedRequestAsync`, `McpDispatcher#dispatchTrackedRequestAsync`, `McpDispatcher#invokeHandlerAsync`. See [[extensions]].

## 📨 Result shapes

`DispatchResult` sealed `DispatchResult`:

| Variant | Meaning |
|---|---|
| `Accepted` | 202, no body (notifications, client responses) |
| `Response(bytes, sessionId, httpStatus)` | JSON-RPC envelope; `httpStatus` from protocol mapper (2026-07-28 uses 400/404) |
| `Status(code, msg)` | raw HTTP, not JSON-RPC (missing/unknown session) |

Handler return value `ServerError` ⇒ error envelope (not exception) `ServerError`. Exceptions ⇒ `handleHandlerError`: `CancellationException` → internal error + `Cancelled` outcome; `RequestMappingException` → its error; else `internalError("Internal error")` `RequestMappingException`. Mapping table [[errors]].

## 🔔 Notifications & client responses

- Client notifications applied **before** 202 ack so client sees effect on next request (`initialized` activates session, `cancelled` cancels in-flight future) `McpOperationHandler#dispatchPostMessage`, `McpDispatcher`.
- Stateless server ignores notifications `McpDispatcher#dispatchNotification`.
- Client JSON-RPC `Response`/`Error` (answer to server→client request: elicitation/sampling) ⇒ 202 + `completePendingRequest`/`failPendingRequest` with ownership check (session id stateful, channel id stateless) `McpOperationHandler#handlePostResponse`, `DefaultTachyonServer#failPendingRequest`.
- Cancellation cancels `inboundRequests` future keyed `(sessionId, requestId)` → cascades to `FutureTask.cancel(true)` + handler stage cancel `McpDispatcher#invokeHandlerAsync`, `McpDispatcher#handleCancellation`.

## 🧱 Handler contract

`RpcMethodHandler` `RpcMethodHandler`: `decode(ctx, rawParams)` single seam (throw `RequestMappingException` for gating), `handle` sync, `handleAsync` default wraps sync. Runs on VT; no `synchronized` (`RequestMappingException#RequestMappingException`).

Feature handlers (tools/prompts/resources/completions) all use `HandlerFutures.invokeAndMap(nullMsg, invocation, executor, mapper)` `HandlerFutures#invokeAndMap` → null stage ⇒ NPE ⇒ error; completion hopped back to executor unless already done (`completeOn` `HandlerFutures#completeOn`).

Related: [[sse-streams]], [[concurrency]], [[feature-registries]].
