// Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
package dev.tachyonmcp.kotlin.server.config

import dev.tachyonmcp.core.server.config.NetworkConfig
import dev.tachyonmcp.core.transport.netty.NettyIoEngine
import dev.tachyonmcp.kotlin.server.TachyonDsl
import java.net.SocketAddress
import kotlin.time.Duration
import kotlin.time.toJavaDuration

@TachyonDsl
public class NetworkScope
    internal constructor() {
        /** Network interface to bind to. */
        public var host: String? = null

        /** Port to listen on. */
        public var port: Int? = null

        /** Bind address and port from a [SocketAddress]. Mutually exclusive with [host]/[port]. */
        public var address: SocketAddress? = null

        /** Base path for MCP endpoints. */
        public var endpointPath: String? = null

        /** Whether to allow connections from private networks. */
        public var allowPrivateNetworks: Boolean? = null

        /** Idle timeout for the reader side of the connection. */
        public var readerIdleTimeout: Duration? = null

        /** Idle timeout for the writer side of the connection. */
        public var writerIdleTimeout: Duration? = null

        /** SSE heartbeat interval for silent listening streams. */
        public var heartbeatInterval: Duration? = null

        /** Maximum allowed content length for incoming requests. */
        public var maxContentLength: Int? = null

        /** Netty I/O engine configuration. */
        public var ioEngine: NettyIoEngine? = null

        /** Exact origins the DNS-rebinding guard admits and CORS grants; empty grants any loopback origin. */
        public val allowedOrigins: MutableList<String> = mutableListOf()

        /** CORS request headers granted beyond the built-in MCP ones. */
        public val allowedHeaders: MutableList<String> = mutableListOf()

        /**
         * Additional `Host` authorities the DNS-rebinding guard accepts beyond `localhost`/`127.0.0.1`
         * — e.g. `"host.docker.internal:8096"` for a sanctioned server reached over a Docker bridge.
         * Empty (default) keeps the localhost-only behaviour.
         */
        public val allowedHosts: MutableList<String> = mutableListOf()

        internal fun applyTo(builder: NetworkConfig.Builder) {
            val addr = address
            if (addr != null) {
                builder.address(addr)
            } else {
                host?.let(builder::host)
                port?.let(builder::port)
            }
            endpointPath?.let(builder::endpointPath)
            if (allowedOrigins.isNotEmpty()) builder.allowedOrigins(*allowedOrigins.toTypedArray())
            allowPrivateNetworks?.let(builder::allowPrivateNetworks)
            if (allowedHeaders.isNotEmpty()) builder.allowedHeaders(*allowedHeaders.toTypedArray())
            if (allowedHosts.isNotEmpty()) builder.allowedHosts(*allowedHosts.toTypedArray())
            readerIdleTimeout?.let { builder.readerIdleTimeout(it.toJavaDuration()) }
            writerIdleTimeout?.let { builder.writerIdleTimeout(it.toJavaDuration()) }
            heartbeatInterval?.let { builder.heartbeatInterval(it.toJavaDuration()) }
            maxContentLength?.let(builder::maxContentLength)
            ioEngine?.let(builder::ioEngine)
        }
    }
