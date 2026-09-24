// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.api.server.features.PaginatedResult
import dev.tachyonmcp.api.server.features.tasks.TaskConnector
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.api.server.session.SessionIdGenerator
import dev.tachyonmcp.core.server.config.SessionConfig
import dev.tachyonmcp.core.server.session.InMemorySessionEventStore
import dev.tachyonmcp.core.server.session.InMemorySessionStore
import dev.tachyonmcp.kotlin.server.domain.Annotations
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.domain.PromptArgument
import dev.tachyonmcp.kotlin.server.domain.PromptMessage
import dev.tachyonmcp.kotlin.server.domain.TextContent
import dev.tachyonmcp.kotlin.server.domain.TextResourceContents
import dev.tachyonmcp.kotlin.server.features.prompts.PromptDescriptor
import dev.tachyonmcp.kotlin.server.features.resources.ResourceDescriptor
import dev.tachyonmcp.kotlin.server.features.tools.ToolDescriptor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.delay
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Stateful servers: `session { … }` turns session state on, and the DSL binds a Netty transport
 * that real MCP clients drive over HTTP.
 */
internal class StatefulServerTest {
    @Suppress("DEPRECATION")
    private val taskConnector: TaskConnector =
        TaskConnector
            .builder()
            .get { _, _ -> error("unused") }
            .cancel { _, _ -> }
            .update { _, _ -> }
            .list { _, _ -> PaginatedResult.of(emptyList(), null, true) }
            .awaitResult { _, _ -> error("unused") }
            .build()

    private data class JacksonPayload(
        val message: String,
    )

    @Test
    fun `enable alone makes the server stateful with defaults`() {
        buildServer {
            name("kotlin-session-defaults")
            session { enable() }
        }.use { server ->
            server.config().session.enabled shouldBe true
            server.config().session.sessionTtl shouldBe SessionConfig.DEFAULT_SESSION_TTL
        }
    }

    @Test
    fun `assigning the default id generator still opts in, as on the Java builder`() {
        buildServer {
            name("kotlin-session-default-generator")
            session { sessionIdGenerator = SessionIdGenerator.DEFAULT }
        }.use { server ->
            server.config().session.enabled shouldBe true
            server.config().session.sessionIdGenerator shouldBe SessionIdGenerator.DEFAULT
        }
    }

    @Test
    fun `session option alone makes the server stateful`() {
        buildServer {
            name("kotlin-session-implied")
            session { sessionTtl = 15.seconds }
        }.use { server ->
            server.config().session.enabled shouldBe true
            server.config().session.sessionTtl shouldBe 15.seconds.toJavaDuration()
        }
    }

