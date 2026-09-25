---
title: "Tasks"
weight: 20
sidebar_order: 20
toc: true
description: |-
  Long-running operations in Tachyon: the tasks/* lifecycle, enforced state machine, status notifications, and TasksExtension (SEP-1686).
---

Tachyon exposes external work as MCP tasks. The application, workflow engine, or job system owns
execution. Tachyon owns protocol mapping, a small snapshot cache, and notifications.

Tools, resources, prompts, and completions can be declared with [annotations](../annotations.md).
Task configuration uses a `TaskConnector` and an explicit tool descriptor: `@McpTool` exposes name and
description, but has no `taskSupport` setting. Use the programmatic task-capable tool below alongside
your annotated services.

## Configure a task connector

Build a `TaskConnector` (package `dev.tachyonmcp.api.server.features.tasks`) from the three operations in the modern Tasks extension. Lookup, cooperative
cancellation, and input submission are one required contract. Only the two legacy operations are
optional:

```java
var tasks = TaskConnector.builder()
        .get((ctx, request) -> workflows.snapshot(request.taskId()))
        .cancel((ctx, request) -> workflows.cancel(request.taskId()))
        .update((ctx, request) -> workflows.submitInput(request.taskId(), request.inputResponses()))
        .build();

var server = TachyonServer.builder()
        .capabilities(c -> c.tasks(tasks))
        .port(8080)
        .build();
```

Tasks are off by default. There is no built-in in-process engine. Enabling tasks without a connector
fails during server configuration. Declaring
`.tasks(tasks)` also registers the `io.modelcontextprotocol/tasks` wire extension automatically —
there's no separate `.withExtensions(...)` call to make. Legacy compatibility operations are:

| Builder method | MCP method |
|---|---|
| `.list(...)` | legacy `tasks/list` |
| `.awaitResult(...)` | legacy blocking `tasks/result` |

`.list(...)` and `.awaitResult(...)` support the pre-SEP-2663 (2025-11-25) wire only.

## Return a task from a tool

Mark the tool as task-capable. Its handler starts external work once and returns the initial
immutable projection:

```java
server.tools().register(
        tool -> tool.name("book_appointment").taskSupport(TaskSupport.REQUIRED),
        (context, request) -> {
            var workflowId = workflows.start(request.arguments());
            return ToolResult.task(
                    TaskSnapshot.working(workflowId, clock.instant(), 1));
        });
```

Use the external system's stable, safe identifier as `taskId`. For Temporal, use Workflow ID rather than Run ID so Continue-As-New keeps one logical MCP task.

The flow is:

1. The handler starts external work.
2. It returns `ToolResult.task(initialSnapshot)`.
3. Tachyon publishes that projection and maps the task response.
4. `tasks/get` calls the connector's `get(...)` and publishes the authoritative returned snapshot.
5. `tasks/update` forwards a `TaskUpdateRequest` to the connector's `update(...)`.
6. `tasks/cancel` calls the connector's `cancel(...)` and acknowledges the accepted request immediately.
7. A later `tasks/get` calls `get(...)` again to observe the authoritative state. Cancellation may
   still be pending or may settle in another terminal state.

Tachyon never runs the handler in the background and never invokes it again for `tasks/update`.

The handler must durably create the external task before returning `ToolResult.task(...)`. Tachyon
then caches the projection before sending the tool response. A subsequent `tasks/get` does not rely
on that cache: it asks the connector for authoritative state.

## Publish snapshots

Push updates through the public `Tasks` façade when the external system sends a callback:

```java
server.tasks().publish(TaskSnapshot.builder()
        .taskId(workflowId)
        .status(TaskState.WORKING)
        .statusMessage("Charging card")
        .createdAt(createdAt)
        .lastUpdatedAt(clock.instant())
        .revision(4)
        .build());
```

`Tasks` contains only projection operations, plus one ephemeral notification:

```java
TaskSnapshot publish(TaskSnapshot snapshot);
@Nullable TaskSnapshot get(String taskId);
boolean remove(String taskId);
void reportProgress(String taskId, double progress, @Nullable Double total, @Nullable String message);
```

A task-augmented `tools/call` that returns `ToolResult.task(...)` creates the task. Its session and
progress token become the task's owner and progress target, fixed for the task's lifetime. Session
notifications (`notifications/tasks/status`, and `notifications/progress` from `reportProgress`) go
to that session only. `publish` updates a task and never changes its owner, whichever thread or
callback calls it. A task id the tool returns that another session already owns fails the call.
Tachyon never generates task ids: the tool or the task execution engine does, and owns their
uniqueness and unpredictability.

Session-owned tasks never emit `notifications/tasks` to sessionless `subscriptions/listen`
streams, even when a subscriber knows the task id or the owning session has ended. Ownerless tasks
continue to notify streams that subscribed to their task ids. This routing preserves session
isolation; it does not provide user authentication or per-user authorization.

`publish` never creates a task. A snapshot for a task Tachyon has not cached, e.g. a connector
callback that runs before the tool returns or on another node, reaches `subscriptions/listen`
subscribers only and is not cached; pull through `tasks/get` stays authoritative.

Only the owner reaches its task. On a stateful server, `tasks/get`, `tasks/cancel`, `tasks/result`
and `tasks/update` for a task another session owns fail with the same `Task not found` error as an
unknown id, before Tachyon calls the connector, and `tasks/list` leaves such tasks out. A client that
reconnects with a new session therefore loses access to its earlier tasks. Tachyon checks only tasks
it has cached with an owner: ownerless tasks and ids it has not seen go to the connector, which
receives the `InteractionContext` and must authorize them itself. Tachyon never broadcasts a task to
other sessions.

Each snapshot carries a monotonically increasing `revision`. Tachyon ignores duplicate or older
revisions. Push improves notification latency, but pull remains authoritative: `tasks/get` always
calls the connector.

`ttl` is measured from `createdAt`, not from when a task goes terminal: it's the point at which the
receiver may delete the task and its result, regardless of status. Tachyon evicts a cached task once
its `ttl` has passed, whatever its status. It's a different setting from the server's own `keepAlive`
cache-retention window (below), which evicts terminal snapshots only.

Terminal snapshots may carry `TaskResult`:

```java
var completed = TaskSnapshot.builder()
        .from(previous)
        .status(TaskState.COMPLETED)
        .result(TaskResult.completed(Map.of("bookingId", bookingId)))
        .lastUpdatedAt(clock.instant())
        .revision(previous.revision() + 1)
        .build();
```

The cache janitor removes tasks past their `ttl` and terminal tasks past `keepAlive`. It never cancels
or mutates external work. An evicted task loses its owner check: later `tasks/*` calls for its id go
to the connector, which must authorize them.

## Report progress

`reportProgress` emits `notifications/progress` addressed to the progress token of the
task-augmented tool call that created the task — it is not part of `TaskSnapshot` and carries no
revision:

```java
server.tasks().reportProgress(workflowId, 40.0, 100.0, "Charging card");
```

When that call carried no progress token, or the task is not cached, `reportProgress` is a no-op,
logged at debug.

## Kotlin

Kotlin uses the same Java connector:

```kotlin
capabilities {
    tasks(taskConnector) {
        pollInterval = 1.seconds
    }
}
```

Tool handlers return the same `ToolResult.task(TaskSnapshot)` branch.

## Temporal

Use `tachyon-tasks-temporal` when [Temporal](https://temporal.io) owns execution. The adapter exposes a concrete `start`
helper because Temporal has a known start contract; that helper is deliberately not part of the
generic `TaskConnector` SPI. See [the Temporal example](https://github.com/tachyonmcp/tachyon/tree/main/examples/temporal).
