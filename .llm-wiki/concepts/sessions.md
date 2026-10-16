---
title: Sessions
tags: [concept, session, state]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/SessionConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-api/src/main/java/dev/tachyonmcp/api/server/session/SessionIdGenerator.java, tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpOperationHandler.java]
updated: 2026-10-16
commit: 747359f0
---

# 🪪 Sessions

Verdict: **stateless by default** (`SessionConfig.enabled=false`) — a stateless server keeps **no** session state at all (`SessionStore.noop()`, `SessionEventStore.noop()`). Stateless is a **server** property, not a session one — no session config ⇒ no sessions. Sessions exist only for 2025-11-25 clients on a stateful server; any `session(...)` option enables them on its own, `Builder#enabled` turns them on with defaults, `ServerBuilder#stateless` writes the opt-out down `SessionConfig.Builder#build`. Split: process-local `Session` runtime (connection, cursor, throttle) + immutable `SessionSnapshot` persisted in pluggable `SessionStore` with revision CAS → multi-node friendly.

## 🧬 Types

| Type | Role | Proof |
|---|---|---|
| `Session` | Runtime: id, `SessionKey`, `SessionState`, SSE connection, backpressure, cursor, extensions, protocol, log level, resuming stream key, **`Owner`** (final, restored from the snapshot's key; the `initialize` caller, anonymous or `Owner.Named`) → [[authentication]]. `send` hands every event to the connection (`false` only when none attached); slow-client hold/close is the connection's job → [[sse-streams]] | `Session`, `Session#send`, `Session#owner`, `Session#isOwnedBy` |
| `SessionState` | `INITIALIZING → ACTIVE → (DRAINING) → CLOSED` | `SessionState` |
| `SessionKey(sessionId, generationId)` | generation fences stale work; UUID generation per create | `SessionKey`, `SessionManager#withLifecycleLock` |
| `SessionSnapshot` | transport-free state + `ownerKey` + `expiresAt` + `revision`. The owner is fixed for the generation: every copy (`Session#snapshot`, `SessionManager#refreshed`, `InMemorySessionStore#touched`) passes it through, or a CAS would fail and evict the local session | `SessionSnapshot` |
| `SessionStore` | `create(key, ownerKey, expiresAt)/find/compareAndSet/touch/terminate` (by key, or conditional on an expected snapshot), sync, may do I/O, never on event loop; `noop()` when sessions off. `create` takes the owner so no node sees the session without it; a store that drops `ownerKey` restores sessions as anonymous and locks out their creator | `SessionStore`, `NoopSessionStore` |
| `SessionEventStore` | `append/drain/replay` event log for SSE resume; `noop()` when sessions off | `SessionEventStore`, `NoopSessionEventStore` |
| `SessionManager` | glue: local map + store + per-id lifecycle lock + janitor | `SessionManager` |
| `SessionIdGenerator<T>` | `DEFAULT` = `sess_<uuid-no-dashes>`, `readsRequest()=false` | `SessionIdGenerator` |

## 🔁 Lifecycle

1. `initialize` (no header) → `server.createSession(generateSessionId(ic), ic.securityContext())` on VT `McpDispatcher#dispatchInitializeAsync`; the `Owner` is derived inside `SessionManager#createSession` and its key goes into the first stored snapshot. Generator gets detached HTTP request copy if `readsRequest()` (`ATTR_INIT_REQUEST`) `McpDispatcher`. Blank id ⇒ `IllegalStateException`.
2. `DefaultDispatchContext.setSession` records negotiated protocol on session `DefaultDispatchContext#setSession`.
3. Response carries `MCP-Session-Id`; init handler fires `OperationStarted(session)` → bound into channel ctx.
4. `notifications/initialized` → `Session.activate()` CAS `INITIALIZING→ACTIVE` `Session#activate`. Before that only `ping` allowed `McpDispatcher#dispatchTrackedRequestAsync`.
5. `DELETE` with header → `removeSession` → 200 / 404 `McpOperationHandler#handleDelete`.
5a. **Owner binding.** Every request naming a session (POST of any kind, GET stream, `Last-Event-ID` resume, DELETE) first passes `McpOperationHandler#ownedBy`: the caller's `Owner` must equal `Session#owner`: an anonymous session serves only anonymous callers. Otherwise the lookup is empty and the answer is the unknown-session `404`, with a WARN and a truncated id; the session is not terminated. Restored sessions (`SessionManager#hydrateAbsent`) carry the owner from the snapshot. Internal lookups by id (task notifications, logging level) are not request lookups and skip the check → [[authentication]].
6. Channel close in init phase → `ShutdownStarted` → removal.
7. Janitor evicts `CLOSED` or idle > TTL locally; terminates the snapshot only if this node wrote last or it expired [SessionManager#sweep](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java), [SessionManager#terminateIfLastWriterOrExpired](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java).

## 💾 Persistence model

`SessionManager` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java`:
- `createSession` → `store.create(newKey, expiresAt)` under per-id `LifecycleLock` (ref-counted `ReentrantLock` map); replaced local session closed `SessionManager#createSession`, `SessionManager`.
- `getSession` → local map, else **hydrate** from store (skip + terminate if CLOSED or expired; incompatible protocol version ⇒ empty) `SessionManager`. `getLocalSession` never hits store (used on hot paths: GET SSE, redelivery).
- Mutations (`activate`, `protocol`, `enableExtension`, `loggingLevel`, `close`) call `onChange` → `persist` → `store.compareAndSet(expected, revision+1)`; lost CAS ⇒ **evict local** (another node owns it) `SessionManager#persist`.
- `removeSession` + janitor `sweep` → under the per-id lifecycle lock (same lock as hydrate, so a racing lookup can't restore the snapshot being deleted): drop from local map, then `session.close()` **before** `store.terminate`, so a slow store never holds the SSE stream or in-flight waiters open. Close always runs; `closeAndTerminate` throws, never logs: a `terminate` failure wins over a connection-close failure (attached as suppressed), and a close failure alone propagates too. One log per failure, at the caller: DELETE ⇒ 500 + ERROR (`McpOperationHandler`), janitor ⇒ WARN `SessionManager#sweep`, init-phase disconnect cleanup ⇒ WARN `McpHandlerManager#onShutdownStarted`. Snapshot stays restorable on any node until it expires — an idle TTL refreshed by activity via `SessionStore#touch`, not an absolute revocation deadline [SessionManager#closeAndTerminate](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java), [SessionManager#removeSession](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java), [McpHandlerManager#onShutdownStarted](../../tachyon-core/src/main/java/dev/tachyonmcp/core/transport/netty/McpHandlerManager.java), `SessionStore`. E2E: `SessionTerminationFailureTest` (DELETE ⇒ 500 + `Connection: close`, SSE stream ends, retry ⇒ 200).
- Janitor ≠ DELETE: idle expiry is local, so after `close()` it calls `terminateIfLastWriterOrExpired` — `find`, then `terminate` only when the current snapshot has the same key and either the revision equals `Session#persistedSnapshot` (no other node wrote) or `expiresAt` ≤ `clock` (expired anyway); the delete is the conditional `SessionStore#terminate(SessionKey, long)` on the key + revision `find` returned (atomic compare-and-delete; every write bumps the revision, so key + revision name one version), so a write from another node in between keeps it. Hydrate drops a CLOSED/expired snapshot the same way. Otherwise local eviction only: a node that hydrated and keeps touching the snapshot keeps serving. DELETE terminates unconditionally [SessionManager#terminateIfLastWriterOrExpired](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java), [SessionManager#removeIfCurrent](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java), [SessionManager#closeAndTerminate](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/SessionManager.java), [InMemorySessionStore#terminate](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/InMemorySessionStore.java). E2E: `SessionJanitorForeignSnapshotTest` (two nodes, shared store).
- `touch()` → `onTouch` → async expiry refresh only when within `ttl/2` of `expiresAt`, deduped per key, on persistence executor `SessionManager`.
- Store choice is resolved once per `build()` from the published `ServerConfig`, so `TachyonServer#config` exposes the very stores the server writes to `DefaultServerBuilder#build`, `SessionConfig#sessionStoreOrDefault`.
- ⚠️ Stateless ⇒ `NoopSessionStore`: `create` mints a snapshot it never keeps, `find` is always empty, and `compareAndSet`/`touch`/`terminate` answer **`true`** — "accepted, nothing to persist". A `false` would read as lost ownership and evict the local session on its first state change `SessionManager#persist`.
- In-memory store `InMemorySessionStore` — `ConcurrentHashMap` keyed by session id. CAS requires same key + higher revision `InMemorySessionStore#compareAndSet`. `touch`/`terminate` are lock-free `get` → `replace`/`remove` loops: replacement snapshot built outside the map's bin monitor, lost race re-reads and retries `InMemorySessionStore#touch`, `InMemorySessionStore#terminate`. JMH: `tachyon-core/src/test/java/dev/tachyonmcp/core/server/session/InMemorySessionStoreBenchmark.java` (`make jmh`). Manager create → remove cycle: `SessionManagerBenchmark#createAndRemove`; create → janitor expiry: [SessionManagerBenchmark#createAndExpire](../../tachyon-core/src/test/java/dev/tachyonmcp/core/server/session/SessionManagerBenchmark.java).

## 📚 Event log

Stateless ⇒ `NoopSessionEventStore`: `append` discards, `drain` returns the cursor, so `replay` is always empty `SessionEventStore#noop`.

`InMemorySessionEventStore` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/InMemorySessionEventStore.java`: caps **10 000 total / 512 per session** (`InMemorySessionEventStore#DEFAULT_MAX_EVENTS`), per-session FIFO, global oldest eviction via `TreeMap headIndex` O(log n), `ReentrantLock` (VT-safe), snapshot-then-process so slow consumer never blocks append.

`SessionEvent` sealed `SessionEvent`: `RequestEvent`, `OutboundRequestEvent`, `ResponseEvent`, `NotificationEvent`, `CancelEvent`. Each outbound carries `sseEventId` + `streamKey` for per-stream replay → [[sse-streams]].

## ⏱️ TTL & liveness

- Defaults: TTL **30s**, janitor **5s** `SessionConfig#DEFAULT_SESSION_TTL`; `SessionConfig` compact ctor rejects session options when disabled `SessionConfig#STATELESS`.
- Liveness bumped by: any request (`session.touch()` in dispatcher), any outbound byte (`SessionTouchHandler`), SSE heartbeat (15s default) — so open GET stream keeps session alive.
- In-flight request ⇒ tracked from dispatch to completion: dispatcher `Session#attachRequest` per request ⇒ `Session.InFlightRequest`. Connected ⇒ not idle; its stream's close ⇒ `InFlightRequest#disconnected` (touches); completion ⇒ `InFlightRequest#completed` (untracks). Janitor expires only `Session#idle` sessions `SessionManager#sweep`. A disconnected waiter never pins its session.
- `close` (local node only) completes each tracked `InFlightRequest#sessionClosed` ⇒ `InteractionContext#responseUndeliverable`, and fires `onChange`, even when the connection's `close` throws [Session#close](../../tachyon-core/src/main/java/dev/tachyonmcp/core/runtime/Session.java). Registrations die with the request: long sessions accumulate nothing.
- `DefaultTachyonServer` passes `config.runtime().clock()` + executor as persistence executor `DefaultTachyonServer#DefaultTachyonServer`.

## 📨 Server → client

- Notifications: `server.sendNotification(session, …)` → event log append → deliver on bound POST-SSE stream (if dispatching same session) else GET connection `DefaultTachyonServer#sendSerializedNotification`.
- Requests (elicitation/sampling): `sendRequest` registers pending future with `runtime.requestTimeout` (60s) and ownership `DefaultTachyonServer`. Stateless dispatch ctx refuses: "Server-to-client requests require a session" `DefaultDispatchContext#sendRequest`.
- Broadcasts (tools/resources/prompts `list_changed`, logs) iterate **ACTIVE local** sessions only `DefaultTachyonServer#broadcastNotification`, `DefaultTachyonServer#broadcastLog`.

[WireClientContext#create](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/WireClientContext.java)
encodes an `ElicitationRequest` record; `toElicitationResult` validates accepted content before
building the result. Missing/non-object content on `ACCEPT` fails; absent content on
`DECLINE`/`CANCEL` stays null. Value builders: [[tachyon-api]].

Related: [[sse-streams]], [[configuration]], [[request-lifecycle]].
