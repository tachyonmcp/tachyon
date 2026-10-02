package dev.tachyonmcp.docs.running.configuration

import dev.tachyonmcp.core.transport.netty.NettyIoEngine
import dev.tachyonmcp.kotlin.server.config.TachyonServerBuilder
import kotlin.time.Duration.Companion.minutes

internal fun TachyonServerBuilder.extraHost() {
    // snips-start: config_kotlin_allowed_hosts
    network { allowedHosts += "host.docker.internal:8096" }
    // snips-end: config_kotlin_allowed_hosts
}

internal fun TachyonServerBuilder.epoll() {
    // snips-start: config_kotlin_io_engine
    network { ioEngine = NettyIoEngine.EPOLL }
    // snips-end: config_kotlin_io_engine
}

internal fun TachyonServerBuilder.sessionAlternatives() {
    // snips-start: config_kotlin_session_alternatives
    session { sessionTtl = 5.minutes }   // sessions on, custom TTL
    session { enable() }                 // sessions on, all defaults
    stateless()                          // explicitly stateless (the default)
    // snips-end: config_kotlin_session_alternatives
}

