---
title: Tasks
tags: [concept, tasks, experimental]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/DefaultToolRegistry.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/, integrations/tachyon-tasks-temporal/]
updated: 2026-09-30
commit: 5a68b8dd
---

# ⏳ Tasks

Verdict: Tachyon does **not** run tasks. External system owns execution via `TaskConnector` functions; Tachyon keeps a revision-ordered projection cache (`TaskSnapshot`), maps MCP task methods to connector calls, and pushes status/progress notifications. All `@ExperimentalApi`.

## 🧱 Types

| Type | Role | Proof |
|---|---|---|
| `TaskConnector` | required `get`, `cancel`, `update`; `@LegacyApi` optional `list`, `awaitResult` | `TaskConnector` |
| `TaskSnapshot` | taskId, status, timestamps, `revision`, pollInterval, result/error, meta; factories `working/inputRequired/completed/failed/cancelled` | `TaskSnapshot` |
| `TaskState` | `SUBMITTED, REJECTED*, AUTH_REQUIRED, WORKING, INPUT_REQUIRED, COMPLETED*, FAILED*, CANCELLED*, UNKNOWN*` (*terminal) | `TaskState` |
| `TaskSupport` | tool-level `FORBIDDEN / OPTIONAL / REQUIRED` | `TaskSupport` |
| `Tasks` (façade) | `publish`, `get`, `remove`, `reportProgress`; `publish(Consumer<TaskSnapshot.Builder>)` default = build ⇒ `publish(TaskSnapshot)` (invalid ⇒ IAE from `TaskSnapshot#check`, nothing published). `TaskSnapshot.Builder#next(previous)` = `from` + `revision + 1`: a not-newer revision is silently ignored (`TaskEntry#publish`) | `Tasks` |
| `TaskEngine` (`…extensions.tasks.engine`, protocol-neutral) | revision cache, route store, TTL janitor, `TaskEvents` push; implements `Tasks` | `TaskEngine` |
| `TaskRoute` / `TaskEvents` (engine) | opaque delivery address (`NONE`) / status + progress listener | `TaskRoute`, `TaskEvents` |
| `McpTaskBinding` (MCP binding) | core `TaskRuntime` + `TaskEvents` ⇒ `notifyTaskStatus`/`notifyTaskProgress`, 2025 capability | `McpTaskBinding` |
| `McpTaskRoute` | MCP route: sessionId + progressToken; `of(null,null)` ⇒ `NONE` | `McpTaskRoute` |
| `TasksExtension` (module `tachyon-extensions-tasks`, `@ProvidedBy(TasksExtensionProvider)`) | id `io.modelcontextprotocol/tasks`, `ALWAYS`; `tasks()` ⇒ engine; builder: connector (required), pageSize, keepAlive, pollInterval, resultPollInterval | `TasksExtension` |
| `TasksExtensionProvider` (`@InternalApi`) | creates `Builder`; `EngineBinding` ⇒ `TasksExtension#install`, `TasksExtension#attach` | `TasksExtensionProvider` |
| `TaskRuntime` (core seam, `@InternalApi`) | what tool/subscription handlers need; `NONE` when no extension | `TaskRuntime` |
| `TasksExtensionSupport` (core, `@InternalApi`) | id + per-request gate `requireDeclared` | `TasksExtensionSupport` |

Boundary: `engine` imports no protocol types, enforced by `EngineBoundaryTest` (future `tachyon-tasks` module for A2A; see [[findings]]).

## ⚙️ Config

