// Copyright (c) 2026 Konstantin Pavlov and contributors.

package dev.tachyonmcp.skill

import dev.tachyonmcp.api.server.config.Mode
import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.core.server.config.NetworkConfig
import dev.tachyonmcp.core.server.json.NetworkntJsonSchemaValidator
import dev.tachyonmcp.core.transport.netty.NettyIoEngine
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.domain.PromptMessage
import dev.tachyonmcp.kotlin.server.domain.TextContent
import dev.tachyonmcp.kotlin.server.domain.TextResourceContents
import dev.tachyonmcp.kotlin.server.features.prompts.promptMessagesOf
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

fun assembleServer(port: Int = NetworkConfig.UNSET_PORT): TachyonServer =
    buildServer {
        // ── identity ──────────────────────────────────────────────
        info {
            name = "demo-server"
            title = "Demo MCP Server"
            version = "1.0"
            description = "Demo MCP server — shows all props"
            instructions = "Beep boop. I demo"
            websiteUrl = "https://example.com/mcp"
            icons +=
                Icon(
                    src = "https://example.com/icon.png",
                    mimeType = "image/png",
                    sizes = listOf("64x64"),
                    theme = "light",
                )
        }

        // ── capabilities — all switches + helpers ─────────────────
        capabilities {
            tools {
                mode = Mode.ON
                listChanged = true
            }
            resources {
                mode = Mode.ON
                subscribe = true
                listChanged = true
            }
            prompts {
                mode = Mode.ON
                listChanged = true
            }
            completionsMode = Mode.AUTO
            logging = true
        }

        // ── session — stateful or stateless ───────────────────────
        // stateless by default; any option below enables sessions
        session {
            sessionTtl = 5.minutes
            janitorInterval = 5.seconds
            // lambda DSL
            sessionIdGenerator { _, request -> request.headers().get("X-Tenant-Id") ?: "anon" }
            // or direct assignment, request-independent:
            // sessionIdGenerator = SessionIdGenerator { _, _ -> "sid_" + Uuid.random().toHexString() }
        }

        // ── runtime ───────────────────────────────────────────────
        runtime {
            shutdownGracePeriod = 7.seconds
        }

        // ── network — everything you can set ──────────────────────
        network {
            host = NetworkConfig.DEFAULT_HOST
            this.port = port
            endpointPath = NetworkConfig.DEFAULT_ENDPOINT_PATH
            allowedOrigins.add("*")
            allowedHeaders.add("Authorization")
            allowedHosts.add("host.docker.internal")
            allowNullOrigin = true
            allowPrivateNetworks = true
            readerIdleTimeout = NetworkConfig.DEFAULT_READER_IDLE_TIMEOUT.toKotlinDuration()
            writerIdleTimeout = NetworkConfig.DEFAULT_WRITER_IDLE_TIMEOUT.toKotlinDuration()
            heartbeatInterval = NetworkConfig.DEFAULT_HEARTBEAT_INTERVAL.toKotlinDuration()
            maxContentLength =
                1024 * 1024 // 1 MB — the actual default (NetworkConfig.DEFAULT_MAX_CONTENT_LENGTH)
            ioEngine = NettyIoEngine.AUTO
        }

        // ── json — serde + schema validation ──────────────────────
        json {
            serde = KxSerializationSerde.Default
            inputValidator = NetworkntJsonSchemaValidator() // default NetworkntJsonSchemaValidator
            outputValidator = inputValidator // default = inputValidator
        }

        // ── tools ─────────────────────────────────────────────────
        tool(name = "ping", description = "Simple ping") {
            ToolResult.text("pong")
        }

        // ── resources ─────────────────────────────────────────────
        resource(
            name = "config",
            uri = "demo://config",
            description = "Server configuration",
            mimeType = "application/json",
        ) {
            TextResourceContents(
                uri = uri,
                mimeType = "application/json",
                text = """{"mode":"production"}""",
            )
        }

        // ── prompts ───────────────────────────────────────────────
        prompt(name = "greet", description = "Generates a greeting") {
            promptMessagesOf(
                PromptMessage(
                    role = Role.USER,
                    content = TextContent("Say hello"),
                ),
            )
        }

        // ── netty pipeline customiser (escape hatch) ──────────────
        pipelineCustomizer { }
    }

fun main() {
    val server = assembleServer(8080)
    server.start()
    println("MCP server on http://localhost:${server.port()}/mcp")
}
