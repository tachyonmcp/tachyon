/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import dev.tachyonmcp.core.transport.netty.http.Origins;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Network-level server configuration.
 *
 * <p><b>Keep-alive for long-running tools.</b> {@code readerIdleTimeout} closes any connection
 * that receives no <em>inbound</em> bytes for its duration. A client that has finished sending a
 * request stays silent while awaiting the reply, so this timer also runs while a handler is
 * computing — a tool slower than {@code readerIdleTimeout} is reaped before it can respond. The
 * remedy is not a larger timeout but SSE keep-alive: when a handler emits a server→client message
 * (e.g. {@code progress(...)}), the response upgrades to {@code text/event-stream} and a scheduler
 * emits a {@code :} comment heartbeat every {@code heartbeatInterval}. Heartbeats are outbound and do
 * not reset the inbound {@code readerIdleTimeout}; size that timeout for dead-peer detection rather
 * than tool runtime.
 *
 * @param host               bind address (default {@code "127.0.0.1"})
 * @param port               listen port (must be set before {@code bind()})
 * @param endpointPath       HTTP path for MCP endpoints (default {@code "/mcp"}); must start with
 *                           {@code /} and contain no query, fragment, whitespace or control
 *                           characters
 * @param readerIdleTimeout  close connections with no inbound traffic for this long (default 60s);
 *                           long-running tools stay alive via SSE heartbeats, not a larger value
 * @param writerIdleTimeout  idle timeout for writing (default 5min)
 * @param maxContentLength   maximum HTTP body size in bytes
 * @param maxPipelinedRequests HTTP/1.1 pipelined requests that may wait behind the one in flight on a
 *                           connection (default 16); the next one gets {@code 429} and the connection
 *                           closes; {@code 0} disables pipelining
 * @param allowedOrigins     origins the DNS-rebinding guard admits and CORS grants, beyond loopback
 *                           ({@code null} = loopback on any port, answered with {@code *}); each a
 *                           serialized origin {@code http(s)://host[:port]}, stored canonical
 * @param allowPrivateNetworks whether to allow private network CORS
 * @param allowedHeaders     CORS request headers granted beyond the built-in MCP ones
 * @param allowedHosts       additional {@code Host} authorities the DNS-rebinding guard accepts
 *                           beyond localhost ({@code null} = localhost-only)
 * @param ioEngine           Netty I/O engine; defaults to {@link NettyIoEngine#AUTO}
 * @param heartbeatInterval  SSE heartbeat interval that keeps an upgraded stream alive (default
 *                           15s); keep below the idle timeout of any proxy in front of the server
 *                           and below the session TTL; {@code <= 0} disables
 * @param maxPendingSseBytes encoded, unsent output one POST-SSE stream may buffer (default 64
 *                           KiB; {@code 0}: none, so a tool waits until each event reaches the
 *                           socket); past it, a tool sending progress, logs or comments waits
 *                           for the client. A subscriber, or a tool writing from a Netty I/O
 *                           thread, is disconnected past this limit, or past the channel's write
 *                           high watermark when the limit is {@code 0}. The final response is
 *                           always accepted, and one larger event may exceed it
 */
@ExperimentalApi
public record NetworkConfig(
        String host,
        int port,
        String endpointPath,
        Duration readerIdleTimeout,
        Duration writerIdleTimeout,
        int maxContentLength,
        int maxPipelinedRequests,
        @Nullable List<String> allowedOrigins,
        boolean allowPrivateNetworks,
        @Nullable List<String> allowedHeaders,
        @Nullable List<String> allowedHosts,
        NettyIoEngine ioEngine,
        Duration heartbeatInterval,
        int maxPendingSseBytes) {

    public NetworkConfig {
        if (maxPipelinedRequests < 0) {
            throw new IllegalArgumentException("maxPipelinedRequests must not be negative");
        }
        if (maxPendingSseBytes < 0) {
            throw new IllegalArgumentException("maxPendingSseBytes must not be negative");
        }
        Objects.requireNonNull(endpointPath, "endpointPath");
        if (!isServablePath(endpointPath)) {
            throw new IllegalArgumentException("endpointPath must start with '/' and contain no query, fragment,"
                    + " whitespace or control characters");
        }
        if (allowedOrigins != null) {
            allowedOrigins =
                    allowedOrigins.stream().map(Origins::requireConfigured).toList();
        }
        if (allowedHeaders != null) {
            allowedHeaders = List.copyOf(allowedHeaders);
        }
        if (allowedHosts != null) {
            allowedHosts = List.copyOf(allowedHosts);
        }
    }

    private static boolean isServablePath(String path) {
        return path.startsWith("/")
                && path.chars()
                        .noneMatch(c -> c == '?' || c == '#' || Character.isWhitespace(c) || Character.isISOControl(c));
    }

    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int UNSET_PORT = -1;
    public static final String DEFAULT_ENDPOINT_PATH = "/mcp";
    public static final Duration DEFAULT_READER_IDLE_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration DEFAULT_WRITER_IDLE_TIMEOUT = Duration.ofMinutes(5);
    public static final Duration DEFAULT_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    public static final int DEFAULT_MAX_PIPELINED_REQUESTS = 16;
    public static final int DEFAULT_MAX_PENDING_SSE_BYTES = 64 * 1024;

    static final NetworkConfig DEFAULT = new NetworkConfig(
            DEFAULT_HOST,
            UNSET_PORT,
            DEFAULT_ENDPOINT_PATH,
            DEFAULT_READER_IDLE_TIMEOUT,
            DEFAULT_WRITER_IDLE_TIMEOUT,
            McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH,
            DEFAULT_MAX_PIPELINED_REQUESTS,
            null,
            false,
            null,
            null,
            NettyIoEngine.AUTO,
            DEFAULT_HEARTBEAT_INTERVAL,
            DEFAULT_MAX_PENDING_SSE_BYTES);

    public static Builder builder() {
        return new Builder();
    }

    /** Builder for {@link NetworkConfig}. */
    public static final class Builder {
        private String host = DEFAULT.host;
        private int port = DEFAULT.port;
        private String endpointPath = DEFAULT.endpointPath;
        private Duration readerIdleTimeout = DEFAULT.readerIdleTimeout;
        private Duration writerIdleTimeout = DEFAULT.writerIdleTimeout;
        private int maxContentLength = DEFAULT.maxContentLength;
        private int maxPipelinedRequests = DEFAULT.maxPipelinedRequests;
        private @Nullable List<String> allowedOrigins = DEFAULT.allowedOrigins;
        private boolean allowPrivateNetworks = DEFAULT.allowPrivateNetworks;
        private @Nullable List<String> allowedHeaders = DEFAULT.allowedHeaders;
        private @Nullable List<String> allowedHosts = DEFAULT.allowedHosts;
        private NettyIoEngine ioEngine = DEFAULT.ioEngine;
        private Duration heartbeatInterval = DEFAULT.heartbeatInterval;
        private int maxPendingSseBytes = DEFAULT.maxPendingSseBytes;
        private boolean hostPortExplicitlySet;
        private boolean addressExplicitlySet;

        private Builder() {}

        /** Sets the bind address. Mutually exclusive with {@link #address}. */
        public Builder host(String host) {
            if (addressExplicitlySet) {
                throw new IllegalStateException("Cannot combine host() with address()");
            }
            this.host = host;
            this.hostPortExplicitlySet = true;
            return this;
        }

        /** Sets the listen port. Mutually exclusive with {@link #address}. */
        public Builder port(int port) {
            if (addressExplicitlySet) {
                throw new IllegalStateException("Cannot combine port() with address()");
            }
            this.port = port;
            this.hostPortExplicitlySet = true;
            return this;
        }

        /** Sets the bind address and port from a {@link SocketAddress}. Mutually exclusive with {@link #host}/{@link #port}. */
        public Builder address(SocketAddress addr) {
            if (hostPortExplicitlySet) {
                throw new IllegalStateException("Cannot combine address() with host()/port()");
            }
            if (addr instanceof InetSocketAddress inet) {
                this.host = inet.getHostString();
                this.port = inet.getPort();
            }
            this.addressExplicitlySet = true;
            return this;
        }

        /** Sets the HTTP path for MCP endpoints. */
        public Builder endpointPath(String endpointPath) {
            this.endpointPath = endpointPath;
            return this;
        }

        /** Sets the reader idle timeout. */
        public Builder readerIdleTimeout(Duration timeout) {
            this.readerIdleTimeout = Objects.requireNonNull(timeout, "readerIdleTimeout cannot be null");
            return this;
        }

        /** Sets the writer idle timeout. */
        public Builder writerIdleTimeout(Duration timeout) {
            this.writerIdleTimeout = Objects.requireNonNull(timeout, "writerIdleTimeout cannot be null");
            return this;
        }

        /** Sets the maximum HTTP body size in bytes (must be positive). */
        public Builder maxContentLength(int bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("maxContentLength must be positive");
            }
            this.maxContentLength = bytes;
            return this;
        }

        /**
         * Sets how many HTTP/1.1 pipelined requests may wait behind the one in flight on a connection
         * (default {@value NetworkConfig#DEFAULT_MAX_PIPELINED_REQUESTS}). Responses always follow
         * request order; the request over the limit gets {@code 429 Too Many Requests} once the ones
         * ahead of it are answered, and the connection closes. {@code 0} disables pipelining.
         */
        public Builder maxPipelinedRequests(int requests) {
            if (requests < 0) {
                throw new IllegalArgumentException("maxPipelinedRequests must not be negative");
            }
            this.maxPipelinedRequests = requests;
            return this;
        }

        /**
         * Sets the origins the DNS-rebinding guard admits and CORS grants. Unset grants any loopback
         * origin, on any port. Once set, loopback origins outside the list are still admitted but get
         * no CORS grant. A remote page reaching a non-loopback {@code Host} also needs {@link
         * #allowedHosts(String...)}.
         *
         * <p>Each entry is a serialized origin, {@code http(s)://host[:port]}, as a browser sends it:
         * no path (not even {@code /}), query, fragment or user info; {@code null} and {@code *} are
         * not origins. Entries are canonicalized, so {@code https://App.Example.com:443} matches the
         * browser's {@code https://app.example.com}. The opaque {@code Origin: null} is always
         * rejected: any web page can send it from a sandboxed iframe.
         *
         * @throws IllegalArgumentException at {@link #build()} if an entry is not a serialized origin
         */
        public Builder allowedOrigins(String... origins) {
            this.allowedOrigins = List.of(origins);
            return this;
        }

        /** Sets whether to allow private network CORS. */
        public Builder allowPrivateNetworks(boolean allow) {
            this.allowPrivateNetworks = allow;
            return this;
        }

        /** Sets CORS request headers granted beyond the built-in MCP ones. */
        public Builder allowedHeaders(String... headers) {
            this.allowedHeaders = List.of(headers);
            return this;
        }

        /**
         * Sets additional {@code Host} authorities the DNS-rebinding guard accepts beyond its built-in
         * loopback hosts — e.g. {@code "host.docker.internal:8096"} for a sanctioned server reached
         * over a Docker bridge. Entries match the request's full authority ({@code host} or {@code
         * host:port}) and its host part, case-insensitively.
         */
        public Builder allowedHosts(String... hosts) {
            this.allowedHosts = List.of(hosts);
            return this;
        }

        /** Sets the Netty I/O engine; defaults to {@link NettyIoEngine#AUTO}. */
        public Builder ioEngine(NettyIoEngine ioEngine) {
            this.ioEngine = Objects.requireNonNull(ioEngine, "ioEngine cannot be null");
            return this;
        }

        /**
         * Sets the SSE heartbeat interval for silent listening streams.
         * {@code <= 0} ({@link Duration#ZERO} or negative) disables heartbeats.
         * Default is 15s. Must be below the session TTL (default 30s) to keep a listening
         * client alive out of the box.
         */
        public Builder heartbeatInterval(Duration heartbeatInterval) {
            this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "heartbeatInterval  cannot be null");
            return this;
        }

        /**
         * Sets how much encoded, unsent output one POST-SSE stream may buffer (default {@value
         * NetworkConfig#DEFAULT_MAX_PENDING_SSE_BYTES} bytes, roughly 200 typical progress
         * notifications). Past it, a tool sending progress, log messages or comments waits until
         * the client catches up. {@code 0} disables buffering: a tool waits until each event reaches
         * the socket before sending the next. Raise it (e.g. to 1 MiB) so a fast tool can run
         * further ahead of the client, at the cost of that much memory per slow client until
         * {@code writerIdleTimeout} closes it.
         *
         * <p>A {@code subscriptions/listen} subscriber never waits, nor does a tool writing from a
         * Netty I/O thread (for example, an async tool continuing on a Netty-based HTTP client's
         * callback): the client is disconnected once this limit is full or, when the limit is
         * {@code 0}, once the channel's write high watermark is. The final response is always
         * accepted, and one event larger than the limit may still be sent.
         */
        public Builder maxPendingSseBytes(int bytes) {
            if (bytes < 0) {
                throw new IllegalArgumentException("maxPendingSseBytes must not be negative");
            }
            this.maxPendingSseBytes = bytes;
            return this;
        }

        /** Builds the {@link NetworkConfig}. */
        public NetworkConfig build() {
            return new NetworkConfig(
                    host,
                    port,
                    endpointPath,
                    readerIdleTimeout,
                    writerIdleTimeout,
                    maxContentLength,
                    maxPipelinedRequests,
                    allowedOrigins,
                    allowPrivateNetworks,
                    allowedHeaders,
                    allowedHosts,
                    ioEngine,
                    heartbeatInterval,
                    maxPendingSseBytes);
        }
    }
}
