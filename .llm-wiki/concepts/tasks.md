---
title: Tasks
tags: [concept, tasks, experimental]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/TasksConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, integrations/tachyon-tasks-temporal/]
updated: 2026-09-25
commit: bb583cea
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

Tool returns `ToolResult.task(snapshot)` ⇒ checks (not FORBIDDEN, legacy must be augmented, connector configured, extension declared on modern) ⇒ `tasksRegistry.create(snapshot, context.sessionId(), progressToken)` (explicit: async tools map off the dispatch thread) ⇒ `createTaskResult`, observation outcome `TaskHandoff`. Non-task result for REQUIRED/augmented ⇒ internal error.

## 🗂️ Registry semantics

Only `TaskRegistry#create` caches a task; `publish` only updates.
- effective pollInterval = snapshot's or config default `DefaultTaskRegistry#withDefaults`.
- `create(snapshot, sessionId, progressToken)`: `putIfAbsent` a `TaskEntry` whose owner session + progress token are `final` (never change, no claim). Existing entry: same owner (`TaskEntry#ownedBy`, `null` = ownerless) ⇒ idempotent, publishes the revision; else ⇒ `null`, owner's snapshot untouched, tool call gets internal error `DefaultTaskRegistry#create`.
- `publish(snapshot)`: cached ⇒ `TaskEntry#publish` accepts only **higher revision**; changed ⇒ `server.notifyTaskStatus(snapshot, ownerSessionId)` + `ChangeSupport.fireOnChange` (listeners append, like the other registries) `DefaultTaskRegistry#onChange`. Uncached ⇒ `notifyTaskStatus(snapshot, null)` (listen subscribers only, e.g. task created on another node) and **not cached**. So `tasks/get|cancel|result` never cache, and an early connector callback before the tool returns is pushed, not cached.
- No `InteractionContext#tasks()`: owner comes only from the task-augmented `tools/call`, never from a thread or facade.
- `reportProgress(taskId, …)` needs the creating call's progressToken, else dropped `DefaultTaskRegistry#reportProgress`.
- Access: `DefaultTaskRegistry#visibleTo` hides a task owned by another session; `tasks/get|cancel|result|update` answer it like an unknown id (`TaskMethodHandlers#taskNotFound`) before calling the connector, `tasks/list` filters it out. Ownerless and uncached ids go to the connector.
- Threading: `TaskEntry` lock guards publish + notify. `TaskEntry#notifyIfNewer` gates on `notifiedRevision`, so a publisher that lost the race never sends a stale status after a newer one. Sends run under the lock, so owner status uses `offerEvent` (slow client ⇒ its stream closes, publishers never park) `DefaultTachyonServer#notifyTaskStatus`.
- Janitor every 30s removes terminal entries older than keepAlive `TaskEntry#TaskEntry`, `TaskEntry`, `TaskEntry#isResultExpired`.

Notification fan-out `DefaultTachyonServer#notifyTaskStatus`: owner session only gets `notifications/tasks/status` (no owner ⇒ none, never broadcast; same for `notifyTaskProgress`) in its protocol's shape; plus `SubscriptionRegistry.notifyTaskStatus` → `notifications/tasks` to `subscriptions/listen` streams filtering on taskId.

## 🌐 Method map

`TaskMethodHandlers.register` `TaskMethodHandlers#register`

| Method | Gate | Connector | Result |
|---|---|---|---|
| `tasks/list` | legacy only | `list` (null ⇒ method not found) | read only: connector scopes by caller (`TaskListFn#apply`), `visibleTo` guard, `withDefaults`; never cached, no notification |
| `tasks/get` | modern: extension declared | `get`; `TaskNotFoundException` ⇒ invalid params | `getTaskResult` |
| `tasks/cancel` | modern: extension | `cancel` then (legacy) `get` | modern empty / legacy snapshot |
| `tasks/result` | legacy only | `awaitResult` (blocking) | tool call payload |
| `tasks/update` | modern only + extension | `update(inputResponses)` | empty |

Gates thrown from `decode` as `RequestMappingException` `RequestMappingException`.

## 🔌 Temporal integration

`TemporalTaskExecutionEngine` → `connector()`; MCP task id = Workflow id; routes (`TemporalTaskRoute`) define workflow type, start args, status query, snapshot mapper, input update `TemporalTaskExecutionEngine`. Test double: `tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/TestTaskConnector.java`. See [[integrations]].

Related: [[feature-registries]], [[extensions]], [[protocol-versions]].
