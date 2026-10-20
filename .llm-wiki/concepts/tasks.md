---
title: Tasks
tags: [concept, tasks, experimental]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/DefaultToolRegistry.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/, integrations/tachyon-tasks-temporal/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/Owner.java]
updated: 2026-10-20
commit: d263b96a
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
| `TaskRuntime` (core seam, `@InternalApi`) | what tool/subscription handlers need: `publish(DispatchContext, TaskSnapshot, ProgressToken)` hands a tool's task over with its caller; `NONE` when no extension | `TaskRuntime` |
| `Owner` (core, `@InternalApi`) | who owns a task or a session: anonymous or `Named`; `equals` = same caller, `admits` = may reach what it owns | [Owner](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/security/Owner.java) |
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

Tool returns `ToolResult.task(snapshot)` ⇒ checks (not FORBIDDEN, legacy must be augmented, extension declared on modern) ⇒ `taskRuntime().publish(context, snapshot, progressToken)` ⇒ `McpTaskBinding#publish` ⇒ `TaskEngine#publishAs(context, snapshot, McpTaskRoute.of(context.sessionId(), token))` (explicit: async tools map off the dispatch thread, and the dispatch context is the request's own fixed copy) ⇒ `createTaskResult`, observation outcome `TaskHandoff`. The caller owns the task; an owner conflict ⇒ ISE ⇒ generic `Internal error`, no `TaskHandoff`. Non-task result for REQUIRED/augmented ⇒ internal error.

## 🗂️ Registry semantics

Verdict: cache + push **routing**, plus an **owner binding** for tasks a named caller creates. A `TaskRoute` (session + progress token) is a delivery address, never an owner. A task a named caller's tool call creates belongs to that caller (`Owner.Named`, see [[authentication]]), from any session: every caller-facing path goes through the **guarded connector** (`TaskEngine#connector`), which answers anyone else `TaskNotFoundException` before the real connector runs, and leaves foreign tasks out of `list`. Unowned tasks (anonymous creator, or `TachyonServer#tasks().publish`) are left to the connector, which authorizes via `InteractionContext`; without an authorization context the task id is a bearer capability (2025-11-25 Tasks § Security).

- **Two publish intents, no nullable owner.** `TaskEngine#publish(snapshot)` (the `Tasks` façade) has no caller: it updates any task and claims none, and creates unowned ones. `TaskEngine#publishAs(ctx, snapshot, route)` (tool handoff) derives the `Owner` from `ctx`. `TaskEngine#refresh(snapshot)` (a task call that read the connector's snapshot) updates only an entry the engine holds and caches nothing for an unknown id, so a foreign or early read never leaves an id unowned for its creator to collide with. An owner never changes: a different owner, an anonymous caller on an owned task, and a named caller on an already cached unowned task are refused (`IllegalStateException`, WARN with a truncated id) **before** anything is touched, so a refused claimant's route never attaches and nothing is pushed. Arbitration is one atomic `owners.compute` per task id `TaskEngine#bind`.
- **Binding outlives the snapshot, up to the task's `ttl`.** `TaskEngine#owners` holds `Binding(owner, until)`: `until = null` while the entry is cached; when the janitor evicts the snapshot (`TaskEngine#evict`) a task with a `ttl` keeps its binding until `createdAt + ttl`, a task without one drops it with the snapshot. The owner is bound in the same step as the entry, and removed in `remove`. In-memory only ⇒ another node or a restart sees the task unowned.
- **One gate.** `TaskEngine#guard` wraps `get`/`cancel`/`update`/`awaitResult` (`requireVisibleTo` ⇒ `TaskNotFoundException`) and `list` (filters by `isVisibleTo`); the engine's own waits (`awaitResult`, `cancelAndAwait`, `pollUntilTerminal`) and `readableTaskIds` use `connector()`, not the raw one. `TaskMethodHandlers` has no ownership code. `listCached` was removed: it would have bypassed the gate.
- ⚠️ Limits: process-local; no `ttl` ⇒ bound only while cached (terminal + `keepAlive`, 5 min); `list` is filtered after the connector's page, so a page can come back short with a valid cursor. `InteractionContext#tasks()` (spec "new") is not built, so a worker that publishes before its tool returns publishes unowned, and the tool's later owned create is refused.

- effective pollInterval = snapshot's or config default `TaskEngine#withDefaults`.
- `publish(snapshot)` = `publish(snapshot, TaskRoute.NONE)`: upsert. Uncached ⇒ cached unrouted; cached ⇒ `TaskEntry#publish` accepts only **higher revision**; changed ⇒ `TaskEntry#notifyIfNewer` `TaskEngine#publish`. No cache-change callback: publish/remove/eviction push nothing beyond per-task status.
- `publish(snapshot, route)` (tool path): `putIfAbsent`; existing entry takes `route` only if unrouted (`TaskEntry#route(TaskRoute)`: set once, never re-routed, colliding call succeeds) `TaskEngine#publish`. Route attach itself pushes nothing: the tool result carries that revision; revisions sent before attach are not re-sent.
- `tasks/get|cancel|result` `TaskEngine#refresh` the connector's snapshot ⇒ a cached task is updated, an unknown id is never cached; a read never routes. `tasks/list` never caches.
- Route source: only the task-augmented `tools/call`, never a thread, facade or read. No `InteractionContext#tasks()`.
- Task ids come from the tool or engine, never Tachyon; unguessability is their job (spec MUST without context binding).
- `reportProgress(taskId, …)` fires `TaskEvents#onProgress`; the MCP binding drops it without an `McpTaskRoute` progressToken `McpTaskBinding#onProgress`.
- Threading: `TaskEntry` lock guards publish + route + notify. `TaskEntry#notifyIfNewer` gates on `notifiedRevision`, so a publisher that lost the race never sends a stale status. Sends run under the lock, so session status uses `offerEvent` (slow client ⇒ its stream closes, publishers never park) `DefaultTachyonServer#notifyTaskStatus`.
- Janitor every 30s removes entries past `createdAt + ttl` (any status, any route) or terminal and cached longer than keepAlive `TaskEntry#isExpired`, `TaskEngine#runJanitorSweep`. Eviction drops the route: later status reaches listeners only.

Notification routing [McpTaskBinding#onStatus](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/McpTaskBinding.java) ⇒ [DefaultTachyonServer#notifyTaskStatus](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java): routed ⇒ that session gets `notifications/tasks/status` (gone ⇒ nothing, no fallback). The binding also calls [SubscriptionRegistry#publish](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/subscriptions/SubscriptionRegistry.java) for `notifications/tasks` on streams naming the task id. The tasks extension owns the `taskIds` topic: [McpTaskBinding#taskIdsTopic](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/McpTaskBinding.java) requires client declaration and narrows ids via [TaskEngine#readableTaskIds](../../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/engine/TaskEngine.java) (connector `get` per id with listener context, fail closed). The filter value is a `TaskListen(listener Owner, taskIds)`, the listener taken from the listen request's `SecurityContext`. The ack echoes only honored ids. No tasks extension ⇒ `taskIds` is unknown and ignored. The connector is asked once per stream; every pushed status is rechecked with `TaskEngine#admits(listener, taskId)`, an in-memory owner lookup, so a stream that named an id before the task existed stops following it once someone else owns it. Eviction/route loss changes nothing for listeners. Never broadcast. [McpResponseMapper#subscriptionNotificationParams](../../tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/mcp/v2026_07_28/codecs/McpResponseMapper.java) merges the stream's `_meta.io.modelcontextprotocol/subscriptionId` over snapshot metadata (server key wins); session `notifications/tasks/status` stays on `taskStatusNotificationParams` without it. Session payloads use the session protocol's `ProtocolResponseMapper#encode`. No `tasks/list_changed`: neither MCP 2025-11-25 (`tasks` cap = `list`/`cancel`/`requests`) nor the 2026-07-28 tasks extension (no `tasks/list`) defines it; `TaskPushRoutingTest#taskSetChangesReachNoSessionButTheOwner`.

History: beta.30 inferred the route from the `OutboundSseStreamMessageRouter` ThreadLocal and broadcast unrouted status to every session; beta.31 (#393) made the session an owner with `visibleTo` gates. Both replaced by explicit route + connector access.

### ❓ Why the route is not in `TaskSnapshot`

Route = server-local delivery state in `TaskEntry` (set once by `TaskEngine#publish(TaskSnapshot, TaskRoute)`); `TaskSnapshot` = the external system's view, authored by connector/engine.
- `Mcp-Session-Id` is session state: snapshots flow to connectors, external stores and logs.
- Connector-authored `publish` would have to carry the route ⇒ either it can re-route (breaks set-once) or the field is ignored (misleading).
- Snapshots map straight to wire types (`McpTaskMapper`): one missed mapper leaks a session id.
- 2026-07-28 has no sessions; the spec binds tasks to the authorization context, not a transport session. The principal owner is `TaskEngine#owners`, a separate map next to the entries: not the route, not the snapshot.

Legacy sticky `cancelled` (2025-11-25 § Task Cancellation 2–3: status ≠ execution) also belongs to the connector: `get` should report `cancelled` once `cancel` is accepted; Tachyon never pins it in the cache (not durable, overwritten by higher revisions) and only waits `TaskEngine#cancelAndAwait`.

Durable access control (restart, multi-node, a task with no `ttl` after its snapshot left the cache) belongs to the connector: every `Task*Fn` gets `InteractionContext`, and `ctx.securityContext().principal().getName()` is the owner a connector should store and check. The engine's own binding is the in-memory first line; unowned tasks are left to the connector entirely. This is a deployment requirement of the 2025-11-25 "bind tasks to said context" MUST, stated in `docs/extensions/tasks.md` and `docs/running/authentication.md`; every path hands the connector the request's own context (e2e `theConnectorReceivesTheCallerOfEachRequestOnEveryTaskPath`, both versions).

## 🌐 Method map

`TaskMethodHandlers.register` `TaskMethodHandlers#register`

| Method | Gate | Connector | Result |
|---|---|---|---|
| `tasks/list` | legacy only | `list` (null ⇒ method not found) | read only: connector scopes by caller (`TaskListFn#apply`), `withDefaults`; never cached, no notification |
| `tasks/get` | modern: extension declared | `get`; `TaskNotFoundException` ⇒ invalid params | `getTaskResult` |
| `tasks/cancel` | modern: extension | modern: `cancel` (fire-and-forget); legacy: `TaskEngine#cancelAndAwait` = `get` (terminal ⇒ -32602, no cancel) ⇒ `cancel` ⇒ poll `get` like `tasks/result` | modern empty / legacy `cancelled` snapshot; other terminal ⇒ -32602 "Cannot cancel task: already in terminal status", ttl ⇒ -32602 expired; no time bound (a never-settling cancel waits until `responseUndeliverable`) |
| `tasks/result` | legacy only | `awaitResult` (blocking); unset ⇒ `get` until terminal, sleeping snapshot `pollInterval` else `resultPollInterval` (1s), each poll refreshing the cache; ttl elapsed ⇒ -32602 "Task has expired" via `TaskAwaitException`; no time bound (spec: MUST block until terminal); `InteractionContext#responseUndeliverable` ⇒ stops polling, `CancellationException` ⇒ `Cancelled` outcome, task untouched `TaskEngine#awaitResult` | tool call payload (`Completed` payloads first pass `JsonUtils#serializeStructured` with `ServerEngine#payloadSerializer`, so the configured serde applies like in `tools/call` `TaskMethodHandlers.TasksResultHandler#handle`), or the task's JSON-RPC error |
| `tasks/update` | modern only + extension | `update(inputResponses)` | empty |

Gates thrown from `decode` as `RequestMappingException` `RequestMappingException`.

Wait release, never task cancel: disconnect on a live session keeps waiting (resumable via `Last-Event-ID`); session end or, without a session, connection close completes `responseUndeliverable` and the poll loop returns; `notifications/cancelled` interrupts. Only `tasks/cancel` stops a task. Scenarios: `LegacyResultWaitE2eTest`.

## 🔌 Temporal integration

`TemporalTaskExecutionEngine` → `connector()`; MCP task id = Workflow id; routes (`TemporalTaskRoute`) define workflow type, start args, status query, snapshot mapper, input update `TemporalTaskExecutionEngine`. Test double: `tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/TestTaskConnector.java`. See [[integrations]].

Related: [[feature-registries]], [[extensions]], [[protocol-versions]].
