---
title: Tasks
tags: [concept, tasks, experimental]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/TasksConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java, integrations/tachyon-tasks-temporal/]
updated: 2026-09-25
commit: cbfbcd7f
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
| `Tasks` (façade) | `publish`, `get`, `remove`, `reportProgress` | `Tasks` |
| `DefaultTaskRegistry` | cache + notifications + TTL janitor | `DefaultTaskRegistry` |
| `TasksExtension` | id `io.modelcontextprotocol/tasks`, `ALWAYS` advertised, per-request gate | `TasksExtension` |

## ⚙️ Config

`capabilities { tasks(connector) }` ⇒ enabled + connector `Builder#tasks`; enabled w/o connector ⇒ ISE `Builder#validateTaskConnector`. `TasksConfig` keepAlive default **5 min**, optional pollInterval, pageSize `TasksConfig`. Builder auto-adds `TasksExtension` when enabled (see [[overview]]).

Startup check: any `REQUIRED` tool w/o connector ⇒ `IllegalStateException("Task-producing tools require a TaskConnector")`; `OPTIONAL` w/o connector ⇒ warn `DefaultTachyonServer#validateConfiguration`.

## 🔁 Task-producing tool call

`ToolMethodHandlers` `validateTaskRequest` `ToolsCallHandler#validateTaskRequest` + `mapResult` `ToolsCallHandler#mapResult`:

| Protocol | Rule |
|---|---|
| 2025-11-25 (legacy augmentation) | client sends task-augmented call; `FORBIDDEN`+augmented ⇒ invalid params; `REQUIRED`+plain ⇒ invalid params |
| 2026-07-28 | no augmentation flag; `REQUIRED` ⇒ tasks extension must be declared (else -32021) |

Tool returns `ToolResult.task(snapshot)` ⇒ checks (not FORBIDDEN, legacy must be augmented, connector configured, extension declared on modern) ⇒ `tasksRegistry.publish(snapshot, new TaskRoute(context.sessionId(), progressToken))` (explicit: async tools map off the dispatch thread); never refused ⇒ `createTaskResult`, observation outcome `TaskHandoff`. Non-task result for REQUIRED/augmented ⇒ internal error.

## 🗂️ Registry semantics

Verdict: cache + push **routing**, no access control. A `TaskRoute` (session + progress token) is a delivery address, never an owner; the connector authorizes every `tasks/*` call. Without an authorization context the task id is a bearer capability (2025-11-25 Tasks § Security).

- effective pollInterval = snapshot's or config default `DefaultTaskRegistry#withDefaults`.
- `publish(snapshot)` = `publish(snapshot, TaskRoute.NONE)`: upsert. Uncached ⇒ cached unrouted; cached ⇒ `TaskEntry#publish` accepts only **higher revision**; changed ⇒ `TaskEntry#notifyIfNewer` + `ChangeSupport.fireOnChange` `DefaultTaskRegistry#publish`, `DefaultTaskRegistry#onChange`.
- `publish(snapshot, route)` (tool path): `putIfAbsent`; existing entry takes `route` only if unrouted (`TaskEntry#route(TaskRoute)`: set once, never re-routed, colliding call succeeds) `DefaultTaskRegistry#publish`. Route attach itself pushes nothing: the tool result carries that revision; revisions sent before attach are not re-sent.
- `tasks/get|cancel|result` publish the connector's snapshot ⇒ cached unrouted; a read never routes. `tasks/list` never caches.
- Route source: only the task-augmented `tools/call`, never a thread, facade or read. No `InteractionContext#tasks()`.
- Task ids come from the tool or engine, never Tachyon; unguessability is their job (spec MUST without context binding).
- `reportProgress(taskId, …)` needs the route's progressToken, else dropped `DefaultTaskRegistry#reportProgress`.
- Threading: `TaskEntry` lock guards publish + route + notify. `TaskEntry#notifyIfNewer` gates on `notifiedRevision`, so a publisher that lost the race never sends a stale status. Sends run under the lock, so session status uses `offerEvent` (slow client ⇒ its stream closes, publishers never park) `DefaultTachyonServer#notifyTaskStatus`.
- Janitor every 30s removes entries past `createdAt + ttl` (any status, any route) or terminal and cached longer than keepAlive `TaskEntry#isExpired`, `DefaultTaskRegistry#runJanitorSweep`. Eviction drops the route: later status reaches listeners only.

Notification routing [DefaultTachyonServer#notifyTaskStatus](../../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java): routed ⇒ that session gets `notifications/tasks/status` (gone ⇒ nothing, no fallback); always also `SubscriptionRegistry#notifyTaskStatus` → `notifications/tasks` on `subscriptions/listen` streams naming the taskId. Listener ids are pre-authorized: `SubscriptionsListenHandler#handleAsync` narrows the filter to `TaskRegistry#readableTaskIds` (connector `get` per id with the listener's ctx, fail closed, nothing cached) before `SubscriptionRegistry#activate`, so the ack echoes only honored ids (2026-07-28 subscriptions: ack = honored subset). Eviction/route loss changes nothing for listeners. Checked once per stream, not per event. Never broadcast. Session payloads use the session protocol's `ProtocolResponseMapper#encode`.

History: beta.30 inferred the route from the `OutboundSseStreamMessageRouter` ThreadLocal and broadcast unrouted status to every session; beta.31 (#393) made the session an owner with `visibleTo` gates. Both replaced by explicit route + connector access.

### ❓ Why the route is not in `TaskSnapshot`

Route = server-local delivery state in `TaskEntry` (set once by `TaskRegistry#publish(TaskSnapshot, TaskRoute)`); `TaskSnapshot` = the external system's view, authored by connector/engine.
- `Mcp-Session-Id` is session state: snapshots flow to connectors, external stores and logs.
- Connector-authored `publish` would have to carry the route ⇒ either it can re-route (breaks set-once) or the field is ignored (misleading).
- Snapshots map straight to wire types (`McpTaskMapper`): one missed mapper leaks a session id.
- 2026-07-28 has no sessions; the spec binds tasks to the authorization context, not a transport session. A future principal owner is a separate `TaskEntry` field, not the route.

Durable access control (eviction, restart, multi-node) belongs to the connector: every `Task*Fn` gets `InteractionContext`; the engine stores its own owner key and authorizes `get`/`cancel`/`update`/`list`.

## 🌐 Method map

`TaskMethodHandlers.register` `TaskMethodHandlers#register`

| Method | Gate | Connector | Result |
|---|---|---|---|
| `tasks/list` | legacy only | `list` (null ⇒ method not found) | read only: connector scopes by caller (`TaskListFn#apply`), `withDefaults`; never cached, no notification |
| `tasks/get` | modern: extension declared | `get`; `TaskNotFoundException` ⇒ invalid params | `getTaskResult` |
| `tasks/cancel` | modern: extension | `cancel` then (legacy) `get` | modern empty / legacy snapshot |
| `tasks/result` | legacy only | `awaitResult` (blocking) | tool call payload |
| `tasks/update` | modern only + extension | `update(inputResponses)` | empty |

Gates thrown from `decode` as `RequestMappingException` `RequestMappingException`.

## 🔌 Temporal integration

`TemporalTaskExecutionEngine` → `connector()`; MCP task id = Workflow id; routes (`TemporalTaskRoute`) define workflow type, start args, status query, snapshot mapper, input update `TemporalTaskExecutionEngine`. Test double: `tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/TestTaskConnector.java`. See [[integrations]].

Related: [[feature-registries]], [[extensions]], [[protocol-versions]].
