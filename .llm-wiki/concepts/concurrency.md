---
title: Concurrency & shutdown
tags: [concept, concurrency, virtual-threads]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/OperationTracker.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/McpDispatcher.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/NettyServer.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/OutboundSseStreamMessageRouter.java]
updated: 2026-09-14
commit: 8c7738c0
---

# 🧵 Concurrency & shutdown

Verdict: **platform** threads for Netty I/O, **virtual thread per task** for everything else (parse, session lookup, handlers, finalization). Rule everywhere: no `synchronized` (VT pinning on Java 21), use `ReentrantLock`; never block event loop; writes marshalled back to `channel.eventLoop()`.

## 🗺️ Thread map

| Work | Thread | Proof |
|---|---|---|
| accept, decode HTTP, validation handlers, writes | `netty-io` platform EL | `NettyServer.java:57-62` |
| body parse + dispatch + handler | `tachyon-vt-N` VT (or custom `threadFactory`) | `DefaultTachyonServer.java:359-362`, `McpOperationHandler.java:131` |
| POST-SSE final response finalize | VT | `McpOperationHandler.java:367-372` |
| session/task janitors | daemon single-thread scheduler | `internal/AbstractJanitor.java:42-54` |
| slow handler watchdog log | daemon `handler-watchdog` (only when DEBUG) | `server/HandlerWatchdog.java:24-45` |
| extension shutdown | VT `ext-shutdown-<id>` | `DefaultTachyonServer.java:643-651` |
| Kotlin coroutines | dispatcher over server executor | [[tachyon-kotlin]] |

## 🔒 Lock inventory (all `ReentrantLock`)

`DefaultTachyonServer.lifecycleLock` (start/close) `:136`, `OperationTracker.lock` `OperationTracker.java:20`, `SessionManager.LifecycleLock` per id, `InMemorySessionEventStore.lock`, `DefaultResourceRegistry.writeLock`, `SubscriptionRegistry.lock` (ack-first atomicity), `TaskEntry.lock`. Comments cite JEP 491 (fixed Java 24) as reason. Commit `6edcabf3` "get rid of synchronized".

## 🧶 ThreadLocal dispatch context

`OutboundSseStreamMessageRouter.withDispatchContext(sessionId, stream, action)` sets ThreadLocals only during **decode + handler kickoff** `McpDispatcher.java:402-410`. Consequence: notifications sent synchronously from handler thread route onto POST-SSE stream; from another thread (async continuation) they fall back to session GET connection. Also `DefaultTaskRegistry.publish` reads owner session from it. Test: `tachyon-core/src/test/java/dev/tachyonmcp/core/transport/netty/ForeignThreadContinuationTest.java`.

## 🛑 Cancellation chain

client `notifications/cancelled` → `inboundRequests.get(key).cancel(true)` → `completion` cancel listener → `FutureTask.cancel(true)` (interrupts VT) + `handlerStage.cancel(true)` `McpDispatcher.java:412-427`, `:574-603`. `HandlerFutures.completeOn` propagates cancel from mapped to source `HandlerFutures.java:78-88`. `joinInterruptibly` restores interrupt flag `:35-51`.

## 🧮 Shutdown (graceful)

`DefaultTachyonServer.close()` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java:990-1041`:

1. Refuse if called on Netty event loop (would deadlock drain) `:1054-1060`.
2. `netty.stopAccepting()` — close server socket, keep children.
3. `deadline = now + runtime.shutdownGracePeriod` (5s default).
4. `operations.drain(deadline)` — stop admission, wait `active==0`. Admission counts until **both** dispatch future and `transportCompletion` (response flushed) done `OperationTracker.java:34-84`.
5. `executor.shutdown()` + await remaining, else `shutdownNow`.
6. `subscriptionRegistry.closeAll()` (graceful listen results), extensions shutdown within deadline, task janitor stop, `sessionManager.close()`, event store close.
7. `finally` `netty.close()`.

New requests during drain ⇒ `RejectedExecutionException` ⇒ 503 "Server shutting down". Tests: `ServerShutdownGraceTest`, e2e `ShutdownDrainTest`.

## ⚠️ Rules for new code

- Annotation registration is synchronous on the caller and delegates to existing registries; the group is not atomic (`DefaultTachyonServer.java:1044`). Spring invokes it before transport startup; handler dispatch still uses virtual threads.

- Handler may block (VT) but not pin: no `synchronized`, no long native calls; CPU-heavy → `context.engine().executor()` `RpcMethodHandler.java:14-25`.
- Any Netty write off EL → `eventLoop.execute` / `runOnEventLoop`; catch `RejectedExecutionException` on shutdown.
- `ByteBuf` ownership: `retain()` before async hop, `release()` in `finally`; rejecting handler must `markRejected` (releases + drops rest) `ChannelHandlerUtils.java:51-91`.
- Peeking handlers read `content().duplicate()` so downstream reader index intact.

Related: [[request-lifecycle]], [[sse-streams]].
