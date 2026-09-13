---
title: Sessions
tags: [concept, session, state]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/SessionConfig.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/session/SessionIdGenerator.java]
updated: 2026-09-13
commit: 582f9c52
---

# 🪪 Sessions

Verdict: **stateless by default** (`SessionConfig.enabled=false`). Sessions exist only for 2025-11-25 clients on a server with `session { enabled(true) }`. Split: process-local `Session` runtime (connection, cursor, throttle) + immutable `SessionSnapshot` persisted in pluggable `SessionStore` with revision CAS → multi-node friendly.

## 🧬 Types

| Type | Role | Proof |
|---|---|---|
| `Session` | Runtime: id, `SessionKey`, `SessionState`, SSE connection, backpressure, cursor, extensions, protocol, log level, resuming stream key | `tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java:25` |
| `SessionState` | `INITIALIZING → ACTIVE → (DRAINING) → CLOSED` | `tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/SessionState.java:8-16` |
| `SessionKey(sessionId, generationId)` | generation fences stale work; UUID generation per create | `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionKey.java:14`, `SessionManager.java:192` |
| `SessionSnapshot` | transport-free state + `expiresAt` + `revision` | `SessionSnapshot.java:24-42` |
| `SessionStore` | `create/find/compareAndSet/touch/terminate`, sync, may do I/O, never on event loop | `SessionStore.java:15-34` |
| `SessionEventStore` | `append/drain/replay` event log for SSE resume | `SessionEventStore.java:12-31` |
| `SessionManager` | glue: local map + store + per-id lifecycle lock + janitor | `SessionManager.java:27` |
| `SessionIdGenerator<T>` | `DEFAULT` = `sess_<uuid-no-dashes>`, `readsRequest()=false` | `tachyon-api/src/main/java/dev/tachyonmcp/api/server/session/SessionIdGenerator.java:41-55` |

## 🔁 Lifecycle

1. `initialize` (no header) → `server.createSession(generateSessionId(ctx))` on VT `McpDispatcher.java:606-608`. Generator gets detached HTTP request copy if `readsRequest()` (`ATTR_INIT_REQUEST`) `McpDispatcher.java:670-679`. Blank id ⇒ `IllegalStateException`.
2. `DefaultDispatchContext.setSession` records negotiated protocol on session `DefaultDispatchContext.java:87-92`.
3. Response carries `MCP-Session-Id`; init handler fires `OperationStarted(session)` → bound into channel ctx.
4. `notifications/initialized` → `Session.activate()` CAS `INITIALIZING→ACTIVE` `Session.java:110-117`. Before that only `ping` allowed `McpDispatcher.java:317-320`.
5. `DELETE` with header → `removeSession` → 200 / 404 `McpOperationHandler.java:489-523`.
6. Channel close in init phase → `ShutdownStarted` → removal.
7. Janitor removes `CLOSED` or idle > TTL `SessionManager.java:165-184`.

## 💾 Persistence model

`SessionManager` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java`:
- `createSession` → `store.create(newKey, expiresAt)` under per-id `LifecycleLock` (ref-counted `ReentrantLock` map); replaced local session closed `:65-74`, `:190-238`.
- `getSession` → local map, else **hydrate** from store (skip + terminate if CLOSED or expired; incompatible protocol version ⇒ empty) `:77-130`. `getLocalSession` never hits store (used on hot paths: GET SSE, redelivery).
- Mutations (`activate`, `protocol`, `enableExtension`, `loggingLevel`, `close`) call `onChange` → `persist` → `store.compareAndSet(expected, revision+1)`; lost CAS ⇒ **evict local** (another node owns it) `:240-261`.
- `touch()` → `onTouch` → async expiry refresh only when within `ttl/2` of `expiresAt`, deduped per key, on persistence executor `:263-309`.
- In-memory store `InMemorySessionStore.java:13` — CAS requires same key + higher revision.

## 📚 Event log

`InMemorySessionEventStore` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/InMemorySessionEventStore.java`: caps **10 000 total / 512 per session** (`:32-33`), per-session FIFO, global oldest eviction via `TreeMap headIndex` O(log n), `ReentrantLock` (VT-safe), snapshot-then-process so slow consumer never blocks append.

`SessionEvent` sealed `SessionEvent.java:10`: `RequestEvent`, `OutboundRequestEvent`, `ResponseEvent`, `NotificationEvent`, `CancelEvent`. Each outbound carries `sseEventId` + `streamKey` for per-stream replay → [[sse-streams]].

## ⏱️ TTL & liveness

- Defaults: TTL **30s**, janitor **5s** `SessionConfig.java:34-35`; `SessionConfig` compact ctor rejects session options when disabled `:39-53`.
- Liveness bumped by: any request (`session.touch()` in dispatcher), any outbound byte (`SessionTouchHandler`), SSE heartbeat (15s default) — so open GET stream keeps session alive.
- `DefaultTachyonServer` passes `config.runtime().clock()` + executor as persistence executor `DefaultTachyonServer.java:316-320`.

## 📨 Server → client

- Notifications: `server.sendNotification(session, …)` → event log append → deliver on bound POST-SSE stream (if dispatching same session) else GET connection `DefaultTachyonServer.java:788-812`.
- Requests (elicitation/sampling): `sendRequest` registers pending future with `runtime.requestTimeout` (60s) and ownership `DefaultTachyonServer.java:828-924`. Stateless dispatch ctx refuses: "Server-to-client requests require a session" `DefaultDispatchContext.java:165-172`.
- Broadcasts (`list_changed`, logs) iterate **ACTIVE local** sessions only `DefaultTachyonServer.java:463-472`, `:723-741`.

Related: [[sse-streams]], [[configuration]], [[request-lifecycle]].
