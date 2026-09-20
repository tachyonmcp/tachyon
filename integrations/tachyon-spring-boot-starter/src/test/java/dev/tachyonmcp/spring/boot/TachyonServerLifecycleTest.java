/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.core.server.TachyonServer;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TachyonServerLifecycleTest {

    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger closes = new AtomicInteger();
    private final AtomicBoolean failStart = new AtomicBoolean();
    private final AtomicBoolean failClose = new AtomicBoolean();
    private final TachyonServer server = (TachyonServer) Proxy.newProxyInstance(
            TachyonServer.class.getClassLoader(), new Class<?>[] {TachyonServer.class}, (proxy, method, args) -> {
                switch (method.getName()) {
                    case "start" -> {
                        starts.incrementAndGet();
                        if (failStart.getAndSet(false)) throw new IllegalStateException("bind failed");
                    }
                    case "close" -> {
                        closes.incrementAndGet();
                        if (failClose.getAndSet(false)) throw new IllegalStateException("close failed");
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                }
                return null;
            });

    @Test
    void failedStartLeavesStoppedAndCanRetryOnce() {
        final var lifecycle = new TachyonServerLifecycle(server);
        failStart.set(true);

        assertThatIllegalStateException().isThrownBy(lifecycle::start).withMessage("bind failed");
        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.stop();
        assertThat(closes).hasValue(0);

        lifecycle.start();
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(starts).hasValue(2);
    }

    /**
     * A {@code TachyonServer} is single-use: {@code close()} is terminal and a later {@code start()}
     * throws. Spring's {@code SmartLifecycle} pauses beans by default, so a
     * {@code ConfigurableApplicationContext.pause()}/{@code restart()} cycle — or a CRaC checkpoint —
     * would stop the transport and never bring it back.
     */
    @Test
    void theTransportOptsOutOfContextPause() {
        final var lifecycle = new TachyonServerLifecycle(server);

        assertThat(lifecycle.isPauseable()).isFalse();
        assertThat(lifecycle.getPhase()).isEqualTo(TachyonServerLifecycle.PHASE);
    }

    @Test
    void failedCloseLeavesRunningAndCanRetryOnce() {
        final var lifecycle = new TachyonServerLifecycle(server);
        lifecycle.start();
        failClose.set(true);

        assertThatIllegalStateException().isThrownBy(lifecycle::stop).withMessage("close failed");
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.start();
        assertThat(starts).hasValue(1);

        lifecycle.stop();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(closes).hasValue(2);
    }
}
