/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.ServerBuilder;
import java.util.List;

/**
 * Pushes the nested {@code tachyon.network/session/runtime} groups onto a {@link ServerBuilder}.
 *
 * <p>Only properties the application actually set are applied, so every unset property keeps the
 * default that Tachyon's own configuration record defines. Customizers run afterwards and therefore
 * still win.
 */
final class TachyonPropertiesApplier {

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
                config.maxContentLength(
                        Math.toIntExact(network.maxContentLength().toBytes()));
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
            builder.stateless();
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

    private static String[] toArray(List<String> values) {
        return values.toArray(String[]::new);
    }
}
