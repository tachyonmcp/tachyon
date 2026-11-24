# MCP Tasks with Temporal

This example keeps the ownership boundary sharp: one MCP task maps to one [Temporal](https://temporal.io) Workflow ID.
The `tachyon-tasks-temporal` integration starts, refreshes, updates, and cancels through
`TemporalTaskExecutionEngine`. Temporal owns durability, retries, timers, and business execution.

The server side needs `dev.tachyonmcp:tachyon-extensions-tasks` for `TasksExtension`.

```java
var taskEngine = BookingTaskEngine.create(workflowClient, "bookings");

var server = TachyonServer.builder()
        .withExtension(TasksExtension.class, t -> t.connector(taskEngine.connector()))
        .port(8080)
        .build();

server.tools().register(
        tool -> tool.name("book_appointment").taskSupport(TaskSupport.REQUIRED),
        (context, request) -> ToolResult.task(taskEngine.start(
                context,
                TemporalTaskStartRequest.builder()
                        .taskId(UUID.randomUUID().toString())
                        .operation("book_appointment")
                        .arguments(request.arguments())
                        .meta(request.meta())
                        .build())));
```

The adapter uses the Tachyon task ID as the Temporal Workflow ID. It deliberately does not retain a
Temporal Run ID, so Continue-As-New preserves the logical MCP task identity.

## Resuming after a pause

A healthy task is not proof that the slot is still free. `BookingWorkflowImpl` pauses in
`input_required` for approval, and the approval may arrive much later, so the Workflow asks the
`SlotAvailability` Activity again right before it books. If the slot is gone it returns to
`input_required` with a message instead of completing. An update validator refuses input while the
Workflow is not waiting for it.

## Test locally

Run the JUnit 5 in-memory and Testcontainers tests:

```shell
./mvnw test -f examples/temporal/pom.xml
```

Docker is required for the container test. When Docker is unavailable, the JUnit 5 Testcontainers
extension skips that test while the `TestWorkflowEnvironment` test still runs.
