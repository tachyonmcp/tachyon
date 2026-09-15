---
title: Sessions
tags: [concept, session, state]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/SessionConfig.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/session/SessionIdGenerator.java]
updated: 2026-09-14
commit: 582f9c52
---

# 🪪 Sessions

Verdict: **stateless by default** (`SessionConfig.enabled=false`). Sessions exist only for 2025-11-25 clients on a server with `session { enabled(true) }`. Split: process-local `Session` runtime (connection, cursor, throttle) + immutable `SessionSnapshot` persisted in pluggable `SessionStore` with revision CAS → multi-node friendly.

## 🧬 Types

| Type | Role | Proof |
|---|---|---|
| `Session` | Runtime: id, `SessionKey`, `SessionState`, SSE connection, backpressure, cursor, extensions, protocol, log level, resuming stream key | `Session` |
| `SessionState` | `INITIALIZING → ACTIVE → (DRAINING) → CLOSED` | `SessionState` |
| `SessionKey(sessionId, generationId)` | generation fences stale work; UUID generation per create | `SessionKey`, `SessionManager#withLifecycleLock` |
| `SessionSnapshot` | transport-free state + `expiresAt` + `revision` | `SessionSnapshot` |
| `SessionStore` | `create/find/compareAndSet/touch/terminate`, sync, may do I/O, never on event loop | `SessionStore` |
| `SessionEventStore` | `append/drain/replay` event log for SSE resume | `SessionEventStore` |
| `SessionManager` | glue: local map + store + per-id lifecycle lock + janitor | `SessionManager` |
| `SessionIdGenerator<T>` | `DEFAULT` = `sess_<uuid-no-dashes>`, `readsRequest()=false` | `SessionIdGenerator` |

## 🔁 Lifecycle

1. `initialize` (no header) → `server.createSession(generateSessionId(ctx))` on VT `McpDispatcher#dispatchInitializeAsync`. Generator gets detached HTTP request copy if `readsRequest()` (`ATTR_INIT_REQUEST`) `McpDispatcher`. Blank id ⇒ `IllegalStateException`.
2. `DefaultDispatchContext.setSession` records negotiated protocol on session `DefaultDispatchContext#setSession`.
3. Response carries `MCP-Session-Id`; init handler fires `OperationStarted(session)` → bound into channel ctx.
4. `notifications/initialized` → `Session.activate()` CAS `INITIALIZING→ACTIVE` `Session#activate`. Before that only `ping` allowed `McpDispatcher#dispatchTrackedRequestAsync`.
5. `DELETE` with header → `removeSession` → 200 / 404 `McpOperationHandler#handleDelete`.
6. Channel close in init phase → `ShutdownStarted` → removal.
7. Janitor removes `CLOSED` or idle > TTL `SessionManager#sweep`.

## 💾 Persistence model

`SessionManager` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java`:
- `createSession` → `store.create(newKey, expiresAt)` under per-id `LifecycleLock` (ref-counted `ReentrantLock` map); replaced local session closed `SessionManager#createSession`, `SessionManager`.
- `getSession` → local map, else **hydrate** from store (skip + terminate if CLOSED or expired; incompatible protocol version ⇒ empty) `SessionManager`. `getLocalSession` never hits store (used on hot paths: GET SSE, redelivery).
- Mutations (`activate`, `protocol`, `enableExtension`, `loggingLevel`, `close`) call `onChange` → `persist` → `store.compareAndSet(expected, revision+1)`; lost CAS ⇒ **evict local** (another node owns it) `SessionManager#persist`.
- `touch()` → `onTouch` → async expiry refresh only when within `ttl/2` of `expiresAt`, deduped per key, on persistence executor `SessionManager`.
- In-memory store `InMemorySessionStore` — CAS requires same key + higher revision.

## 📚 Event log

`InMemorySessionEventStore` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/InMemorySessionEventStore.java`: caps **10 000 total / 512 per session** (`InMemorySessionEventStore#DEFAULT_MAX_EVENTS`), per-session FIFO, global oldest eviction via `TreeMap headIndex` O(log n), `ReentrantLock` (VT-safe), snapshot-then-process so slow consumer never blocks append.

`SessionEvent` sealed `SessionEvent`: `RequestEvent`, `OutboundRequestEvent`, `ResponseEvent`, `NotificationEvent`, `CancelEvent`. Each outbound carries `sseEventId` + `streamKey` for per-stream replay → [[sse-streams]].

## ⏱️ TTL & liveness

- Defaults: TTL **30s**, janitor **5s** `SessionConfig#DEFAULT_SESSION_TTL`; `SessionConfig` compact ctor rejects session options when disabled `SessionConfig#STATELESS`.
- Liveness bumped by: any request (`session.touch()` in dispatcher), any outbound byte (`SessionTouchHandler`), SSE heartbeat (15s default) — so open GET stream keeps session alive.
- `DefaultTachyonServer` passes `config.runtime().clock()` + executor as persistence executor `DefaultTachyonServer#DefaultTachyonServer`.

## 📨 Server → client

- Notifications: `server.sendNotification(session, …)` → event log append → deliver on bound POST-SSE stream (if dispatching same session) else GET connection `DefaultTachyonServer#sendSerializedNotification`.
- Requests (elicitation/sampling): `sendRequest` registers pending future with `runtime.requestTimeout` (60s) and ownership `DefaultTachyonServer`. Stateless dispatch ctx refuses: "Server-to-client requests require a session" `DefaultDispatchContext#sendRequest`.
- Broadcasts (`list_changed`, logs) iterate **ACTIVE local** sessions only `DefaultTachyonServer#broadcastNotification`, `DefaultTachyonServer#broadcastLog`.

Related: [[sse-streams]], [[configuration]], [[request-lifecycle]].
