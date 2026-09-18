// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.features.tasks.TaskCancelRequest
import dev.tachyonmcp.api.server.features.tasks.TaskConnector
import dev.tachyonmcp.api.server.features.tasks.TaskGetRequest
import dev.tachyonmcp.api.server.features.tasks.TaskNotFoundException
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot
import dev.tachyonmcp.api.server.features.tasks.TaskState
import dev.tachyonmcp.api.server.features.tasks.TaskSupport
import dev.tachyonmcp.api.server.features.tools.ToolRequest
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.core.server.TachyonServer
import dev.tachyonmcp.core.server.internal.ServerEngine
import dev.tachyonmcp.core.server.session.DefaultDispatchContext
import dev.tachyonmcp.kotlin.server.features.CoroutineRuntime
import dev.tachyonmcp.kotlin.server.features.tools.toolFn
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class ToolFnFactoryTest {
    @Test
    fun `async handler returns while coroutine is suspended`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val handlerThread = AtomicReference<String>()

        withCoroutineRuntime { runtime, ctx ->
            val fn =
                toolFn("suspend-test", runtime) {
                    handlerThread.set(Thread.currentThread().name)
                    started.countDown()
                    release.await()
                    ToolResult.text("ok")
                }
            val request = ToolRequest.builder().name("suspend-test").build()

            val result = fn.apply(ctx, request).toCompletableFuture()

            started.await(5, TimeUnit.SECONDS) shouldBe true
            result.isDone shouldBe false
            release.countDown()
            result.get(5, TimeUnit.SECONDS) shouldBe ToolResult.text("ok")
            handlerThread.get().startsWith("kotlin-handler-") shouldBe true
        }
    }

    @Test
    fun `handler is reusable after exception`() {
        val calls = AtomicInteger()

        withCoroutineRuntime { runtime, ctx ->
            val fn =
                toolFn("supervisor-test", runtime) {
                    if (calls.incrementAndGet() == 1) {
                        error("boom")
                    }
                    ToolResult.text("ok")
                }
            val request = ToolRequest.builder().name("supervisor-test").build()

            val failure =
                shouldThrow<ExecutionException> {
                    fn.apply(ctx, request).toCompletableFuture().get(5, TimeUnit.SECONDS)
                }
            val second =
                fn.apply(ctx, request).toCompletableFuture().get(5, TimeUnit.SECONDS)

            failure.cause?.message shouldBe "boom"
            second shouldBe ToolResult.text("ok")
            calls.get() shouldBe 2
        }
    }

    @Test
    fun `server close cancels suspended handler`() {
        val runtime = CoroutineRuntime()
        val delegate =
            TachyonServer
                .builder()
                .withExtensions(runtime)
                .build() as ServerEngine
        val started = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        delegate.use { delegate ->
            val fn =
                toolFn("shutdown-test", runtime) {
                    started.countDown()
                    try {
                        delay(100500.seconds)
                    } finally {
                        cancelled.set(true)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    ToolResult.text("never")
                }
            val ctx = DefaultDispatchContext.stateless(delegate)

            fn.apply(
                ctx,
                ToolRequest.builder().name("shutdown-test").build(),
            )

            started.await(5, TimeUnit.SECONDS) shouldBe true
            delegate.close()
            cancelled.get() shouldBe true
        }
    }

    @Test
    fun `server close respects shutdown grace period`() {
        val runtime = CoroutineRuntime()
        val release = CompletableDeferred<Unit>()
        val started = CountDownLatch(1)
        val server =
            TachyonServer
                .builder()
                .threadFactory(Thread.ofVirtual().name("bounded-shutdown-", 0).factory())
                .runtime { it.shutdownGracePeriod(Duration.ofMillis(50)) }
                .withExtensions(runtime)
                .build() as ServerEngine
        val fn =
            toolFn("bounded-shutdown", runtime) {
                started.countDown()
                withContext(NonCancellable) {
                    release.await()
                }
                ToolResult.text("done")
            }

        fn.apply(
            DefaultDispatchContext.stateless(server),
            ToolRequest.builder().name("bounded-shutdown").build(),
        )
        try {
            started.await(5, TimeUnit.SECONDS) shouldBe true

            val before = System.nanoTime()
            server.close()
            val elapsed = Duration.ofNanos(System.nanoTime() - before)

            (elapsed < Duration.ofSeconds(2)) shouldBe true
        } finally {
            release.complete(Unit)
            server.close()
        }
    }

    @Test
    fun `tasks cancel is delivered to the task connector`() {
        val taskId = "cancellable-task"
        val initial = TaskSnapshot.working(taskId, Instant.parse("2026-08-28T00:00:00Z"), 1)
        val engine = RecordingCancelTaskEngine(initial)
        val connector =
            TaskConnector
                .builder()
                .get(engine::refresh)
                .cancel(engine::cancel)
                .update { _, _ -> }
                .build()

        TachyonServer(port = 0) {
            name("cancellable-tool-test")
            session { enable() }
            capabilities {
                tasks(connector)
            }
            tool("cancellable", taskSupport = TaskSupport.REQUIRED) {
                ToolResult.task(initial)
            }
        }.use { server ->
            McpProbe(server.port()).use { probe ->
                probe.initialize()
                val call =
                    probe.post(
                        """{"jsonrpc":"2.0","id":2,"method":"tools/call","params":""" +
                            """{"name":"cancellable","arguments":{},"task":{}}}""",
                    )
                call.body() shouldContain taskId

                val cancel =
                    probe.post(
                        """{"jsonrpc":"2.0","id":3,"method":"tasks/cancel","params":{"taskId":"$taskId"}}""",
                    )
                val cancelJson = ObjectMapper().readTree(lastDataPayload(cancel.body()))

                cancelJson.at("/result/taskId").asString() shouldBe taskId
                cancelJson.at("/result/status").asString() shouldBe "cancelled"
                engine.cancelledTaskIds shouldBe listOf(taskId)
            }
        }
    }

    @Test
    fun `post-build tool uses server coroutine runtime`() {
        TachyonServer(port = 0) {
            name("dynamic-tool-test")
            session { enable() }
        }.use { server ->
            server.registerTool("dynamic") {
                delay(10.milliseconds)
                ToolResult.text("dynamic-ok")
            }

            McpProbe(server.port()).use { probe ->
                probe.initialize()
                probe.callTool("dynamic").body() shouldContain "dynamic-ok"
            }
        }
    }

    /**
     * Extracts the JSON-RPC payload from an HTTP response body that may be plain JSON or a single
     * SSE frame (`Accept: application/json, text/event-stream` lets the server pick either) --
     * returns the last `data:` line's content, or the raw body if there is no SSE framing.
     */
    private fun lastDataPayload(body: String): String =
        body
            .lines()
            .lastOrNull { it.startsWith("data:") }
            ?.removePrefix("data:")
            ?.trim()
            ?: body

    private fun withCoroutineRuntime(block: (CoroutineRuntime, InteractionContext) -> Unit) {
        val runtime = CoroutineRuntime()
        val delegate =
            TachyonServer
                .builder()
                .threadFactory(Thread.ofVirtual().name("kotlin-handler-", 0).factory())
                .withExtensions(runtime)
                .build() as ServerEngine
        delegate.use {
            block(runtime, DefaultDispatchContext.stateless(delegate))
        }
    }

    private class RecordingCancelTaskEngine(
        initial: TaskSnapshot,
    ) {
        val cancelledTaskIds = CopyOnWriteArrayList<String>()
        private val snapshot = AtomicReference(initial)

        fun refresh(
            @Suppress("unused")
            context: InteractionContext,
            request: TaskGetRequest,
        ): TaskSnapshot =
            snapshot.get().takeIf { it.taskId() == request.taskId() }
                ?: throw TaskNotFoundException(request.taskId())

        fun cancel(
            @Suppress("unused")
            context: InteractionContext,
            request: TaskCancelRequest,
        ) {
            cancelledTaskIds += request.taskId()
            snapshot.updateAndGet {
                TaskSnapshot
                    .builder()
                    .from(it)
                    .status(TaskState.CANCELLED)
                    .revision(it.revision() + 1)
                    .build()
            }
        }
    }
}
