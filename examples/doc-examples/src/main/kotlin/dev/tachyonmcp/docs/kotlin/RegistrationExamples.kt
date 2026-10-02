package dev.tachyonmcp.docs.kotlin

import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder
import dev.tachyonmcp.kotlin.server.features.completions.CompletionResult
import dev.tachyonmcp.kotlin.server.features.tools.ToolDescriptor
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import java.util.concurrent.atomic.AtomicInteger

internal fun TachyonServerBuilder.completions() {
    // snips-start: kotlin_completions
    promptCompletion("rewrite-forecast") {
        CompletionResult {
            values = listOf("plain", "concise", "pirate").filter { it.startsWith(argumentValue) }
        }
    }
    resourceCompletion("myapp://users/{userId}/profile") {
        CompletionResult {
            values = listOf("alice", "bob").filter { it.startsWith(argumentValue) }
            hasMore = false
        }
    }
    // snips-end: kotlin_completions
}

internal fun postBuildRegistration(): TachyonServer {
    // snips-start: kotlin_post_build_registration
    val server = buildServer { network { port = 0 } }

    server.registerTool(
        ToolDescriptor {
            name = "echo"
            description = "Echo a message"
        },
    ) {
        text(arguments.stringValue("msg"))
    }

    server.registerResource(name = "config", uri = "myapp://config") {
        TextResourceContents { text = """{"mode":"demo"}""" }
    }

    server.registerResourceTemplate(
        name = "user-profile",
        uriTemplate = "myapp://users/{userId}/profile",
    ) {
        TextResourceContents { text = """{"userId":"${param("userId")}"}""" }
    }

    server.registerPrompt(name = "rewrite-forecast") {
        content { text("Rewrite this forecast.") }
    }

    server.registerPromptCompletion("rewrite-forecast") {
        CompletionResult { values = listOf("plain", "concise", "pirate") }
    }

    server.registerResourceCompletion("myapp://users/{userId}/profile") {
        CompletionResult { values = listOf("alice", "bob") }
    }
    // snips-end: kotlin_post_build_registration
    return server
}

internal class MetricsHandler : ChannelDuplexHandler() {
    override fun channelRead(
        ctx: ChannelHandlerContext,
        msg: Any,
    ) {
        reads.incrementAndGet()
        ctx.fireChannelRead(msg)
    }

    companion object {
        val reads = AtomicInteger()
    }
}

internal fun TachyonServerBuilder.pipelineCustomization() {
    // snips-start: kotlin_pipeline_customizer
    pipelineCustomizer {
        addFirst("metrics", MetricsHandler())
    }
    // snips-end: kotlin_pipeline_customizer
}
