# Tachyon Tasks — Temporal

`tachyon-tasks-temporal` maps MCP tasks to durable Temporal Workflow Executions. Tachyon owns the
MCP projection. Temporal owns execution, retries, timers, durable waits, and cancellation.

```xml
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-tasks-temporal</artifactId>
    <version>${tachyon.version}</version>
</dependency>
<dependency>
    <groupId>dev.tachyonmcp</groupId>
    <artifactId>tachyon-extensions-tasks</artifactId>
    <version>${tachyon.version}</version>
</dependency>
```

`tachyon-extensions-tasks` provides `TasksExtension`, which serves the MCP `tasks/*` methods.

Define how an operation starts a Workflow, queries application-owned status, accepts input, and
maps that status to a complete `TaskSnapshot`:

```java
var bookingRoute = TemporalTaskRoute.builder(TemporalTaskStatus.class)
        .operation("book_appointment")
        .workflowType("BookingWorkflow")
        .startArguments(request -> new Object[] {request.arguments().asMap()})
        .statusQuery("taskStatus")
        .snapshotMapper(BookingTaskEngine::snapshot)
        .inputUpdate("provideInput", input -> new Object[] {input.inputResponses()})
        .build();

var taskEngine = TemporalTaskExecutionEngine.builder(workflowClient)
        .taskQueue("bookings")
        .route(bookingRoute)
        .build();

var server = TachyonServer.builder()
        .withExtension(TasksExtension.class, t -> t.connector(taskEngine.connector()))
        .port(8080)
        .build();

server.tools().register(
        tool -> tool.name("book_appointment").taskSupport(TaskSupport.REQUIRED),
        (context, request) -> {
            var start = TemporalTaskStartRequest.builder()
                    .taskId(UUID.randomUUID().toString())
                    .operation("book_appointment")
                    .arguments(request.arguments())
                    .meta(request.meta())
                    .build();
            return ToolResult.task(taskEngine.start(context, start));
        });
```

The MCP task ID becomes the Temporal Workflow ID. Refresh and input submission resolve the route
from Temporal's authoritative Workflow type, so a new engine instance can reattach after restart.
