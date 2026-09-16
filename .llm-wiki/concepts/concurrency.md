---
title: Concurrency & shutdown
tags: [concept, concurrency, virtual-threads]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/OperationTracker.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/NettyServer.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStreamMessageRouter.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/PeekedBody.java]
updated: 2026-09-17
commit: 7cf92303
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
| Kotlin coroutines | dispatcher over server executor | [[tachyon-kotlin]] |

## 🔒 Lock inventory (all `ReentrantLock`)

`DefaultTachyonServer.lifecycleLock` (start/close) `DefaultTachyonServer#lifecycleLock`, `OperationTracker.lock` `OperationTracker#lock`, `SessionManager.LifecycleLock` per id, `InMemorySessionEventStore.lock`, `DefaultResourceRegistry.writeLock`, `SubscriptionRegistry.lock` (ack-first atomicity), `TaskEntry.lock`. Comments cite JEP 491 (fixed Java 24) as reason. Commit `6edcabf3` "get rid of synchronized".

## 🧶 ThreadLocal dispatch context

`OutboundSseStreamMessageRouter.withDispatchContext(sessionId, stream, action)` sets ThreadLocals only during **decode + handler kickoff** `McpDispatcher#invokeHandlerAsync`. Consequence: notifications sent synchronously from handler thread route onto POST-SSE stream; from another thread (async continuation) they fall back to session GET connection. Also `DefaultTaskRegistry.publish` reads owner session from it. Test: `tachyon-core/src/test/java/dev/tachyonmcp/core/transport/netty/ForeignThreadContinuationTest.java`.

## 🛑 Cancellation chain

client `notifications/cancelled` → `inboundRequests.get(key).cancel(true)` → `completion` cancel listener → `FutureTask.cancel(true)` (interrupts VT) + `handlerStage.cancel(true)` `McpDispatcher#invokeHandlerAsync`, `McpDispatcher#handleCancellation`. `HandlerFutures.completeOn` propagates cancel from mapped to source `HandlerFutures#completeOn`. `joinInterruptibly` restores interrupt flag `HandlerFutures#joinInterruptibly`.

## 🧮 Shutdown (graceful)

[DefaultTachyonServer#close](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java):

1. Refuse if called on Netty event loop (would deadlock drain) [DefaultTachyonServer#requireNotOnEventLoop](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java).
2. `netty.stopAccepting()` — close server socket, keep children.
3. `deadline = now + runtime.shutdownGracePeriod` (5s default).
4. `operations.drain(deadline)` — stop admission, wait `active==0`. Admission counts until **both** dispatch future and `transportCompletion` (response flushed) done `OperationTracker`.
5. `executor.shutdown()` + await remaining, else `shutdownNow`.
6. `subscriptionRegistry.closeAll()` (graceful listen results), extensions shutdown within deadline, task janitor stop, `sessionManager.close()`, event store close.
7. `finally` `netty.close()`.

New requests during drain ⇒ `RejectedExecutionException` ⇒ 503 "Server shutting down". Tests: `ServerShutdownGraceTest`, e2e `ShutdownDrainTest`.

## ⚠️ Rules for new code

- Annotation registration is synchronous on the caller and delegates to existing registries; the group is not atomic (`DefaultTachyonServer#requireNotOnEventLoop`). Spring invokes it before transport startup; handler dispatch still uses virtual threads.

- Handler may block (VT) but not pin: no `synchronized`, no long native calls; CPU-heavy → `context.engine().executor()` `RpcMethodHandler`.
- Any Netty write off EL → `eventLoop.execute` / `runOnEventLoop`; catch `RejectedExecutionException` on shutdown.
- `ByteBuf` ownership: `retain()` before async hop, `release()` in `finally`; rejecting handler must `markRejected` (releases + drops rest) `ChannelHandlerUtils#rejectAndClose`.
- Peeking handlers call `PeekedBody.peek` — one parse per POST body, cached on a channel attribute keyed by the request instance, reused by the dispatch site via `PeekedBody.cached` (read on the event loop, before the async hop). It parses `content().duplicate()`, so the downstream reader index stays intact `PeekedBody#peek`.

Related: [[request-lifecycle]], [[sse-streams]].
