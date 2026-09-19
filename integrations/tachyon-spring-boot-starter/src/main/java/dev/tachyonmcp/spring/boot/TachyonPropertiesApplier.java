/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.ServerBuilder;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.unit.DataSize;

/**
 * Pushes the nested {@code tachyon.network/session/runtime} groups onto a {@link ServerBuilder}.
 *
 * <p>Only properties the application actually set are applied, so every unset property keeps the
 * default that Tachyon's own configuration record defines. Customizers run afterwards and therefore
 * still win.
 *
 * <p>A value the core builders would reject is rejected here first, as an {@link
 * InvalidConfigurationPropertyValueException}: Boot's analyzer for it names the key and the file and
 * line it came from, where the core speaks in terms of its Java API. Nothing is silently dropped.
 */
final class TachyonPropertiesApplier {

    private static final String SESSION_ENABLED = "tachyon.session.enabled";
    private static final String SESSION_TTL = "tachyon.session.session-ttl";
    private static final String JANITOR_INTERVAL = "tachyon.session.janitor-interval";
    private static final String MAX_CONTENT_LENGTH = "tachyon.network.max-content-length";

    private TachyonPropertiesApplier() {}

    static void apply(TachyonProperties properties, ServerBuilder builder) {
        applyNetwork(properties.network(), builder);
        applySession(properties.session(), builder);
        applyRuntime(properties.runtime(), builder);
    }

    private static void applyNetwork(TachyonProperties.Network network, ServerBuilder builder) {
        builder.network(config -> {
            if (network.endpointPath() != null) config.endpointPath(network.endpointPath());
            if (network.readerIdleTimeout() != null) config.readerIdleTimeout(network.readerIdleTimeout());
            if (network.writerIdleTimeout() != null) config.writerIdleTimeout(network.writerIdleTimeout());
            if (network.heartbeatInterval() != null) config.heartbeatInterval(network.heartbeatInterval());
            if (network.maxContentLength() != null) {
                config.maxContentLength(toPositiveIntBytes(network.maxContentLength()));
            }
            if (network.allowedOrigins() != null) config.allowedOrigins(toArray(network.allowedOrigins()));
            if (network.allowedHeaders() != null) config.allowedHeaders(toArray(network.allowedHeaders()));
            if (network.allowedHosts() != null) config.allowedHosts(toArray(network.allowedHosts()));
            if (network.allowNullOrigin() != null) config.allowNullOrigin(network.allowNullOrigin());
            if (network.allowPrivateNetworks() != null) config.allowPrivateNetworks(network.allowPrivateNetworks());
            if (network.ioEngine() != null) config.ioEngine(network.ioEngine());
        });
    }

    private static void applySession(TachyonProperties.Session session, ServerBuilder builder) {
        if (Boolean.FALSE.equals(session.enabled())) {
            rejectSessionOptions(session);
            builder.stateless();
            return;
        }
        builder.session(config -> {
            if (Boolean.TRUE.equals(session.enabled())) config.enabled();
            if (session.sessionTtl() != null) config.sessionTtl(session.sessionTtl());
            if (session.janitorInterval() != null) config.janitorInterval(session.janitorInterval());
        });
    }

    private static void applyRuntime(TachyonProperties.Runtime runtime, ServerBuilder builder) {
        builder.runtime(config -> {
            if (runtime.shutdownGracePeriod() != null) config.shutdownGracePeriod(runtime.shutdownGracePeriod());
            if (runtime.requestTimeout() != null) config.requestTimeout(runtime.requestTimeout());
        });
    }

    /**
     * A stateless server has no session to configure, so session options beside {@code
     * enabled: false} are a contradiction. Mirrors {@code SessionConfig#SESSION_OPTIONS_REQUIRE_ENABLED}
     * in the keys the application wrote.
     */
    private static void rejectSessionOptions(TachyonProperties.Session session) {
        final var configured = new ArrayList<String>(2);
        if (session.sessionTtl() != null) configured.add(SESSION_TTL);
        if (session.janitorInterval() != null) configured.add(JANITOR_INTERVAL);
        if (configured.isEmpty()) return;

        throw new InvalidConfigurationPropertyValueException(
                SESSION_ENABLED,
                false,
                "Session options are set on a stateless server: %s. Remove them, or set %s to true."
                        .formatted(String.join(", ", configured), SESSION_ENABLED));
    }

    /**
     * The core takes the body limit as a positive {@code int} of bytes. Narrowing without this check
     * fails as an {@code ArithmeticException} naming no property.
     */
    private static int toPositiveIntBytes(DataSize maxContentLength) {
        final var bytes = maxContentLength.toBytes();
        if (bytes <= 0 || bytes > Integer.MAX_VALUE) {
            throw new InvalidConfigurationPropertyValueException(
                    MAX_CONTENT_LENGTH,
                    maxContentLength,
                    "Maximum request body size must be positive and no larger than %d bytes."
                            .formatted(Integer.MAX_VALUE));
        }
        return (int) bytes;
    }

    private static String[] toArray(List<String> values) {
        return values.toArray(String[]::new);
    }
}