`withExtension(TasksExtension.class, t -> t.connector(c))` ⇒ registered = enabled; no capability switch, no auto-registration. No connector ⇒ ISE `TasksExtension.Builder#build`; `TaskEngineSettings` validates pageSize/pollInterval > 0, keepAlive default **5 min** (`TaskEngineSettings#DEFAULT_KEEP_ALIVE`). Kotlin: `tasks(connector) { }` on `TachyonServerBuilder`. Runtime access: [TasksExtension#tasks(TachyonServer)](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/TasksExtension.java) (callbacks) and `TasksExtension#tasks(InteractionContext)` (tool handlers, via `InteractionContext#extension`). Both ⇒ ISE "TasksExtension is not registered…" when absent; thrown in a tool handler ⇒ `-32603`. Instance `TasksExtension#tasks()` ⇒ ISE before build. Façade server-scoped: background work keeps it, never `ctx`. Kotlin: `TachyonServer.tasks` + `ToolScope#tasks`. Install phase `TasksExtension#install` (before any extension bootstraps): engine ⇒ `McpTaskBinding` listener ⇒ `ServerEngine#installTaskRuntime`. So task-capable tools registered in another extension's `bootstrap` pass the `DefaultToolRegistry#register` check even when that extension comes first. Bootstrap `TasksExtension#attach`: `TaskMethodHandlers#register` ⇒ janitor start.

Registration check: registering a `REQUIRED` or `OPTIONAL` tool w/o tasks extension (`TaskRuntime#executionConfigured`) ⇒ `IllegalStateException("Tool '<name>' declares task support, which requires TasksExtension")`, at build (bootstrap registrations ⇒ build fails, server closed) or after it `DefaultToolRegistry#register`. Extensions bootstrap before registrations, so the runtime is already installed.

## 🔁 Task-producing tool call

`ToolMethodHandlers` `validateTaskRequest` `ToolsCallHandler#validateTaskRequest` + `mapResult` `ToolsCallHandler#mapResult`:

| Protocol | Rule |
|---|---|
| 2025-11-25 (legacy augmentation) | client sends task-augmented call; `FORBIDDEN`/absent + augmented ⇒ -32601; `REQUIRED` + plain ⇒ -32601 (§ Tool-Level Negotiation) |
| 2026-07-28 | no augmentation flag; `REQUIRED` ⇒ tasks extension must be declared (else -32021) |

Tool returns `ToolResult.task(snapshot)` ⇒ checks (not FORBIDDEN, legacy must be augmented, extension declared on modern) ⇒ `taskRuntime().publish(snapshot, context.sessionId(), progressToken)` ⇒ `McpTaskRoute.of(...)` (explicit: async tools map off the dispatch thread); never refused ⇒ `createTaskResult`, observation outcome `TaskHandoff`. Non-task result for REQUIRED/augmented ⇒ internal error.

## 🗂️ Registry semantics

Verdict: cache + push **routing**, no access control. A `TaskRoute` (session + progress token) is a delivery address, never an owner; the connector authorizes every `tasks/*` call. Without an authorization context the task id is a bearer capability (2025-11-25 Tasks § Security).

- effective pollInterval = snapshot's or config default `TaskEngine#withDefaults`.
- `publish(snapshot)` = `publish(snapshot, TaskRoute.NONE)`: upsert. Uncached ⇒ cached unrouted; cached ⇒ `TaskEntry#publish` accepts only **higher revision**; changed ⇒ `TaskEntry#notifyIfNewer` `TaskEngine#publish`. No cache-change callback: publish/remove/eviction push nothing beyond per-task status.
- `publish(snapshot, route)` (tool path): `putIfAbsent`; existing entry takes `route` only if unrouted (`TaskEntry#route(TaskRoute)`: set once, never re-routed, colliding call succeeds) `TaskEngine#publish`. Route attach itself pushes nothing: the tool result carries that revision; revisions sent before attach are not re-sent.
- `tasks/get|cancel|result` publish the connector's snapshot ⇒ cached unrouted; a read never routes. `tasks/list` never caches.
- Route source: only the task-augmented `tools/call`, never a thread, facade or read. No `InteractionContext#tasks()`.
- Task ids come from the tool or engine, never Tachyon; unguessability is their job (spec MUST without context binding).
- `reportProgress(taskId, …)` fires `TaskEvents#onProgress`; the MCP binding drops it without an `McpTaskRoute` progressToken `McpTaskBinding#onProgress`.
- Threading: `TaskEntry` lock guards publish + route + notify. `TaskEntry#notifyIfNewer` gates on `notifiedRevision`, so a publisher that lost the race never sends a stale status. Sends run under the lock, so session status uses `offerEvent` (slow client ⇒ its stream closes, publishers never park) `DefaultTachyonServer#notifyTaskStatus`.
- Janitor every 30s removes entries past `createdAt + ttl` (any status, any route) or terminal and cached longer than keepAlive `TaskEntry#isExpired`, `TaskEngine#runJanitorSweep`. Eviction drops the route: later status reaches listeners only.

Notification routing [McpTaskBinding#onStatus](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/McpTaskBinding.java) ⇒ [DefaultTachyonServer#notifyTaskStatus](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java): routed ⇒ that session gets `notifications/tasks/status` (gone ⇒ nothing, no fallback). The binding also calls [SubscriptionRegistry#publish](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/subscriptions/SubscriptionRegistry.java) for `notifications/tasks` on streams naming the task id. The tasks extension owns the `taskIds` topic: [McpTaskBinding#taskIdsTopic](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/McpTaskBinding.java) requires client declaration and narrows ids via [TaskEngine#readableTaskIds](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/engine/TaskEngine.java) (connector `get` per id with listener context, fail closed). The ack echoes only honored ids. No tasks extension ⇒ `taskIds` is unknown and ignored. Checked once per stream, not per event. Eviction/route loss changes nothing for listeners. Never broadcast. [McpResponseMapper#subscriptionNotificationParams](../../tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/codecs/McpResponseMapper.java) merges the stream's `_meta.io.modelcontextprotocol/subscriptionId` over snapshot metadata (server key wins); session `notifications/tasks/status` stays on `taskStatusNotificationParams` without it. Session payloads use the session protocol's `ProtocolResponseMapper#encode`. No `tasks/list_changed`: neither MCP 2025-11-25 (`tasks` cap = `list`/`cancel`/`requests`) nor the 2026-07-28 tasks extension (no `tasks/list`) defines it; `TaskPushRoutingTest#taskSetChangesReachNoSessionButTheOwner`.

History: beta.30 inferred the route from the `OutboundSseStreamMessageRouter` ThreadLocal and broadcast unrouted status to every session; beta.31 (#393) made the session an owner with `visibleTo` gates. Both replaced by explicit route + connector access.

### ❓ Why the route is not in `TaskSnapshot`

Route = server-local delivery state in `TaskEntry` (set once by `TaskEngine#publish(TaskSnapshot, TaskRoute)`); `TaskSnapshot` = the external system's view, authored by connector/engine.
- `Mcp-Session-Id` is session state: snapshots flow to connectors, external stores and logs.
- Connector-authored `publish` would have to carry the route ⇒ either it can re-route (breaks set-once) or the field is ignored (misleading).
- Snapshots map straight to wire types (`McpTaskMapper`): one missed mapper leaks a session id.
- 2026-07-28 has no sessions; the spec binds tasks to the authorization context, not a transport session. A future principal owner is a separate `TaskEntry` field, not the route.

Legacy sticky `cancelled` (2025-11-25 § Task Cancellation 2–3: status ≠ execution) also belongs to the connector: `get` should report `cancelled` once `cancel` is accepted; Tachyon never pins it in the cache (not durable, overwritten by higher revisions) and only waits `TaskEngine#cancelAndAwait`.

Durable access control (eviction, restart, multi-node) belongs to the connector: every `Task*Fn` gets `InteractionContext`; the engine stores its own owner key and authorizes `get`/`cancel`/`update`/`list`.

## 🌐 Method map

`TaskMethodHandlers.register` `TaskMethodHandlers#register`

| Method | Gate | Connector | Result |
|---|---|---|---|
| `tasks/list` | legacy only | `list` (null ⇒ method not found) | read only: connector scopes by caller (`TaskListFn#apply`), `withDefaults`; never cached, no notification |
| `tasks/get` | modern: extension declared | `get`; `TaskNotFoundException` ⇒ invalid params | `getTaskResult` |
| `tasks/cancel` | modern: extension | modern: `cancel` (fire-and-forget); legacy: `TaskEngine#cancelAndAwait` = `get` (terminal ⇒ -32602, no cancel) ⇒ `cancel` ⇒ poll `get` like `tasks/result` | modern empty / legacy `cancelled` snapshot; other terminal ⇒ -32602 "Cannot cancel task: already in terminal status", ttl ⇒ -32602 expired; no time bound (a never-settling cancel waits until `responseUndeliverable`) |
| `tasks/result` | legacy only | `awaitResult` (blocking); unset ⇒ `get` until terminal, sleeping snapshot `pollInterval` else `resultPollInterval` (1s), each poll published; ttl elapsed ⇒ -32602 "Task has expired" via `TaskAwaitException`; no time bound (spec: MUST block until terminal); `InteractionContext#responseUndeliverable` ⇒ stops polling, `CancellationException` ⇒ `Cancelled` outcome, task untouched `TaskEngine#awaitResult` | tool call payload, or the task's JSON-RPC error |
| `tasks/update` | modern only + extension | `update(inputResponses)` | empty |

Gates thrown from `decode` as `RequestMappingException` `RequestMappingException`.

Wait release, never task cancel: disconnect on a live session keeps waiting (resumable via `Last-Event-ID`); session end or, without a session, connection close completes `responseUndeliverable` and the poll loop returns; `notifications/cancelled` interrupts. Only `tasks/cancel` stops a task. Scenarios: `LegacyResultWaitE2eTest`.

## 🔌 Temporal integration

`TemporalTaskExecutionEngine` → `connector()`; MCP task id = Workflow id; routes (`TemporalTaskRoute`) define workflow type, start args, status query, snapshot mapper, input update `TemporalTaskExecutionEngine`. Test double: `tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/TestTaskConnector.java`. See [[integrations]].

Related: [[feature-registries]], [[extensions]], [[protocol-versions]].
