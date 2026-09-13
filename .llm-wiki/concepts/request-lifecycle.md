---
title: Request lifecycle
tags: [concept, dispatch]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/RpcMethodHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpInitializationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-13
commit: 582f9c52
---

# 🔄 Request lifecycle

Verdict: event loop parses nothing heavy. Body hop → worker executor (VT) → `McpDispatcher` → `RpcMethodHandler.decode` → `handleAsync` → result mapped by protocol's response mapper → marshalled back to event loop → JSON (`application/json`) **or** SSE if handler pushed anything first.

## 🧵 Trace: POST `tools/call`

| # | Step | Thread | Proof |
|---|---|---|---|
| 1 | Pipeline guards (host, endpoint, headers, Accept, aggregate) | event loop | [[netty-pipeline]] |
| 2 | `ProtocolVersionHandler` binds `ChannelContext` for negotiated `Protocol` | event loop | `tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ProtocolVersionHandler.java:48` |
| 3 | 2026-07-28 only: `RequestValidationHandler` + `ExtensionNegotiationHandler` peek body | event loop | [[protocol-versions]] |
| 4 | First request on channel hits `McpInitializationHandler`; non-`initialize` → fires `OperationStarted.STATELESS`, forwards to `McpOperationHandler` | event loop | `McpInitializationHandler.java:84-102`, `:321-338` |
| 5 | `handlePost`: capture interaction ctx **synchronously** (pipelined next request may rebind), `body.retain()`, `runAsync(parseAndDispatchPost, executor)` | EL → VT | `McpOperationHandler.java:115-147` |
| 6 | Session header ⇒ `server.getSession` (may hydrate from store) → 404 plain text if unknown | VT | `McpOperationHandler.java:154-186` |
| 7 | Parse JSON-RPC → `Request` / `Response` / `Error` / `Notification` | VT | `McpOperationHandler.java:188-239` |
| 8 | New `PostSseStream` per request, `dispatchRequestAsync(... transportCompletion)` | VT | `McpOperationHandler.java:269-306` |
| 9 | `OperationTracker.execute` admission (refuse when closing) | VT | `McpDispatcher.java:234-248` |
| 10 | Observation start; permitted log level from `_meta`; route | VT | `McpDispatcher.java:250-329` |
| 11 | `invokeHandlerAsync`: dup in-flight id ⇒ reject; `FutureTask` on executor; ThreadLocal dispatch ctx; `decode` then `handleAsync` | VT | `McpDispatcher.java:344-426`, `:432-440` |
| 12 | `ToolsCallHandler.handleAsync`: name length, lookup, extension gate, input schema, task gate, invoke user fn | VT | `ToolMethodHandlers.java:106-164` |
| 13 | Result → `mapResult` (task handoff / structured serialize / output schema) → `responseMapper.callToolResult` | VT | `ToolMethodHandlers.java:180-216` |
| 14 | `handleSuccessOrError` → `DispatchResult.Response(bytes, sessionId, 200)` | VT | `McpDispatcher.java:475-504` |
| 15 | `completePostRequest` on event loop: `Status` ⇒ plain HTTP; stream started ⇒ finalize on VT (append event log, write final SSE event, close); else JSON response | EL (+VT) | `McpOperationHandler.java:308-402`, `:404-435` |
| 16 | `transportCompletion` completes when write flushes → `OperationTracker` releases | EL | `ChannelHandlerUtils.completeOn` `ChannelHandlerUtils.java:191` |

## 🚦 Routing rules in `dispatchTrackedRequestAsync`

`McpDispatcher.java:250-329`:

- `initialize` + no session id ⇒ `dispatchInitializeAsync` (creates session unless stateless) `:588-627`. With session id ⇒ `invalidRequest("Session already initialized")`.
- **Session bypass** when `server.isStateless()` **or** `!protocol.supportsSessions()` (2026-07-28) **or** pre-session `ping` `:281-292`.
- Stateful, no `MCP-Session-Id` ⇒ `DispatchResult.Status(400)` `:294-298`; unknown ⇒ `Status(404)` `:300-305`.
- Session `CLOSED` ⇒ invalid request; `INITIALIZING` ⇒ only `ping` `:313-320`.
- `lookupHandler`: method owned by extension ⇒ extension must be enabled on ctx, and `_meta.<extId>` present if `requiresMetaEnvelope()` — else `methodNotFound` `:331-342`. See [[extensions]].

## 📨 Result shapes

`DispatchResult` sealed `McpDispatcher.java:160-193`:

| Variant | Meaning |
|---|---|
| `Accepted` | 202, no body (notifications, client responses) |
| `Response(bytes, sessionId, httpStatus)` | JSON-RPC envelope; `httpStatus` from protocol mapper (2026-07-28 uses 400/404) |
| `Status(code, msg)` | raw HTTP, not JSON-RPC (missing/unknown session) |

Handler return value `ServerError` ⇒ error envelope (not exception) `:482-488`. Exceptions ⇒ `handleHandlerError`: `CancellationException` → internal error + `Cancelled` outcome; `RequestMappingException` → its error; else `internalError("Internal error")` `:442-473`. Mapping table [[errors]].

## 🔔 Notifications & client responses

- Client notifications applied **before** 202 ack so client sees effect on next request (`initialized` activates session, `cancelled` cancels in-flight future) `McpOperationHandler.java:221-233`, `McpDispatcher.java:510-586`.
- Stateless server ignores notifications `McpDispatcher.java:519-523`.
- Client JSON-RPC `Response`/`Error` (answer to server→client request: elicitation/sampling) ⇒ 202 + `completePendingRequest`/`failPendingRequest` with ownership check (session id stateful, channel id stateless) `McpOperationHandler.java:241-267`, `DefaultTachyonServer.java:875-898`.
- Cancellation cancels `inboundRequests` future keyed `(sessionId, requestId)` → cascades to `FutureTask.cancel(true)` + handler stage cancel `McpDispatcher.java:395-410`, `:557-586`.

## 🧱 Handler contract

`RpcMethodHandler` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/RpcMethodHandler.java:27`: `decode(ctx, rawParams)` single seam (throw `RequestMappingException` for gating), `handle` sync, `handleAsync` default wraps sync. Runs on VT; no `synchronized` (`:14-25`).

Feature handlers (tools/prompts/resources/completions) all use `HandlerFutures.invokeAndMap(nullMsg, invocation, executor, mapper)` `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java:120` → null stage ⇒ NPE ⇒ error; completion hopped back to executor unless already done (`completeOn` `:78`).

Related: [[sse-streams]], [[concurrency]], [[feature-registries]].
