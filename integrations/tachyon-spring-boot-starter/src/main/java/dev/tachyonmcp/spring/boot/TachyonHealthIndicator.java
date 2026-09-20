/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.TachyonServer;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Actuator health for the Tachyon transport: {@code UP} with bound host and port while
 * {@link TachyonServerLifecycle} is running, {@code DOWN} with {@code state} of {@code not-started}
 * or {@code stopped} otherwise. Reports the lifecycle's state, not the transport's.
 *
 * <p>Extends {@link AbstractHealthIndicator} so a transport that stops between the running check and
 * the host/port read is reported as {@code DOWN} with an {@code error} detail, rather than throwing
 * out of the health endpoint.
 */
@ExperimentalApi
public final class TachyonHealthIndicator extends AbstractHealthIndicator {

    private final TachyonServer server;
    private final TachyonServerLifecycle lifecycle;

    /**
     * Creates the indicator.
     *
     * @param server the server whose binding is reported
     * @param lifecycle the lifecycle whose running state decides the status
     */
    public TachyonHealthIndicator(TachyonServer server, TachyonServerLifecycle lifecycle) {
        super("Tachyon MCP transport health check failed");
        this.server = server;
        this.lifecycle = lifecycle;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        // host()/port() throw until the transport is bound, so they belong to the UP branch only.
        if (!lifecycle.isRunning()) {
            builder.down().withDetail("state", lifecycle.hasStarted() ? "stopped" : "not-started");
            return;
        }
        builder.up().withDetail("host", server.host()).withDetail("port", server.port());
    }
}
