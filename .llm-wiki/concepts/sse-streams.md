---
title: SSE streams
tags: [concept, transport, sse]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/sse/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStream.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStreamMessageRouter.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java]
updated: 2026-09-20
commit: 04156c98
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
| `SseHeartbeat` | `:\r\n` comment every interval on EL; skip when unwritable; failed tick stashes the cause then closes | `SseHeartbeat#enable`, `SseHeartbeat#send` |
| `SseSerializer` | `id:/event:/data:` framing into pooled buf, split on newlines | `SseSerializer` |
| `OutboundSseStreamMessageRouter` | ThreadLocal `(sessionId, stream)` during dispatch → divert session notifications onto POST stream | `OutboundSseStreamMessageRouter#logger` |

## 📮 POST-SSE flow

1. `PostSseStream` created per POST; `streamKey` = one counter draw (not JSON-RPC id — clients reuse ids) `PostSseStream#PostSseStream`. That same draw is the priming event's id, so it precedes every id drawn later during dispatch — senders draw ids while the POST is still buffered (`DefaultTachyonServer#sendSerializedNotification`), so a freshly drawn priming id would outrank them and a resume from it would skip the notification.
2. Handler emits notification/progress/log/request → `start()` → writes 200 + SSE headers (`Connection: close`, `X-Accel-Buffering: no`) + enables heartbeat + priming event `id=<n>#<key>` with empty data (SEP-1699) unless events queued `PostSseStream#doStart`, `HttpHelpers#setSseStreamHeaders`. `start()` returns a `CompletionStage<Void>` that completes once that initial write (queued events included) is flushed — used by `subscriptions/listen` to time its ack, not just scheduling it `OutboundSseStream#start`, `SubscriptionsListenHandler#handleAsync` → [[observability]]. A second `start()` mirrors the outcome of the call that opened the stream (`comment()` self-start included) instead of reporting success early; `start()` on a closed stream fails `ClosedChannelException` `PostSseStream#doStart`.
3. `ctx.notifications().comment(msg)` self-starts stream → token-free keep-alive `PostSseStream#comment`, `NotificationsImpl#comment`.
4. Handler done, stream started ⇒ final response finalized on VT: append `ResponseEvent` to log (stateful), write, `terminateAsync()` (last chunk + close) `McpOperationHandler#finalizePostSseResponse`.
5. Stream never started ⇒ `terminate()` (neutralize so late message can't open a second response on pooled socket) then plain JSON `McpOperationHandler#completePostRequest`.
6. Final write dropped (client gone) ⇒ `redeliverOnReconnect`: if session's current GET connection resumed **this** stream key, send live; else wait for replay `McpOperationHandler#redeliverOnReconnect`.

Initial headers and every queued event are aggregated with Netty `PromiseCombiner`; a successful final write cannot hide an earlier failure [PostSseStream#doStart](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/sse/PostSseStream.java). The close attribute preserves the first failure with `setIfAbsent` [ChannelHandlerUtils#markCloseFailure](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/ChannelHandlerUtils.java).

## 👂 GET-SSE flow

- Stateless server: `openStatelessStream` — headers, `retry: 3000`, heartbeat, priming, nothing else `SseManager#openStatelessStream`.
- Stateful: requires `MCP-Session-Id` (400) and local or hydrated session (404) `McpOperationHandler#handleGet`.
- `openStream`: new `NettySseConnection` replaces session connection (previous one **closed**); close listener detaches only if still current `SseManager#openStream`, `Session#connection`.
- `Last-Event-ID` present ⇒ remember `resumingStreamKey`, replay on executor.
- 2026-07-28 has no GET (protocol `matches` POST only); uses `subscriptions/listen` POST stream instead → [[feature-registries]].

## ⏪ Replay

`SseManager.replayEvents` `SseManager#replayEvents`: parse `<n>[#<key>]`, take all session events, keep `sseId > n` **and** `streamKey == key` (null = GET stream), convert via `ServerEngine.toSseEvent` (request/cancel events skipped) `ServerEngine#wireEventId`, stop when `session.send` false (throttled/closed).

## 🫀 Keep-alive math

`NetworkConfig`: `readerIdleTimeout` 60s closes silent non-SSE sockets; heartbeat 15s keeps SSE; keep heartbeat < proxy idle timeouts and < session TTL (30s); heartbeats are outbound and never reset reader idle `NetworkConfig`, `NetworkConfig#DEFAULT_READER_IDLE_TIMEOUT`. Idle tick on SSE channel = no-op only in `McpOperationHandler#userEventTriggered` (requests carrying `MCP-Session-Id`). A POST without one — every 2026-07-28 request, `initialize` — runs under `McpInitializationHandler#userEventTriggered`, which closes on any idle tick, so reader idle still ends an upgraded stream there ([[findings]]).

## 🧯 Close semantics

- `close()` writes `retry: 3000` then last chunk + close (client should reconnect) vs `terminate()` no retry `PostSseStream#doClose`, `NettySseConnection#doClose`.
- `terminateAsync` also completes on channel close so shutdown drain never hangs — but consults the recorded close cause first, because a failed terminating write closes the channel from its own listener and that fallback would otherwise report success for it `PostSseStream#terminateAsync`.
- `doClose` cancels heartbeats before the terminating chunk: the scheduled tick is otherwise cancelled only on channel close and could emit a comment the HTTP encoder no longer accepts `SseHeartbeat#cancel`, `NettySseConnection#doClose`.
- Fire-and-forget calls (`writeEvent`, `comment`, `close`, `terminate`) swallow a shutting-down loop's rejection; `start` fails its stage and `writeEvent(long, byte[], Runnable)` runs `onDropped` instead `PostSseStream#runOnEventLoopQuietly`.
- Every write (headers, priming/queued, events, comments, retry, last chunk) shares one failure listener: stash cause via `ChannelHandlerUtils#markCloseFailure`, then close — so `onClose` reports a transport failure, not an ordinary disconnect `PostSseStream#closeOnWriteFailure`. A heartbeat is written by the scheduler, not that listener, and does the same for itself — it is how an idle stream finds a dead peer `SseHeartbeat#send`.
- SSE responses always `Connection: close` — server hard-closes on end; advertising keep-alive raced FIN vs next request `HttpHelpers#HttpHelpers`.

Related: [[sessions]], [[request-lifecycle]], [[concurrency]].
