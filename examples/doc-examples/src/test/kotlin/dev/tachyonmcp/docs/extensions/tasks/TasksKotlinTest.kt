package dev.tachyonmcp.docs.extensions.tasks

import dev.tachyonmcp.api.server.domain.TaskResult
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot
import dev.tachyonmcp.api.server.features.tasks.TaskState
import dev.tachyonmcp.docs.JsonRpc
import dev.tachyonmcp.docs.kotlin.DOCUMENTED_PORT
import dev.tachyonmcp.docs.kotlin.ForkedMain
import dev.tachyonmcp.extensions.tasks.TasksExtension
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.config.tasks
import dev.tachyonmcp.kotlin.server.domain.TaskSnapshot
import dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class TasksKotlinTest {
    @Test
    fun `main returns a task from the book tool`() {
        ForkedMain.start("dev.tachyonmcp.docs.extensions.tasks.TasksKotlinKt").use {
            JsonRpc.declaring(DOCUMENTED_PORT, TasksExtension.ID).use { client ->
                val started =
                    assertThatResponse(
                        client.post(JsonRpc.request("tools/call", """{"name":"book","arguments":{}}""")),
                    ).hasStatus(200).isSuccess().result()

                started.path("resultType").asString() shouldBe "task"
                started.path("taskId").asString() shouldStartWith "wf-"
                started.path("status").asString() shouldBe "working"
            }
        }
    }

    @Test
    fun `buildServer registers the tasks extension`() {
        kotlinTasksBuilder(Workflows().connector()).use { server ->
            server.extension(TasksExtension::class.java).isPresent shouldBe true
        }
    }

    @Test
    fun `TaskSnapshot from a previous snapshot bumps the revision and carries the result`() {
        TachyonServer(port = 0) { tasks(Workflows().connector()) }.use { server ->
            val previous = TaskSnapshot.working("wf-k", Instant.parse("2026-09-24T07:00:00Z"), 3)
            server.tasks.publish(previous)

            publishCompleted(server, previous)

            val completed = server.tasks.get("wf-k")!!
            completed.status() shouldBe TaskState.COMPLETED
            completed.revision() shouldBe 4L
            completed.createdAt() shouldBe previous.createdAt()
            completed.result().shouldBeInstanceOf<TaskResult.Completed>()
        }
    }

    @Test
    fun `TaskSnapshot without from requires its mandatory fields`() {
        shouldThrow<IllegalArgumentException> { TaskSnapshot { taskId = "wf-x" } }
    }
}
