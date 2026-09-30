// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.ToolAnnotations
import dev.tachyonmcp.api.server.features.tasks.TaskConnector
import dev.tachyonmcp.api.server.features.tasks.TaskSupport
import dev.tachyonmcp.kotlin.server.config.tasks
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.features.tools.toolDescriptor
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * Every flat `tool(...)` / `registerTool(...)` overload must accept the full optional attribute
 * set of [dev.tachyonmcp.api.server.features.tools.ToolDescriptor.Builder].
 */
internal class ToolDescriptorAttributesTest {
    private class TypedInput

    private class TypedOutput

    @Test
    fun `every optional attribute reaches the descriptor`() {
        val icon = Icon { src = "https://example.com/tool.png" }
        val toolAnnotations = ToolAnnotations.builder().readOnlyHint(true).build()

        buildServer {
            tasks(DescriptorTaskConnector)
            tool(
                name = "build-time",
                description = "desc",
                title = "Title",
                inputSchema = JsonSchema.objectSchema(),
                outputSchema = JsonSchema.objectSchema(),
                taskSupport = TaskSupport.OPTIONAL,
                annotations = toolAnnotations,
                icons = listOf(icon),
                meta = mapOf("k" to "v"),
            ) { text("ok") }
        }.use { server ->
            server.registerTool(
                name = "post-build",
                description = "desc",
                title = "Title",
                inputSchema = JsonSchema.objectSchema(),
                outputSchema = JsonSchema.objectSchema(),
                taskSupport = TaskSupport.OPTIONAL,
                annotations = toolAnnotations,
                icons = listOf(icon),
                meta = mapOf("k" to "v"),
            ) { text("ok") }

            server.registerTool<TypedInput, TypedOutput>(
                name = "post-build-typed",
                description = "desc",
                title = "Title",
                taskSupport = TaskSupport.OPTIONAL,
                annotations = toolAnnotations,
                icons = listOf(icon),
                meta = mapOf("k" to "v"),
            ) { _: TypedInput -> TypedOutput() }

            listOf("build-time", "post-build", "post-build-typed").forEach { name ->
                withClue(name) {
                    val descriptor = server.tools().find(name).orElseThrow()
                    descriptor.title() shouldBe "Title"
                    descriptor.description() shouldBe "desc"
                    descriptor.taskSupport() shouldBe TaskSupport.OPTIONAL
                    descriptor.annotations() shouldBe toolAnnotations
                    descriptor.icons() shouldBe listOf(icon)
                    descriptor.meta() shouldBe mapOf("k" to "v")
                    descriptor.inputSchema() shouldNotBe null
                    descriptor.outputSchema() shouldNotBe null
                }
            }
        }
    }

    @Test
    fun `extensionId is not a flat tool attribute, only settable via the descriptor scope`() {
        val descriptor = toolDescriptor("extension-owned") { extensionId = "ext" }

        descriptor.extensionId() shouldBe "ext"
    }
}

internal val DescriptorTaskConnector: TaskConnector =
    TaskConnector
        .builder()
        .get { _, _ -> error("unused") }
        .cancel { _, _ -> }
        .update { _, _ -> }
        .build()
