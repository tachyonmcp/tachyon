---
title: SSE streams
tags: [concept, transport, sse]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/sse/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStream.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStreamMessageRouter.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java]
updated: 2026-09-14
commit: 582f9c52
---

# 📡 SSE streams

Verdict: two stream kinds. **POST-SSE** = per-request, lazy: JSON response unless handler emits server→client message first, then upgrades to `text/event-stream` and final response is last event. **GET-SSE** = session listening stream (stateful) or empty stream (stateless). Every event id is globally unique counter, suffixed `#<streamKey>` for POST streams → replay never crosses streams.

## 🧱 Abstractions

| Type | Role | Proof |
|---|---|---|
| `OutboundSseStream` | transport-neutral: `start`, `started`, `writeEvent`, `comment`, `close`, `onClose`, `streamKey`, `channelId` | `OutboundSseStream` |
| `PostSseStream` | Netty impl, state machine `NEW/OPEN/CLOSED_UNOPENED/CLOSED_OPENED`, all mutations on event loop | `PostSseStream` |
| `NettySseConnection` | `SseConnection` for GET stream, close listener | `NettySseConnection` |
| `SseManager` | open GET streams, priming, replay | `SseManager` |
| `SseHeartbeat` | `:\r\n` comment every interval on EL; skip when unwritable | `SseHeartbeat#enable` |
| `SseSerializer` | `id:/event:/data:` framing into pooled buf, split on newlines | `SseSerializer` |
| `OutboundSseStreamMessageRouter` | ThreadLocal `(sessionId, stream)` during dispatch → divert session notifications onto POST stream | `OutboundSseStreamMessageRouter#logger` |

## 📮 POST-SSE flow

1. `PostSseStream` created per POST; `streamKey` = one counter draw (not JSON-RPC id — clients reuse ids) `PostSseStream#PostSseStream`.
2. Handler emits notification/progress/log/request → `start()` → writes 200 + SSE headers (`Connection: close`, `X-Accel-Buffering: no`) + enables heartbeat + priming event `id=<n>#<key>` with empty data (SEP-1699) unless events queued `PostSseStream#doStart`, `HttpHelpers#setSseStreamHeaders`.
3. `ctx.notifications().comment(msg)` self-starts stream → token-free keep-alive `PostSseStream#comment`, `NotificationsImpl#comment`.
4. Handler done, stream started ⇒ final response finalized on VT: append `ResponseEvent` to log (stateful), write, `terminateAsync()` (last chunk + close) `McpOperationHandler#finalizePostSseResponse`.
5. Stream never started ⇒ `terminate()` (neutralize so late message can't open a second response on pooled socket) then plain JSON `McpOperationHandler#completePostRequest`.
6. Final write dropped (client gone) ⇒ `redeliverOnReconnect`: if session's current GET connection resumed **this** stream key, send live; else wait for replay `McpOperationHandler#redeliverOnReconnect`.

## 👂 GET-SSE flow

- Stateless server: `openStatelessStream` — headers, `retry: 3000`, heartbeat, priming, nothing else `SseManager#openStatelessStream`.
- Stateful: requires `MCP-Session-Id` (400) and local or hydrated session (404) `McpOperationHandler#handleGet`.
- `openStream`: new `NettySseConnection` replaces session connection (previous one **closed**); close listener detaches only if still current `SseManager#openStream`, `Session#connection`.
- `Last-Event-ID` present ⇒ remember `resumingStreamKey`, replay on executor.
- 2026-07-28 has no GET (protocol `matches` POST only); uses `subscriptions/listen` POST stream instead → [[feature-registries]].

## ⏪ Replay

`SseManager.replayEvents` `SseManager#replayEvents`: parse `<n>[#<key>]`, take all session events, keep `sseId > n` **and** `streamKey == key` (null = GET stream), convert via `ServerEngine.toSseEvent` (request/cancel events skipped) `ServerEngine#wireEventId`, stop when `session.send` false (throttled/closed).

## 🫀 Keep-alive math

`NetworkConfig`: `readerIdleTimeout` 60s closes silent non-SSE sockets; heartbeat 15s keeps SSE; keep heartbeat < reader idle and < session TTL (30s) `NetworkConfig`, `NetworkConfig#DEFAULT_READER_IDLE_TIMEOUT`. Idle tick on SSE channel = no-op `McpOperationHandler#userEventTriggered`.

## 🧯 Close semantics

- `close()` writes `retry: 3000` then last chunk + close (client should reconnect) vs `terminate()` no retry `PostSseStream#doClose`, `NettySseConnection#doClose`.
- `terminateAsync` also completes on channel close so shutdown drain never hangs `PostSseStream#terminateAsync`.
- SSE responses always `Connection: close` — server hard-closes on end; advertising keep-alive raced FIN vs next request `HttpHelpers#HttpHelpers`.

Related: [[sessions]], [[request-lifecycle]], [[concurrency]].