    @Test
    fun `all DSL parameters`() {
        val appName = "tachyon-e2e"
        val expectedIcon =
            Icon {
                src = "https://example.com/s/icon.png"
                mimeType = "image/png"
                sizes = listOf("64x64")
                theme = "white"
            }
        TachyonServer(
            port = 0,
        ) {
            info {
                name = appName
                title = "My Test MCP Server"
                icons += expectedIcon
                websiteUrl = "https://example.com/mcp"
                version = "2.0.0"
                description = "e2e test server"
                instructions = "ignore me"
            }
            capabilities {
                tools {
                    mode = Mode.ON
                    listChanged = true
                    pageSize = 20
                }
                resources {
                    mode = Mode.ON
                    subscribe = true
                    listChanged = true
                    pageSize = 21
                }
                prompts {
                    mode = Mode.ON
                    listChanged = true
                    pageSize = 22
                }
                tasks(taskConnector) {
                    pageSize = 23
                    pollInterval = 250.milliseconds
                }
                completionsMode = Mode.ON
                logging = true
            }
            network {
                host = "127.0.0.1"
                port = 0
                endpointPath = "/mcp"
                allowedOrigins.add("https://App.Example.com:443")
                allowedHosts.add("host.docker.internal:8096")
                allowPrivateNetworks = true
                readerIdleTimeout = 124.seconds
                writerIdleTimeout = 60.seconds
                maxContentLength = 1_000_000
                maxPipelinedRequests = 4
                maxPendingSseBytes = 256 * 1024
            }
            session {
                enable()
                sessionTtl = 15.seconds
                sessionStore = InMemorySessionStore()
                sessionEventStore = InMemorySessionEventStore()
                sessionIdGenerator { _, req -> req.headers().get("X-Tenant-Id") ?: "anon" }
            }
            observability {
                slowRequestLogging(threshold = 15.seconds)
            }
            pipelineCustomizer { }
            tool("ping", "Health check") { ToolResult.text("pong") }
            resource(
                "config",
                "file:///config.yaml",
                description = "App config",
                mimeType = "text/yaml",
            ) {
                TextResourceContents(uri = uri, mimeType = "text/yaml", text = "key: value")
            }
            prompt("greet", "Say hello") {
                listOf(
                    PromptMessage(
                        role = Role.USER,
                        TextContent("Hello, ${arguments.stringOr("name", "world")}!"),
                    ),
                )
            }
        }.use { handle ->
            (handle.port() > 0) shouldBe true
            handle.host() shouldBe "127.0.0.1"
            val config = handle.config()

            // identity
            with(config.identity) {
                name() shouldBe appName
                title() shouldBe "My Test MCP Server"
                version() shouldBe "2.0.0"
                description() shouldBe "e2e test server"
                instructions() shouldBe "ignore me"
                websiteUrl() shouldBe "https://example.com/mcp"
                icons() shouldBe listOf(expectedIcon)
            }

            // capabilities
            with(config.capabilities) {
                tools().mode() shouldBe Mode.ON
                tools().listChanged() shouldBe true
                tools().pageSize() shouldBe 20
                resources().mode() shouldBe Mode.ON
                resources().subscribe() shouldBe true
                resources().listChanged() shouldBe true
                resources().pageSize() shouldBe 21
                prompts().mode() shouldBe Mode.ON
                prompts().listChanged() shouldBe true
                prompts().pageSize() shouldBe 22
                tasks().enabled() shouldBe true
                tasks().connector() shouldBe taskConnector
                tasks().pageSize() shouldBe 23
                completions() shouldBe Mode.ON
                logging() shouldBe true
            }

            // session
            config.session.enabled shouldBe true
            config.session.sessionTtl shouldBe 15.seconds.toJavaDuration()
            config.session.sessionIdGenerator shouldNotBe SessionIdGenerator.DEFAULT

            // network
            with(config.network) {
                host shouldBe "127.0.0.1"
                endpointPath shouldBe "/mcp"
                allowedHosts shouldBe listOf("host.docker.internal:8096")
                allowedOrigins shouldBe listOf("https://app.example.com")
                readerIdleTimeout shouldBe 124.seconds.toJavaDuration()
                writerIdleTimeout shouldBe 60.seconds.toJavaDuration()
                maxContentLength shouldBe 1_000_000
                maxPipelinedRequests shouldBe 4
                maxPendingSseBytes shouldBe 256 * 1024
                allowPrivateNetworks shouldBe true
            }

            with(config.observability) {
                slowRequestLogging shouldBe true
                slowRequestThreshold shouldBe 15.seconds.toJavaDuration()
            }

            // registered features
            handle.tools().find("ping").orElse(null) shouldNotBe null
            handle.prompts().find("greet").orElse(null) shouldNotBe null
            handle.resources().find("config").orElse(null) shouldNotBe null

            // MCP initialize
            McpProbe(handle.port()).use { probe ->
                val init = probe.initialize()
                init.statusCode() shouldBe 200
                init.body() shouldContain appName
                init.body() shouldContain "2.0.0"
            }
        }
    }

    @Test
    fun `network port set via DSL without factory port parameter`() {
        TachyonServer(
            configure = {
                name("dsl-port-test")
                session { enable() }
                network { port = 0 }
            },
        ).use { handle ->
            (handle.port() > 0) shouldBe true
        }
    }

