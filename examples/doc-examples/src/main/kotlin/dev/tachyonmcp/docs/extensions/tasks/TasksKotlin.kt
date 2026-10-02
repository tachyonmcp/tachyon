package dev.tachyonmcp.docs.extensions.tasks

import dev.tachyonmcp.api.server.domain.TaskResult
import dev.tachyonmcp.api.server.features.tasks.TaskConnector
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot
import dev.tachyonmcp.api.server.features.tasks.TaskState
import dev.tachyonmcp.api.server.features.tasks.TaskSupport
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.config.tasks
import dev.tachyonmcp.kotlin.server.domain.TaskSnapshot
import java.time.Instant
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

internal fun kotlinTasksBuilder(taskConnector: TaskConnector): TachyonServer {
    val built =
        // snips-start: tasks_kotlin_builder
        buildServer {
            tasks(taskConnector) {
                pollInterval = 1.seconds
            }
        }
    // snips-end: tasks_kotlin_builder
    return built
}

fun main() {
    val workflows = Workflows()
    val taskConnector = workflows.connector()
    val snapshot = TaskSnapshot.working("wf-seed", Instant.now(), 1)
    // snips-start: tasks_kotlin_server
    val server = TachyonServer(port = 8080) {
        tasks(taskConnector)
        tool(name = "book", taskSupport = TaskSupport.REQUIRED) {
            val workflowId = workflows.start(arguments, tasks::publish) // ToolScope.tasks
            ToolResult.task(TaskSnapshot.working(workflowId, Instant.now(), 1))
        }
    }
    server.tasks.publish(snapshot) // TachyonServer.tasks, e.g. from a workflow callback
    // snips-end: tasks_kotlin_server
}

@OptIn(ExperimentalTime::class)
internal fun publishCompleted(server: TachyonServer, previous: TaskSnapshot) {
    // snips-start: tasks_kotlin_next
    server.tasks.publish(
        TaskSnapshot(from = previous) {
            status = TaskState.COMPLETED
            result = TaskResult.completed(ToolResult.text("Charged"))
            lastUpdatedAt = Clock.System.now()
        },
    )
    // snips-end: tasks_kotlin_next
}
