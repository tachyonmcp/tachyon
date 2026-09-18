// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server

import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.api.server.extensions.AdvertiseMode
import dev.tachyonmcp.api.server.extensions.ExtensionContext
import dev.tachyonmcp.api.server.extensions.ServerExtension
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.core.server.config.SessionConfig
import dev.tachyonmcp.kotlin.server.features.resources.ResourceTemplateDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

/**
 * Stateless servers: no session state, and `buildServer` wires the registries
 * without binding transport.
 */
internal class StatelessServerTest {
    private class TypedInput

    private class TypedOutput

    @Test
    fun `buildServer registers features without binding a transport`() {
        buildServer {
            name("kotlin-build")
            capabilities {
                tools { mode = Mode.AUTO }
            }
            tool("build", "Build test") { ToolResult.text("built") }
        }.use { server ->
            server.tools().find("build").orElse(null) shouldNotBe null
            shouldThrow<IllegalStateException> { server.port() }
                .message shouldContain "Server not started"
        }
    }

    @Test
    fun `stateless is the default and can be stated explicitly`() {
        buildServer {
            name("kotlin-stateless-explicit")
            stateless()
        }.use { server ->
            server.config().session.enabled shouldBe false
        }
        buildServer { name("kotlin-stateless-default") }.use { server ->
            server.config().session.enabled shouldBe false
        }
    }

    @Test
    fun `a session option combined with stateless is rejected`() {
        shouldThrow<IllegalStateException> {
            buildServer {
                name("kotlin-session-contradiction")
                stateless()
                session { sessionTtl = 15.seconds }
            }
        }.message shouldBe SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED
    }

    @Test
    @Suppress("DEPRECATION")
    fun `the deprecated enabled flag still states the opt-out and its contradiction`() {
        buildServer {
            name("kotlin-session-deprecated-off")
            session { enabled = false }
        }.use { server ->
            server.config().session.enabled shouldBe false
        }
        shouldThrow<IllegalStateException> {
            buildServer {
                name("kotlin-session-deprecated-contradiction")
                session {
                    enabled = false
                    sessionTtl = 15.seconds
                }
            }
        }.message shouldBe SessionConfig.SESSION_OPTIONS_REQUIRE_ENABLED
    }

    @Test
    fun `typed tools resolve schemas through the JsonSchemaFactory chain`() {
        buildServer {
            typedTool<TypedInput, TypedOutput>("typed") { ToolResult.text("unused") }
        }.use { server ->
            server.registerTool<TypedInput, TypedOutput>("typed-post-build", "Post-build") {
                TypedOutput()
            }

            listOf("typed", "typed-post-build").forEach { name ->
                withClue(name) {
                    val descriptor = server.tools().find(name).orElseThrow()
                    descriptor.inputSchema()?.json() shouldBe
                        """{"type":"object","title":"TypedInput"}"""
                    descriptor.outputSchema()?.json() shouldBe
                        """{"type":"object","title":"TypedOutput"}"""
                }
            }
            server
                .tools()
                .find("typed-post-build")
                .orElseThrow()
                .description() shouldBe
                "Post-build"
        }
    }

    @Test
    fun `extensions are registered and bootstrapped from a single vararg call`() {
        val bootstrapped = mutableSetOf<String>()

        fun extensionNamed(id: String) =
            object : ServerExtension {
                override fun extensionId(): String = id

                override fun advertiseMode(): AdvertiseMode = AdvertiseMode.ALWAYS

                override fun bootstrap(context: ExtensionContext) {
                    bootstrapped += id
                }
            }

        buildServer {
            name("multi-extension-test")
            extensions(
                extensionNamed("one"),
                extensionNamed("two"),
            )
        }.use {
            bootstrapped shouldBe setOf("one", "two")
        }
    }

    @Test
    fun `duplicate extension IDs are rejected`() {
        fun extensionNamed(id: String) =
            object : ServerExtension {
                override fun extensionId(): String = id

                override fun advertiseMode(): AdvertiseMode = AdvertiseMode.ALWAYS
            }

        shouldThrow<IllegalArgumentException> {
            buildServer {
                name("duplicate-extension-test")
                extensions(
                    extensionNamed("duplicate"),
                    extensionNamed("duplicate"),
                )
            }
        }.message shouldContain "Duplicate extension ID: duplicate"
    }

    @Test
    fun `runtime clock is wired through to the runtime config`() {
        val fixedClock = Clock.fixed(Instant.parse("2025-01-01T00:00:00Z"), ZoneOffset.UTC)
        buildServer {
            name("runtime-clock-test")
            runtime {
                clock = fixedClock
            }
        }.use { server ->
            server.config().runtime.clock() shouldBe fixedClock
        }
    }

    @Test
    fun `resourceTemplate accepts a prebuilt descriptor`() {
        val descriptor =
            ResourceTemplateDescriptor {
                name = "descriptor-template"
                uriTemplate = "descriptor://{id}"
                description = "Prebuilt descriptor"
                mimeType = "text/plain"
                title = "Descriptor title"
            }

        buildServer {
            resourceTemplate(descriptor) {
                TextResourceContents {
                    text = param("id")
                }
            }
        }.use { server ->
            server.resources().findTemplate("descriptor-template").orElseThrow() shouldBe descriptor
        }
    }
}
