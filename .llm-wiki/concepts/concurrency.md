---
title: Concurrency & shutdown
tags: [concept, concurrency, virtual-threads]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/sse/PostSseStream.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/OperationTracker.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/NettyServer.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStreamMessageRouter.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/PeekedBody.java]
updated: 2026-09-28
commit: 16f22b51
---

# 🧵 Concurrency & shutdown

Verdict: **platform** threads for Netty I/O, **virtual thread per task** for everything else (parse, session lookup, handlers, finalization). Rule everywhere: no `synchronized` (VT pinning on Java 21), use `ReentrantLock`; never block event loop; writes marshalled back to `channel.eventLoop()`.

## 🗺️ Thread map

| Work | Thread | Proof |
|---|---|---|
| accept, decode HTTP, validation handlers, writes | `netty-io` platform EL | [NettyServer#NettyServer](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/NettyServer.java) |
| body parse + dispatch + handler | `tachyon-vt-N` VT (or custom `threadFactory`) | [DefaultTachyonServer#defaultExecutor](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java), [McpOperationHandler#handlePost](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java) |
| POST-SSE final response finalize | VT | [McpOperationHandler#completePostRequest](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java) |
| session/task janitors | daemon single-thread scheduler | [AbstractJanitor#start](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/AbstractJanitor.java) |
| slow handler watchdog log | daemon `handler-watchdog` (only when DEBUG) | [HandlerWatchdog#SCHEDULER](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/HandlerWatchdog.java) |
| extension shutdown | VT `ext-shutdown-<id>` | [DefaultTachyonServer#shutdownExtensions](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java) |
| transport-triggered subscription terminal observation | server executor; fallback VT after shutdown rejection (graceful shutdown uses its caller) | [SubscriptionsListenHandler#executeCompletion](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/handlers/SubscriptionsListenHandler.java) |
| Kotlin coroutines | dispatcher over server executor | [[tachyon-kotlin]] |

Ack timestamp publication and stream exception capture: [[observability]].

## 🔒 Lock inventory (all `ReentrantLock`)

`DefaultTachyonServer.lifecycleLock` (start/close) `DefaultTachyonServer#lifecycleLock`, `OperationTracker.lock` `OperationTracker#lock`, `SessionManager.LifecycleLock` per id, `InMemorySessionEventStore.lock`, `DefaultResourceRegistry.writeLock`, `SubscriptionRegistry.lock` (ack-first atomicity), `TaskEntry.lock`, `PostSseStream.capacityLock` (producers park for POST-SSE budget; event loop only signals, only with waiters `PostSseStream#signalCapacity`; interrupted park closes the stream `PostSseStream#reserve`). Comments cite JEP 491 (fixed Java 24) as reason. Commit `6edcabf3` "get rid of synchronized".

## 🧶 ThreadLocal dispatch context

`OutboundSseStreamMessageRouter.withDispatchContext(sessionId, stream, action)` sets ThreadLocals only during **decode + handler kickoff** `McpDispatcher#invokeHandlerAsync`. Consequence: notifications sent synchronously from handler thread route onto POST-SSE stream; from another thread (async continuation) they fall back to session GET connection. Task push routes instead come from the task-augmented tool call and stay fixed; routing in [DefaultTachyonServer#notifyTaskStatus](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java) is described in [[tasks]]. Test: `tachyon-core/src/test/java/dev/tachyonmcp/core/transport/netty/ForeignThreadContinuationTest.java`.

## 🛑 Cancellation chain

client `notifications/cancelled` → `inboundRequests.get(key).cancel(true)` → `completion` cancel listener → `FutureTask.cancel(true)` (interrupts VT) + `handlerStage.cancel(true)` `McpDispatcher#invokeHandlerAsync`, `McpDispatcher#handleCancellation`. `HandlerFutures.completeOn` propagates cancel from mapped to source `HandlerFutures#completeOn`. `joinInterruptible` restores interrupt flag `HandlerFutures#joinInterruptibly`.

## 🧮 Shutdown (graceful)

[DefaultTachyonServer#close](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java):

1. Refuse `close()`/`stop()` on a Netty event loop (would deadlock drain) [DefaultTachyonServer#requireNotOnEventLoop](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).
2. `netty.stopAccepting()` — close server socket, keep children. Bounded wait `NettyServer#await`.
3. `deadline = now + runtime.shutdownGracePeriod` (5s default).
4. `subscriptionRegistry.closeAll()` — graceful listen results first; a listen response pends for the stream's life, so a later drain would wait out the whole grace. Also closes the registry: a listen admitted earlier that activates later gets ack + graceful result at once `SubscriptionRegistry#activate`.
5. `operations.drain(deadline)` — stop admission, wait `active==0`. Admission counts until **both** dispatch future and `transportCompletion` (response flushed) done `OperationTracker`.
6. `executor.shutdown()` + await remaining, else `shutdownNow`.
7. Extensions shutdown within deadline, task janitor stop, `sessionManager.close()`, event store close.
8. `finally` `netty.close()` + `transport = null` — close children (unsent bytes dropped), `shutdownGracefully(0, grace)`. Each wait `awaitUninterruptibly(runtime.shutdownGracePeriod)`, read from the engine config: interrupt restored after, timeout ⇒ warn + continue, so neither an interrupted drain nor a wedged loop (only `pipelineCustomizer` handlers or a bug run there; user handlers run on the executor) hangs shutdown `NettyServer#close`.

New requests during drain ⇒ `RejectedExecutionException` ⇒ 503 "Server shutting down". Tests: `ServerShutdownGraceTest`, e2e `ShutdownDrainTest`, e2e `ServerRestartTest#shutdownEndsListenStreamGracefullyWithoutWaitingOutTheGracePeriod` (stop + close).

## 🔁 Stop / restart

`DefaultTachyonServer#stop` = transport-only subset of close, same `lifecycleLock` + event-loop guard: `stopAccepting` ⇒ `subscriptionRegistry.closeAll()` (listen responses pend for the stream's life, so complete them before the drain waits on their flush) ⇒ `drain(deadline)` ⇒ transport `close()` ⇒ `transport = null` ⇒ `OperationTracker#reopen` + `SubscriptionRegistry#reopen`. Keeps executor, extensions, registries, sessions, event store ⇒ `start()` binds a fresh `NettyServer` (port 0 ⇒ new port), sessions resume. No-op when not started or closed; `close()` stays terminal (`start()` ⇒ ISE "Server is closed"). Past the grace, unfinished responses are dropped: a slow client or handler never extends `stop()`. Normal case ≈ drain time + milliseconds; wedged event loop ≤ `shutdownGracePeriod` per wait (stopAccepting, drain, 3 close waits), never the client's pace. `Duration.ZERO` ⇒ no transport waits: closes proceed async, so an immediate rebind of a fixed port may race. Test: e2e `ServerRestartTest` (`#stopDropsUnfinishedRequestAfterGracePeriodAndRestarts`).

## ⚠️ Rules for new code

- Annotation registration is synchronous on the caller and delegates to existing registries; the group is not atomic (`DefaultTachyonServer#requireNotOnEventLoop`). Spring invokes it before transport startup; handler dispatch still uses virtual threads.

- Handler may block (VT) but not pin: no `synchronized`, no long native calls; CPU-heavy → `context.engine().executor()` `RpcMethodHandler`.
- Any Netty write off EL → `eventLoop.execute` / `runOnEventLoop`; catch `RejectedExecutionException` on shutdown.
- `ByteBuf` ownership: `retain()` before async hop, `release()` in `finally`; rejecting handler must `markRejected` (releases + drops rest) `ChannelHandlerUtils#rejectAndClose`.
- Peeking handlers call `PeekedBody.peek` — one parse per POST body, cached on a channel attribute keyed by the request instance, reused by the dispatch site via `PeekedBody.cached` (read on the event loop, before the async hop). It parses `content().duplicate()`, so the downstream reader index stays intact `PeekedBody#peek`.

Related: [[request-lifecycle]], [[sse-streams]].