    @Test
    fun `Kotlin DSL retains Jackson serde by default`() {
        TachyonServer(port = 0) {
            name("jackson-default-test")
            session { enable() }
            tool("jackson-default") { success(JacksonPayload("ok")) }
        }.use { server ->
            McpProbe(server.port()).use { probe ->
                probe.initialize()
                probe.callTool("jackson-default").body() shouldContain
                    """"structuredContent":{"message":"ok"}"""
            }
        }
    }

    @Test
    fun `name-based suspend resourceTemplate registers and lists template`() {
        val icon =
            Icon {
                src = "https://example.com/user.svg"
                mimeType = "image/svg+xml"
            }
        val annotations = Annotations { priority = 0.7 }
        TachyonServer(port = 0) {
            name("template-test")
            session { enable() }
            resourceTemplate(
                name = "user-profile",
                uriTemplate = "user://{userId}/profile",
                description = "User profile template",
                mimeType = "application/json",
                title = "User profile",
                annotations = annotations,
                icons = listOf(icon),
            ) {
                TextResourceContents {
                    text = "{\"id\":\"${param("userId")}\"}"
                }
            }
        }.use { handle ->
            McpProbe(handle.port()).use { probe ->
                probe.initialize()
                val response = probe.request(2, "resources/templates/list")
                response.statusCode() shouldBe 200
                response.body() shouldContain "user-profile"
                response.body() shouldContain "user://{userId}/profile"
                response.body() shouldContain "User profile"
                response.body() shouldContain "https://example.com/user.svg"

                val readResponse =
                    probe.post(
                        """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"user://42/profile"}}""",
                    )
                readResponse.statusCode() shouldBe 200
                readResponse.body() shouldContain """"uri":"user://42/profile""""
                readResponse.body() shouldContain """"mimeType":"application/json""""
                readResponse.body() shouldContain """{\"id\":\"42\"}"""
            }
        }
    }

    @Test
    fun `static resource contents inherit registered uri and mime type`() {
        val icon = Icon { src = "https://example.com/resource.svg" }
        val expectedAnnotations = Annotations { priority = 0.8 }
        val blobDescriptor =
            ResourceDescriptor {
                name = "blob"
                uri = "test://blob"
                mimeType = "application/octet-stream"
                title = "Binary resource"
                annotations = expectedAnnotations
                size = 2
                icons = listOf(icon)
            }
        TachyonServer(port = 0) {
            name("contextual-resource-contents-test")
            session { enable() }
            resource(
                name = "text",
                uri = "test://text",
                description = "Text resource",
                mimeType = "text/plain",
                title = "Text resource title",
                annotations = expectedAnnotations,
                size = 5,
                icons = listOf(icon),
            ) {
                TextResourceContents { text = "hello" }
            }

            resource(blobDescriptor) {
                BlobResourceContents { data = byteArrayOf(1, 2) }
            }
        }.use { handle ->
            with(handle.resources().find("text").orElseThrow()) {
                description() shouldBe "Text resource"
                mimeType() shouldBe "text/plain"
                title() shouldBe "Text resource title"
                annotations() shouldBe expectedAnnotations
                size() shouldBe 5
                icons() shouldBe listOf(icon)
            }
            handle.resources().find("blob").orElseThrow() shouldBe blobDescriptor

            McpProbe(handle.port()).use { probe ->
                probe.initialize()

                val text =
                    probe.post(
                        """{"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"test://text"}}""",
                    )
                text.statusCode() shouldBe 200
                text.body() shouldContain """"uri":"test://text""""
                text.body() shouldContain """"mimeType":"text/plain""""
                text.body() shouldContain """"text":"hello""""

                val blob =
                    probe.post(
                        """{"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"test://blob"}}""",
                    )
                blob.statusCode() shouldBe 200
                blob.body() shouldContain """"uri":"test://blob""""
                blob.body() shouldContain """"mimeType":"application/octet-stream""""
                blob.body() shouldContain """"blob":"AQI=""""
            }
        }
    }

