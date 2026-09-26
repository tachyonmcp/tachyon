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

`publish` caches a task Tachyon has not seen and otherwise applies newer revisions. It never
starts work.

### Where notifications go

A task-augmented `tools/call` that returns `ToolResult.task(...)` gives the task a route: that
call's session and progress token. The route is fixed once set.
- `notifications/tasks/status` goes to that session, and `notifications/progress` from
  `reportProgress` to that token.
- A task published before the tool call returns, e.g. by a job that reports `working` early, takes
  the route when the call returns.
- A second tool call that returns the same id succeeds but never takes the route.
- `publish` never picks a session from the calling thread.

Every `subscriptions/listen` stream that names a task id also receives that task's
`notifications/tasks`, routed or not, as long as the connector let the stream read it. When the
stream opens, Tachyon calls the connector's `get` with the listener's `InteractionContext` for each
id it names. An id the connector refuses or fails on is left out, and the acknowledgment lists only
the ids that stay. The check runs once per stream, not per notification. Status is never broadcast
to other sessions.

### Who can reach a task

Tachyon does not decide access. `tasks/get`, `tasks/cancel`, `tasks/result`, `tasks/update` and
`tasks/list` always call the connector, which receives the caller's `InteractionContext` and
must authorize the call. So does every task id a `subscriptions/listen` stream names, through
`get`. Tachyon has no authentication, so the MCP spec's rule for servers without
an authorization context applies: the task id is a bearer capability. Generate it unguessable, e.g.
a random UUID. Tachyon never generates task ids: the tool or the task execution engine owns their
uniqueness and unpredictability.

To keep a task private to the session that started it, record the session with the job and check
it in the connector. Answer a task the caller may not see like an unknown id:

```java
.get((ctx, request) -> {
    var job = jobs.find(request.taskId());
    if (job == null || !Objects.equals(job.sessionId(), ctx.sessionId())) {
        throw new TaskNotFoundException(request.taskId());
    }
    return job.snapshot();
})
```

Apply the same check in `cancel`, `update` and `awaitResult`, and filter `list`. The `get` check also
keeps other clients' `subscriptions/listen` streams away from the task. A client that reconnects with
a new session then loses access to its earlier tasks.

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
or mutates external work. An evicted task loses its route: a later publish caches it unrouted, so its
session no longer receives its status. Listeners are unaffected: the connector authorized them when
their streams opened.

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
