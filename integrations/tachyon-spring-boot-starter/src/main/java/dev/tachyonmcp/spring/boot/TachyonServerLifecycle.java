/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.context.SmartLifecycle;

/**
 * Binds the {@link TachyonServer} transport once the context is refreshed and closes it first on
 * shutdown.
 */
@ExperimentalApi
public final class TachyonServerLifecycle implements SmartLifecycle {

    /**
     * The {@link SmartLifecycle#getPhase() phase} the MCP transport starts and stops in: the highest
     * there is, so it binds last and stops first. Spring's web server sits at
     * {@code DEFAULT_PHASE - 2048}, its graceful shutdown at {@code - 1024}.
     *
     * <p>Give a {@link org.springframework.context.Lifecycle} bean a lower phase to make it outlive
     * in-flight MCP operations; one left on the default phase has no defined order relative to this.
     */
    public static final int PHASE = SmartLifecycle.DEFAULT_PHASE;

    private final TachyonServer server;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean running;
    private volatile boolean everStarted;

    /**
     * Creates the lifecycle for {@code server}.
     *
     * @param server the server to start and stop
     */
    public TachyonServerLifecycle(TachyonServer server) {
        this.server = server;
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (running) return;
            server.start();
            running = true;
            everStarted = true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            if (!running) return;
            server.close();
            running = false;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /**
     * Never. A {@link TachyonServer} is single-use — {@link
     * TachyonServer#close()} is terminal and a later {@link TachyonServer#start()} throws — so the
     * stop/start cycle that {@code ConfigurableApplicationContext.pause()}/{@code restart()} and CRaC
     * checkpoint-restore apply to pauseable beans would leave the transport permanently down. Spring's
     * default is {@code true}; Boot's own web server lifecycles opt out the same way.
     */
    @Override
    public boolean isPauseable() {
        return false;
    }

    /** Whether the transport has ever bound — distinguishes "not started yet" from "stopped". */
    boolean hasStarted() {
        return everStarted;
    }
}