    @Test
    fun `tool accepts a prebuilt descriptor`() {
        val descriptor =
            ToolDescriptor {
                name = "descriptor-tool"
                title = "Descriptor Tool"
                description = "Tool built from a prebuilt descriptor"
            }

        TachyonServer(port = 0) {
            name("descriptor-tool-test")
            session { enable() }
            tool(descriptor) { ToolResult.text("descriptor-ok") }
        }.use { handle ->
            handle.tools().find("descriptor-tool").orElseThrow() shouldBe descriptor
            McpProbe(handle.port()).use { probe ->
                probe.initialize()
                val response = probe.callTool("descriptor-tool")
                response.statusCode() shouldBe 200
                response.body() shouldContain "descriptor-ok"
            }
        }
    }

    @Test
    fun `prompt accepts a prebuilt descriptor with arguments`() {
        val descriptor =
            PromptDescriptor {
                name = "descriptor-prompt"
                description = "Prompt built from a prebuilt descriptor"

                arguments =
                    listOf(
                        PromptArgument {
                            name = "style"
                            title = "Style"
                            description = "Narration style"
                            required = true
                        },
                    )
            }

        TachyonServer(port = 0) {
            name("descriptor-prompt-test")
            session { enable() }
            prompt(descriptor) {
                listOf(
                    PromptMessage(
                        role = Role.USER,
                        TextContent("styled: ${arguments.stringOr("style", "none")}"),
                    ),
                )
            }
        }.use { handle ->
            handle.prompts().find("descriptor-prompt").orElseThrow() shouldBe descriptor
            McpProbe(handle.port()).use { probe ->
                probe.initialize()
                val response =
                    probe.post(
                        """{"jsonrpc":"2.0","id":2,"method":"prompts/get","params":{"name":"descriptor-prompt"}}""",
                    )
                response.statusCode() shouldBe 200
                response.body() shouldContain "styled: none"
            }
        }
    }

    @Test
    fun `suspend tool with delay returns correct result`() {
        TachyonServer(port = 0) {
            name("delay-test")
            session { enable() }
            tool("slow", "Delayed") {
                delay(10.milliseconds)
                ToolResult.text("delayed-ok")
            }
        }.use { handle ->
            McpProbe(handle.port()).use { probe ->
                val init = probe.initialize()
                init.statusCode() shouldBe 200

                val response = probe.callTool("slow")
                response.statusCode() shouldBe 200
                response.body() shouldContain "delayed-ok"
            }
        }
    }

    @Test
    fun `tool with outputSchema appears in tools list`() {
        val outputSchema = """{"type":"object","properties":{"result":{"type":"string"}}}"""
        TachyonServer(port = 0) {
            name("output-test")
            session { enable() }
            tool(
                "with-output",
                "Has output schema",
                inputSchema = JsonSchema.unchecked("""{"type":"object"}"""),
                outputSchema = JsonSchema.unchecked(outputSchema),
            ) {
                ToolResult.text("done")
            }
        }.use { handle ->
            McpProbe(handle.port()).use { probe ->
                probe.initialize()
                val response = probe.request(2, "tools/list")
                response.body() shouldContain "with-output"
                response.body() shouldContain "outputSchema"
            }
        }
    }

    @Test
    fun `notification sent during suspend tool arrives on the request stream`() {
        TachyonServer(port = 0) {
            name("notify-test")
            capabilities { logging = true }
            session { enable() }
            tool("notify", "Notifies mid-run") {
                delay(10.milliseconds)
                ctx.notifications().info("notify-test", "mid-run-note")
                delay(10.milliseconds)
                ToolResult.text("notify-done")
            }
        }.use { handle ->
            McpProbe(handle.port()).use { probe ->
                probe.initialize()
                val response = probe.callTool("notify")
                response.statusCode() shouldBe 200
                response.headers().firstValue("content-type").orElse("") shouldContain
                    "text/event-stream"

                val body = response.body()
                body shouldContain "notifications/message"
                body shouldContain "mid-run-note"
                body shouldContain "notify-done"
                (body.indexOf("mid-run-note") < body.indexOf("notify-done")) shouldBe true
            }
        }
    }
}
