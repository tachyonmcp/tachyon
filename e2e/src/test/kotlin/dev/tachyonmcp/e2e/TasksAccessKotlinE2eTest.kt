// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
@file:OptIn(ExperimentalTime::class)

package dev.tachyonmcp.e2e

import dev.tachyonmcp.api.server.domain.TaskResult
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot
import dev.tachyonmcp.api.server.features.tasks.TaskState
import dev.tachyonmcp.api.server.features.tasks.TaskSupport
import dev.tachyonmcp.api.server.features.tasks.Tasks
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.extensions.tasks.TasksExtension
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.config.tasks
import dev.tachyonmcp.kotlin.server.domain.TaskSnapshot
import dev.tachyonmcp.testkit.McpTestClients
import dev.tachyonmcp.testkit.TestTaskConnector
import io.kotest.matchers.equals.shouldEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeSameInstanceAs
import net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatIllegalStateException
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import tools.jackson.databind.node.JsonNodeFactory
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

/** Kotlin `ToolScope.tasks` and `TachyonServer.tasks`, over the wire. */
internal class TasksAccessKotlinE2eTest {
    private val created = Instant.parse("2026-09-27T07:00:00Z")
    private val javaCreated = created.toJavaInstant()

    @Test
    fun `tool handler publishes from background work through ToolScope tasks`() {
        val jobStore = TestTaskConnector()
        val handlerTasks = AtomicReference<Tasks>()
        val release = CountDownLatch(1)
        TachyonServer(port = 0) {
            tasks(jobStore.connector())
            tool(name = "report", taskSupport = TaskSupport.REQUIRED) {
                val tasks = tasks
                handlerTasks.set(tasks)
                val task = TaskSnapshot.working("kt-report-1", javaCreated, 1)
                jobStore.publish(task)
                Thread.startVirtualThread {
                    release.await()
                    val result = ToolResult.text("Report ready")
                    tasks.publish(
                        TaskSnapshot.completed(
                            "kt-report-1",
                            javaCreated,
                            java.time.Instant.now(),
                            2,
                            result,
                        ),
                    )
                }
                ToolResult.task(task)
            }
        }.use { server ->
            McpTestClients
                .latest(server.port())
                .withExtensions(mapOf(TasksExtension.ID to JsonNodeFactory.instance.objectNode()))
                .use { client ->
                    val call =
                        client.post(
                            """
                            {"jsonrpc":"2.0","id":1,"method":"tools/call",
                             "params":{"name":"report","arguments":{}}}
                            """.trimIndent(),
                        )
                    call.statusCode() shouldEqual 200
                    assertThatJson(call.body()).inPath("$.result.taskId").isEqualTo("kt-report-1")
                    assertThatJson(call.body()).inPath("$.result.status").isEqualTo("working")
                }

            handlerTasks.get() shouldBeSameInstanceAs server.tasks
            server.tasks shouldBeSameInstanceAs TasksExtension.tasks(server)

            release.countDown()
            await().atMost(Duration.ofSeconds(5)).untilAsserted {
                server.tasks
                    .get("kt-report-1")
                    .shouldNotBeNull()
                    .status() shouldEqual
                    TaskState.COMPLETED
            }
        }
    }

    @Test
    fun `TaskSnapshot factory builds and copies snapshots for publish`() {
        TachyonServer(port = 0) { tasks(TestTaskConnector().connector()) }.use { server ->
            val working =
                server.tasks.publish(
                    TaskSnapshot {
                        taskId = "kt-charge-1"
                        status = TaskState.WORKING
                        statusMessage = "Charging card"
                        createdAt = created
                        lastUpdatedAt = created
                        pollInterval = 2.seconds
                        revision = 1
                    },
                )
            working.statusMessage() shouldEqual "Charging card"
            working.pollInterval() shouldEqual Duration.ofSeconds(2)

            val completed =
                server.tasks.publish(
                    TaskSnapshot(from = working) {
                        status = TaskState.COMPLETED
                        statusMessage = null
                        result = TaskResult.completed(ToolResult.text("Charged"))
                        lastUpdatedAt = created + 5.seconds
                    },
                )

            completed.taskId() shouldEqual "kt-charge-1"
            completed.createdAt() shouldEqual javaCreated
            completed.pollInterval() shouldEqual Duration.ofSeconds(2)
            completed.revision() shouldEqual working.revision() + 1
            TaskSnapshot(from = completed) { revision = 7 }.revision() shouldEqual 7L
            server.tasks.get("kt-charge-1") shouldEqual completed
        }
    }

    @Test
    fun `TaskSnapshot factory names a missing required field`() {
        assertThatIllegalArgumentException()
            .isThrownBy {
                TaskSnapshot {
                    status = TaskState.WORKING
                    createdAt = created
                    lastUpdatedAt = created
                    revision = 1
                }
            }.withMessage("TaskSnapshot.taskId is required")
    }

    @Test
    fun `server tasks fails fast without the tasks extension`() {
        TachyonServer(port = 0) { }.use { server ->
            assertThatIllegalStateException()
                .isThrownBy { server.tasks }
                .withMessage(
                    "TasksExtension is not registered: add .withExtension(TasksExtension.class, t -> t.connector(...))",
                )
        }
    }
}
