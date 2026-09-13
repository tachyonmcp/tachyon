/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import io.netty.channel.IoHandlerFactory;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.ServerSocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * A Netty I/O transport engine — pairs an {@link IoHandlerFactory} with a
 * {@link ServerSocketChannel} class.
 *
 * <p>Native transports ({@link #IO_URING}, {@link #EPOLL}, {@link #KQUEUE}) are resolved via
 * Netty's own {@code isAvailable()} detectors (reflective) so that the
 * {@code netty-transport-classes-*} JARs are needed only at runtime.
 *
 * <p>Use {@link #detect()} for the best available transport, or set an explicit engine.
 */
public enum NettyIoEngine {
    IO_URING(resolve(
            "io.netty.channel.uring.IoUring",
            "io.netty.channel.uring.IoUringIoHandler",
            "io.netty.channel.uring.IoUringServerSocketChannel")),
    EPOLL(resolve(
            "io.netty.channel.epoll.Epoll",
            "io.netty.channel.epoll.EpollIoHandler",
            "io.netty.channel.epoll.EpollServerSocketChannel")),
    KQUEUE(resolve(
            "io.netty.channel.kqueue.KQueue",
            "io.netty.channel.kqueue.KQueueIoHandler",
            "io.netty.channel.kqueue.KQueueServerSocketChannel")),
    NIO(new Resolution(new Resolved(NioIoHandler.newFactory(), NioServerSocketChannel.class), null)),
    AUTO(new Resolution(null, "AUTO is resolved via detect()"));

    private final @Nullable Resolved resolved;
    private final @Nullable String unavailabilityReason;

    NettyIoEngine(Resolution resolution) {
        this.resolved = resolution.resolved;
        this.unavailabilityReason = resolution.reason;
    }

    /**
     * Returns the {@link IoHandlerFactory} for this transport.
     *
     * @throws UnsupportedOperationException if the transport classes are unavailable
     */
    public IoHandlerFactory ioHandler() {
        if (this == AUTO) return detect().ioHandler();
        return require().ioHandler;
    }

    /**
     * Returns the {@link ServerSocketChannel} class for this transport.
     *
     * @throws UnsupportedOperationException if the transport classes are unavailable
     */
    public Class<? extends ServerSocketChannel> channel() {
        if (this == AUTO) return detect().channel();
        return require().channel;
    }

    private Resolved require() {
        if (resolved == null) {
            throw new UnsupportedOperationException(name() + " transport not available: " + unavailabilityReason);
        }
        return resolved;
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    private static final class Detected {
        private static final NettyIoEngine ENGINE = best();

        private static NettyIoEngine best() {
            for (var e : values()) {
                if (e != AUTO && e.resolved != null) {
                    return e;
                }
            }
            return NIO;
        }
    }

    /**
     * Detects the best available transport in priority order:
     * io_uring &gt; epoll &gt; kqueue &gt; NIO.
     *
     * <p>The result is computed once on first call and cached for the lifetime of the JVM.
     */
    public static NettyIoEngine detect() {
        return Detected.ENGINE;
    }

    // -----------------------------------------------------------------------
    // Reflection-based transport resolver
    // -----------------------------------------------------------------------

    private record Resolved(IoHandlerFactory ioHandler, Class<? extends ServerSocketChannel> channel) {}

    private record Resolution(
            @Nullable Resolved resolved, @Nullable String reason) {}

    private static Resolution resolve(String availClass, String factoryClass, String channelClass) {
        try {
            Class<?> avail = Class.forName(availClass);
            if (!(boolean) avail.getMethod("isAvailable").invoke(null)) {
                var cause = avail.getMethod("unavailabilityCause").invoke(null);
                LoggerFactory.getLogger(NettyIoEngine.class).debug("{} unavailable: {}", availClass, cause);
                return new Resolution(null, String.valueOf(cause));
            }
            IoHandlerFactory factory = (IoHandlerFactory)
                    Class.forName(factoryClass).getMethod("newFactory").invoke(null);
            @SuppressWarnings("unchecked")
            Class<? extends ServerSocketChannel> channel =
                    (Class<? extends ServerSocketChannel>) Class.forName(channelClass);
            return new Resolution(new Resolved(factory, channel), null);
        } catch (ReflectiveOperationException | LinkageError e) {
            LoggerFactory.getLogger(NettyIoEngine.class).debug("{} unavailable", availClass, e);
            return new Resolution(null, e.toString());
        }
    }
}
