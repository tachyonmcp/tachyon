/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.core.server.TachyonServer;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Actuator health for the Tachyon transport: {@code UP} with bound host and port while
 * {@link TachyonServerLifecycle} is running, {@code DOWN} otherwise.
 */
@ExperimentalApi
public final class TachyonHealthIndicator implements HealthIndicator {

    private final TachyonServer server;
    private final TachyonServerLifecycle lifecycle;

    /**
     * Creates the indicator.
     *
     * @param server the server whose binding is reported
     * @param lifecycle the lifecycle whose running state decides the status
     */
    public TachyonHealthIndicator(TachyonServer server, TachyonServerLifecycle lifecycle) {
        this.server = server;
        this.lifecycle = lifecycle;
    }

    @Override
    public Health health() {
        if (!lifecycle.isRunning()) return Health.down().build();
        return Health.up()
                .withDetail("host", server.host())
                .withDetail("port", server.port())
                .build();
    }
}
