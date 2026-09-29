/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.io.Closeable;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Netty-based MCP server with Streamable HTTP transport. Detects the best
 * available I/O transport (io_uring, epoll, kqueue, NIO) via
 * {@link NettyIoEngine#detect()} and binds a {@link ServerBootstrap} with the
 * MCP pipeline defined by {@link McpChannelInitializer}.
 */
public final class NettyServer implements Closeable {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);

    final MultiThreadIoEventLoopGroup eventLoopGroup;
    private final Channel serverChannel;
    private final DefaultChannelGroup childChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final Duration shutdownTimeout;

    /**
     * Returns the port the server is bound to.
     */
    public int port() {
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /**
     * Returns the host the server is bound to.
     */
    public String host() {
        return ((InetSocketAddress) serverChannel.localAddress()).getHostString();
    }

    public NettyServer(int port, ServerEngine server) {
        this(server, NettyServerConfig.defaults(port));
    }

    public NettyServer(ServerEngine server, NettyServerConfig config) {
        shutdownTimeout = server.config().runtime().shutdownGracePeriod();
        var engine = config.ioEngine();
        if (engine == NettyIoEngine.AUTO) {
            engine = NettyIoEngine.detect();
        }

        // Event loops run on PLATFORM threads. Netty I/O loops never voluntarily
        // yield (they spin in epoll_wait / io_uring_enter / Selector.select), and
        // native transports pin via JNI — so virtual threads provide no benefit
        // here and add scheduling cost. Virtual threads are used only for
        // application-level work (see Server#executor()).
        var group = new MultiThreadIoEventLoopGroup(new DefaultThreadFactory("netty-io"), engine.ioHandler());
        Channel channel = null;
        var bound = false;
        try {
            var bootstrap = new ServerBootstrap();
            bootstrap
                    .group(group)
                    .channel(engine.channel())
                    .option(ChannelOption.SO_BACKLOG, 1024)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(32 * 1024, 128 * 1024))
                    .childHandler(new McpChannelInitializer(
                            config.endpointPath(),
                            server.isStateless(),
                            server,
                            config.readerIdleTimeout(),
                            config.writerIdleTimeout(),
                            config.maxContentLength(),
                            config.maxPipelinedRequests(),
                            childChannels,
                            config.corsConfig(),
                            config.allowedHosts(),
                            config.pipelineCustomizer()));

            var bindFuture = bootstrap.bind(config.host(), config.port());
            channel = bindFuture.channel();
            bindFuture.sync();
            bound = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to start Netty server on " + config.host() + ":" + config.port(), e);
        } finally {
            if (!bound) {
                abortStartup(channel, group);
            }
        }

        eventLoopGroup = group;
        serverChannel = channel;
        var version = NettyServer.class.getPackage().getImplementationVersion();
        logger.info(
                "TachyonMCP Server {}(Netty I/O: {}) started on {}",
                version == null ? "" : "v" + version + " ",
                engine,
                channel.localAddress());
    }

    /**
     * Releases what a failed constructor allocated: nothing else holds the group, so
     * {@link #close()} can never reach it. Runs from a {@code finally}, so the startup failure
     * propagates unchanged.
     */
    private void abortStartup(@Nullable Channel channel, MultiThreadIoEventLoopGroup group) {
        if (channel != null && channel.isOpen()) {
            await(channel.close(), "Server channel close");
        }
        await(group.shutdownGracefully(0, shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS), "Event loop shutdown");
    }

    /**
     * Returns whether the calling thread is one of this server's I/O event loops. Shutdown drains
     * in-flight requests by waiting for their responses to flush, which only these threads can do —
     * so a shutdown started from one of them could never make progress.
     */
    public boolean inEventLoop() {
        for (var eventLoop : eventLoopGroup) {
            if (eventLoop.inEventLoop()) return true;
        }
        return false;
    }

    /**
     * Stops accepting new connections by closing the server channel. Existing child channels and
     * event loops stay alive so in-flight requests can complete and flush. Idempotent;
     * {@link #close()} finishes the teardown.
     */
    public void stopAccepting() {
        await(serverChannel.close(), "Server channel close");
    }

    /**
     * Closes every channel, dropping unsent data, and shuts the event loops down. Each wait is
     * bounded and uninterruptible, restoring the interrupt status afterwards, so neither a slow
     * client nor an interrupted caller can keep the transport alive.
     */
    @Override
    public void close() {
        logger.debug("Shutting down TachyonMCP Server");
        await(serverChannel.close(), "Server channel close");
        await(childChannels.close(), "Connection close");
        await(
                eventLoopGroup.shutdownGracefully(0, shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS),
                "Event loop shutdown");
    }

    private void await(Future<?> future, String step) {
        if (!future.awaitUninterruptibly(shutdownTimeout.toMillis())) {
            logger.warn("{} did not finish within {}; continuing", step, shutdownTimeout);
        }
    }
}
