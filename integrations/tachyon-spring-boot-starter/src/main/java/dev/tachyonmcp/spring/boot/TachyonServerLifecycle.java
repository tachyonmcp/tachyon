/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/**
 * Binds the {@link TachyonServer} transport once the context is refreshed and closes it first on
 * shutdown.
 */
@ExperimentalApi
public final class TachyonServerLifecycle implements SmartLifecycle {

    private final TachyonServer server;
    private final AtomicBoolean running = new AtomicBoolean();

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
        if (running.compareAndSet(false, true)) server.start();
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) server.close();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
