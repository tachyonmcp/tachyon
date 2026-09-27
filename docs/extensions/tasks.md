---
title: "Tasks"
weight: 5
sidebar_order: 5
toc: true
aliases:
  - /docs/features/tasks/
description: |-
  Long-running operations in Tachyon: the `tasks/*` lifecycle, enforced state machine, status notifications, and TasksExtension (SEP-2663; SEP-1686 for MCP 2025-11-25 clients).
---

Tachyon exposes external work as [MCP tasks](https://modelcontextprotocol.io/extensions/tasks/). The application, workflow engine, or job system owns
execution. Tachyon owns protocol mapping, a small snapshot cache, and notifications.

Tools, resources, prompts, and completions can be declared with [annotations](../annotations.md).
Task configuration uses a `TaskConnector` and an explicit tool descriptor: `@McpTool` exposes name and description, but has no `taskSupport` setting. Use the programmatic task-capable tool below alongside your annotated services.

The main flow: add the module and a connector, return a task from a tool, publish its updates,
and let clients retrieve the result. Clients on MCP 2025-11-25 get the [legacy behavior](#legacy-clients-mcp-2025-11-25) on top.

## Add the module

`TasksExtension` lives in `tachyon-extensions-tasks`; its version is pinned by the `tachyon-bom`:

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-extensions-tasks</artifactId>
</dependency>
```

## Configure a task connector

Build a `TaskConnector` (package `dev.tachyonmcp.api.server.features.tasks`) from the three operations in the modern Tasks extension. Lookup, cooperative
cancellation, and input submission are one required contract:

```java
var tasks = TaskConnector.builder()
        .get((ctx, request) -> workflows.snapshot(request.taskId()))
        .cancel((ctx, request) -> workflows.cancel(request.taskId()))
        .update((ctx, request) -> workflows.submitInput(request.taskId(), request.inputResponses()))
        .build();

var server = TachyonServer.builder()
        .withExtension(TasksExtension.class, t -> t.connector(tasks))
        .port(8080)
        .build();
```

Tasks are off until you register `TasksExtension`. There is no built-in in-process engine:
registering it without a connector fails the build, and so does a tool declaring
`TaskSupport.OPTIONAL` or `REQUIRED` without it. Registering it also advertises the
`io.modelcontextprotocol/tasks` wire extension. Two optional connector operations serve
[legacy clients](#legacy-clients-mcp-2025-11-25) only.

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
TasksExtension.tasks(server).publish(s -> s
        .taskId(workflowId)
        .status(TaskState.WORKING)
        .statusMessage("Charging card")
        .createdAt(createdAt)
        .lastUpdatedAt(clock.instant())
        .revision(4));
```

`publish(Consumer<TaskSnapshot.Builder>)` builds the snapshot and publishes it; `publish(TaskSnapshot)`
takes one you already built. An invalid snapshot throws `IllegalArgumentException` and is never
published.

`Tasks` contains only projection operations, plus one ephemeral notification:

```java
TaskSnapshot publish(TaskSnapshot snapshot);
TaskSnapshot publish(Consumer<TaskSnapshot.Builder> configurer);
@Nullable TaskSnapshot get(String taskId);
boolean remove(String taskId);
void reportProgress(String taskId, double progress, @Nullable Double total, @Nullable String message);
```

`publish` caches a task Tachyon has not seen and otherwise applies newer revisions. It never
starts work.

A tool handler reaches the same façade through its context, e.g. to hand it to the work it starts:

```java
(ctx, request) -> {
    var tasks = TasksExtension.tasks(ctx);
    var workflowId = workflows.start(request.arguments(), snapshot -> tasks.publish(snapshot));
    return ToolResult.task(TaskSnapshot.working(workflowId, clock.instant(), 1));
}
```

The façade is server-scoped, so background work may keep it after the call returns. Keep the
façade, never `ctx`: the context belongs to the request. Both `TasksExtension.tasks(server)` and
`TasksExtension.tasks(ctx)` throw `IllegalStateException` when `TasksExtension` is not registered;
a tool handler that throws it answers `-32603 Internal error`.

Terminal snapshots carry the result. Publish one when the work completes; `next(previous)` copies
the previous snapshot and bumps its revision, since `publish` ignores a revision that is not newer:

```java
TasksExtension.tasks(server).publish(s -> s.next(previous)
        .status(TaskState.COMPLETED)
        .result(TaskResult.completed(Map.of("bookingId", bookingId)))
        .lastUpdatedAt(clock.instant()));
```

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

## Retrieve the result

Clients read a task with `tasks/get`, which always calls the connector's `get(...)`: pull is
authoritative, push only improves latency. Each snapshot carries a monotonically increasing
`revision`, and Tachyon ignores duplicate or older revisions. A completed snapshot's result travels in
the `tasks/get` answer and in the `notifications/tasks` sent to listeners.

A dropped connection or an ended session never cancels a task; only `tasks/cancel` does. A client that
reconnects reads the result with `tasks/get`, as long as the connector still knows the task.

## Who can reach a task

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

## Retention

`ttl` is measured from `createdAt`, not from when a task goes terminal: it's the point at which the
receiver may delete the task and its result, regardless of status. Tachyon evicts a cached task once
its `ttl` has passed, whatever its status.

`keepAlive` is the server's own cache-retention window, and evicts terminal snapshots only (default
5 minutes; zero or negative keeps them indefinitely). `pollInterval` is the interval suggested to
requestors when a snapshot sets none:

```java
.withExtension(TasksExtension.class, t -> t
        .connector(connector)
        .keepAlive(Duration.ofMinutes(10))
        .pollInterval(Duration.ofSeconds(2)))
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
TasksExtension.tasks(server).reportProgress(workflowId, 40.0, 100.0, "Charging card");
```

When that call carried no progress token, or the task is not cached, `reportProgress` is a no-op,
logged at debug.

## Kotlin

`tachyon-kotlin` declares `tachyon-extensions-tasks` as an optional dependency, so add it yourself
to use tasks from Kotlin:

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-extensions-tasks</artifactId>
</dependency>
```

Without it, `tasks(connector) { }`, `ToolScope.tasks`, and `TachyonServer.tasks` throw
`IllegalStateException` naming the missing dependency when first used; a tool handler that touches
`tasks` answers `-32603 Internal error`. Servers that never use tasks run without the module.

Kotlin uses the same Java connector:

```kotlin
buildServer {
    tasks(taskConnector) {
        pollInterval = 1.seconds
    }
}
```

Tool handlers return the same `ToolResult.task(TaskSnapshot)` branch. The façade is a property on
the server and on the tool scope:

```kotlin
val server = TachyonServer(port = 8080) {
    tasks(taskConnector)
    tool(name = "book", taskSupport = TaskSupport.REQUIRED) {
        val workflowId = workflows.start(arguments, tasks::publish) // ToolScope.tasks
        ToolResult.task(TaskSnapshot.working(workflowId, Instant.now(), 1))
    }
}
server.tasks.publish(snapshot) // TachyonServer.tasks, e.g. from a workflow callback
```

Build snapshots with `TaskSnapshot { }`. Pass `from` to build the next revision of a previous
snapshot: its fields carry over and the revision is bumped, like Java's `next(previous)`:

```kotlin
server.tasks.publish(
    TaskSnapshot(from = previous) {
        status = TaskState.COMPLETED
        result = TaskResult.completed(ToolResult.text("Charged"))
        lastUpdatedAt = Clock.System.now()
    },
)
```

`taskId`, `status`, `createdAt`, `lastUpdatedAt`, and, without `from`, `revision` are required. Timestamps are
`kotlin.time.Instant` and durations `kotlin.time.Duration`; the factory opts in to `ExperimentalTime`
itself, so only your own `kotlin.time` calls, such as `Clock.System.now()`, need `@OptIn`.

## Legacy clients (MCP 2025-11-25)

The connector's two optional operations serve the pre-SEP-2663 (2025-11-25) wire only:

| Builder method | MCP method |
|---|---|
| `.list(...)` | legacy `tasks/list` |
| `.awaitResult(...)` | legacy blocking `tasks/result` |

Without `.list(...)`, `tasks/list` answers `-32601 Method not found`. Without `.awaitResult(...)`,
`tasks/result` still works:
Tachyon calls `get` until the task is terminal, waiting the snapshot's `pollInterval` (else
`resultPollInterval`, default 1 second) between calls, then returns its result. As the spec
requires, the wait has no time limit. It ends only when:

| Condition | `tasks/result` answer |
|---|---|
| task terminal | its result, or its JSON-RPC error |
| task `ttl` elapsed while still running (2025-11-25 § TTL) | `-32602 Failed to retrieve task: Task has expired` |
| client sent `notifications/cancelled` for the request | none (cancelled) |
| response undeliverable: session ended (`DELETE` or idle expiry) or, without a session, connection closed | none; the wait stops polling |

```java
.withExtension(TasksExtension.class, tasks -> tasks
        .connector(connector)
        .resultPollInterval(Duration.ofMillis(500)))
```

A connector `.awaitResult(...)` replaces this loop and owns its own wait, including any time limit.

### Legacy `tasks/cancel`

The connector's `cancel(...)` keeps the MCP 2026-07-28 contract on both versions: it only requests
cancellation. MCP 2025-11-25 instead requires the task to be `cancelled` before `tasks/cancel`
answers, so for those clients Tachyon waits on top of the connector:

| Step | Legacy `tasks/cancel` |
|---|---|
| `get(...)` reports a terminal task | `-32602 Cannot cancel task: already in terminal status '<status>'`; `cancel(...)` is not called |
| otherwise | `cancel(...)`, then `get(...)` until terminal, paced and bounded like `tasks/result` |
| settles `cancelled` | the `cancelled` task |
| settles `completed` or `failed` first | `-32602 Cannot cancel task: already in terminal status '<status>'` |
| task `ttl` elapses | `-32602 … Task has expired` |
| response undeliverable, as for `tasks/result` | none; the wait stops, the cancel stays requested |

The wait runs on the request's virtual thread and sleeps between polls, so it holds no platform
thread; each waiting cancel costs one `get(...)` per poll interval.

The spec rule is about the task's status, not its execution: stopping the work is a best effort, but
an accepted cancel makes the task `cancelled` for good, even if the work later completes. Tachyon does
not own that status — `get(...)` does — so keep it in the external system. Record the accepted cancel
where `get(...)` reads status and report `cancelled` from then on: the legacy response then needs one
lookup, and the answer holds across restarts and nodes. A connector that reports only execution state,
such as a Temporal workflow still processing its cancellation, is answered once it settles.

### Disconnects during a legacy wait

The task itself is never touched: a dropped connection, an ended session or a released wait never
cancels it (2025-11-25 Transports: disconnection SHOULD NOT be interpreted as cancellation). Only
`tasks/cancel` stops a task. A dropped connection on a live session is not an end either: the client
may resume the stream with `Last-Event-ID`, so the wait keeps polling. While a wait's connection is
open, its session does not idle out.

## Temporal

Use `tachyon-tasks-temporal` when [Temporal](https://temporal.io) owns execution. The adapter exposes a concrete `start`
helper because Temporal has a known start contract; that helper is deliberately not part of the
generic `TaskConnector` SPI. See [the Temporal example](https://github.com/tachyonmcp/tachyon/tree/main/examples/temporal).
