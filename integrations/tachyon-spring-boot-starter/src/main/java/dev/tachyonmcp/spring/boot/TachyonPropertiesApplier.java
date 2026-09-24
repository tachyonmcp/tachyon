/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.transport.netty.http.Origins;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.PropertyMapper;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * Pushes {@code tachyon.*} properties onto a {@link ServerBuilder}.
 *
 * <p>Only properties the application actually set are applied, so every unset property keeps the
 * default that Tachyon's own configuration record defines. {@link PropertyMapper} drops {@code null}
 * sources by default, which is exactly that rule. Customizers run afterwards and therefore still win.
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
    private static final String MAX_PIPELINED_REQUESTS = "tachyon.network.max-pipelined-requests";
    private static final String MAX_PENDING_SSE_BYTES = "tachyon.network.max-pending-sse-bytes";
    private static final String ALLOWED_ORIGINS = "tachyon.network.allowed-origins";

    private static final PropertyMapper MAP = PropertyMapper.get();

    private TachyonPropertiesApplier() {}

    static void apply(TachyonProperties properties, ServerBuilder builder) {
        builder.port(properties.port());
        MAP.from(properties.name()).to(builder::name);
        MAP.from(properties.version()).to(builder::version);
        MAP.from(properties.host()).to(builder::host);
        applyNetwork(properties.network(), builder);
        applySession(properties.session(), builder);
        applyRuntime(properties.runtime(), builder);
    }

    private static void applyNetwork(TachyonProperties.Network network, ServerBuilder builder) {
        builder.network(config -> {
            MAP.from(network.endpointPath()).to(config::endpointPath);
            MAP.from(network.readerIdleTimeout()).to(config::readerIdleTimeout);
            MAP.from(network.writerIdleTimeout()).to(config::writerIdleTimeout);
            MAP.from(network.heartbeatInterval()).to(config::heartbeatInterval);
            MAP.from(network.maxContentLength())
                    .as(size -> toIntBytes(MAX_CONTENT_LENGTH, size, 1))
                    .to(config::maxContentLength);
            MAP.from(network.maxPendingSseBytes())
                    .as(size -> toIntBytes(MAX_PENDING_SSE_BYTES, size, 0))
                    .to(config::maxPendingSseBytes);
            MAP.from(network.maxPipelinedRequests())
                    .as(TachyonPropertiesApplier::toNonNegativeRequests)
                    .to(config::maxPipelinedRequests);
            MAP.from(network.allowedOrigins())
                    .as(TachyonPropertiesApplier::toServedOrigins)
                    .to(config::allowedOrigins);
            MAP.from(network.allowedHeaders()).as(StringUtils::toStringArray).to(config::allowedHeaders);
            MAP.from(network.allowedHosts()).as(StringUtils::toStringArray).to(config::allowedHosts);
            MAP.from(network.allowPrivateNetworks()).to(config::allowPrivateNetworks);
            MAP.from(network.ioEngine()).to(config::ioEngine);
        });
    }

    private static void applySession(TachyonProperties.Session session, ServerBuilder builder) {
        if (Boolean.FALSE.equals(session.enabled())) {
            rejectSessionOptions(session);
            builder.stateless();
            return;
        }
        builder.session(config -> {
            MAP.from(session.enabled()).whenTrue().toCall(config::enabled);
            MAP.from(session.sessionTtl()).to(config::sessionTtl);
            MAP.from(session.janitorInterval()).to(config::janitorInterval);
        });
    }

    private static void applyRuntime(TachyonProperties.Runtime runtime, ServerBuilder builder) {
        builder.runtime(config -> {
            MAP.from(runtime.shutdownGracePeriod()).to(config::shutdownGracePeriod);
            MAP.from(runtime.requestTimeout()).to(config::requestTimeout);
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
     * Each allowed origin must be a serialized origin, {@code http(s)://host[:port]} with no path, as
     * the core requires. A trailing {@code /}, {@code *} or {@code null} is refused by name here.
     */
    private static String[] toServedOrigins(List<String> origins) {
        for (final var origin : origins) {
            try {
                Origins.requireConfigured(origin);
            } catch (IllegalArgumentException e) {
                throw new InvalidConfigurationPropertyValueException(
                        ALLOWED_ORIGINS,
                        origin,
                        "Each allowed origin must be a serialized origin, http(s)://host[:port] with no path,"
                                + " as a browser sends it in the Origin header.");
            }
        }
        return StringUtils.toStringArray(origins);
    }

    /**
     * The core takes byte limits as an {@code int} of at least {@code min}. Narrowing without this
     * check fails as an {@code ArithmeticException} naming no property, and {@code Source#asInt}
     * would truncate silently.
     */
    private static int toIntBytes(String property, DataSize size, int min) {
        final var bytes = size.toBytes();
        if (bytes < min || bytes > Integer.MAX_VALUE) {
            throw new InvalidConfigurationPropertyValueException(
                    property, size, "Size must be between %d and %d bytes.".formatted(min, Integer.MAX_VALUE));
        }
        return (int) bytes;
    }

    private static int toNonNegativeRequests(Integer maxPipelinedRequests) {
        if (maxPipelinedRequests < 0) {
            throw new InvalidConfigurationPropertyValueException(
                    MAX_PIPELINED_REQUESTS,
                    maxPipelinedRequests,
                    "Pipelined request limit must not be negative; 0 disables pipelining.");
        }
        return maxPipelinedRequests;
    }
}
