package dev.tachyonmcp.docs.features.tools

import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder

internal fun TachyonServerBuilder.reverseTool() {
    // snips-start: tools_kotlin_reverse
    tool(name = "reverse", description = "Reverse a string") {
        val msg = arguments.stringValue("message")
        text(msg.reversed())
    }
    // snips-end: tools_kotlin_reverse
}
