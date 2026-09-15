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

    private final TachyonServer server;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile boolean running;

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
}
