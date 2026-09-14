---
title: Tasks
tags: [concept, tasks, experimental]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/TasksConfig.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tools/ToolMethodHandlers.java, integrations/tachyon-tasks-temporal/]
updated: 2026-09-14
commit: 5bee50aa
---

# ⏳ Tasks

Verdict: Tachyon does **not** run tasks. External system owns execution via `TaskConnector` functions; Tachyon keeps a revision-ordered projection cache (`TaskSnapshot`), maps MCP task methods to connector calls, and pushes status/progress notifications. All `@ExperimentalApi`.

## 🧱 Types

| Type | Role | Proof |
|---|---|---|
| `TaskConnector` | required `get`, `cancel`, `update`; `@LegacyApi` optional `list`, `awaitResult` | `tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/TaskConnector.java:22-159` |
| `TaskSnapshot` | taskId, status, timestamps, `revision`, pollInterval, result/error, meta; factories `working/inputRequired/completed/failed/cancelled` | `.../tasks/TaskSnapshot.java:20-202` |
| `TaskState` | `SUBMITTED, REJECTED*, AUTH_REQUIRED, WORKING, INPUT_REQUIRED, COMPLETED*, FAILED*, CANCELLED*, UNKNOWN*` (*terminal) | `.../tasks/TaskState.java:26-47` |
| `TaskSupport` | tool-level `FORBIDDEN / OPTIONAL / REQUIRED` | `.../tasks/TaskSupport.java:20-36` |
| `Tasks` (façade) | `publish`, `get`, `remove`, `reportProgress` | `.../tasks/Tasks.java:11-41` |
| `DefaultTaskRegistry` | cache + notifications + TTL janitor | `tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/DefaultTaskRegistry.java:25` |
| `TasksExtension` | id `io.modelcontextprotocol/tasks`, `ALWAYS` advertised, per-request gate | `.../tasks/TasksExtension.java:12-42` |

## ⚙️ Config

`capabilities { tasks(connector) }` ⇒ enabled + connector `CapabilitiesConfig.java:438-441`; enabled w/o connector ⇒ ISE `:443-447`. `TasksConfig` keepAlive default **5 min**, optional pollInterval, pageSize `TasksConfig.java:26-49`. Builder auto-adds `TasksExtension` when enabled (see [[overview]]).

Startup check: any `REQUIRED` tool w/o connector ⇒ `IllegalStateException("Task-producing tools require a TaskConnector")`; `OPTIONAL` w/o connector ⇒ warn `DefaultTachyonServer.java:273-298`.

## 🔁 Task-producing tool call

`ToolMethodHandlers` `validateTaskRequest` `:166-178` + `mapResult` `:180-205`:

| Protocol | Rule |
|---|---|
| 2025-11-25 (legacy augmentation) | client sends task-augmented call; `FORBIDDEN`+augmented ⇒ invalid params; `REQUIRED`+plain ⇒ invalid params |
| 2026-07-28 | no augmentation flag; `REQUIRED` ⇒ tasks extension must be declared (else -32021) |

Tool returns `ToolResult.task(snapshot)` ⇒ checks (not FORBIDDEN, legacy must be augmented, connector configured, extension declared on modern) ⇒ `tasksRegistry.publish(snapshot, progressToken)` ⇒ `createTaskResult`, observation outcome `TaskHandoff`. Non-task result for REQUIRED/augmented ⇒ internal error.

## 🗂️ Registry semantics

`DefaultTaskRegistry.publish` `:78-103`:
- effective pollInterval = snapshot's or config default.
- `TaskEntry.publish` accepts only **higher revision**; taskId immutable `TaskEntry.java:40-54`.
- Changed ⇒ `server.notifyTaskStatus(snapshot, ownerSessionId)` + `onChange`.
- Owner session captured from dispatch ThreadLocal at creation (`OutboundSseStreamMessageRouter.currentSessionId()`).
- `reportProgress(taskId, …)` needs progressToken captured at creation else dropped `:106-121`.
- Janitor every 30s removes terminal entries older than keepAlive `:28`, `:163-168`, `TaskEntry.java:74-78`.

Notification fan-out `DefaultTachyonServer.java:477-491`: owner session (or all ACTIVE) gets `notifications/tasks/status` in its protocol's shape; plus `SubscriptionRegistry.notifyTaskStatus` → `notifications/tasks` to `subscriptions/listen` streams filtering on taskId.

## 🌐 Method map

`TaskMethodHandlers.register` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/TaskMethodHandlers.java:25-31`

| Method | Gate | Connector | Result |
|---|---|---|---|
| `tasks/list` | legacy only | `list` (null ⇒ method not found) | list, each item re-published |
| `tasks/get` | modern: extension declared | `get`; `TaskNotFoundException` ⇒ invalid params | `getTaskResult` |
| `tasks/cancel` | modern: extension | `cancel` then (legacy) `get` | modern empty / legacy snapshot |
| `tasks/result` | legacy only | `awaitResult` (blocking) | tool call payload |
| `tasks/update` | modern only + extension | `update(inputResponses)` | empty |

Gates thrown from `decode` as `RequestMappingException` `:33-49`.

## 🔌 Temporal integration

`TemporalTaskExecutionEngine` → `connector()`; MCP task id = Workflow id; routes (`TemporalTaskRoute`) define workflow type, start args, status query, snapshot mapper, input update `integrations/tachyon-tasks-temporal/src/main/java/dev/tachyonmcp/tasks/temporal/TemporalTaskExecutionEngine.java:29-65`. Test double: `tachyon-testkit/src/main/java/dev/tachyonmcp/testkit/TestTaskConnector.java`. See [[integrations]].

Related: [[feature-registries]], [[extensions]], [[protocol-versions]].
