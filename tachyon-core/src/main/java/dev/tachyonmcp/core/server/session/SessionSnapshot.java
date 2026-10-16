/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.core.runtime.SessionState;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, transport-free state that can reconstruct one MCP session generation.
 *
 * @param key session generation key
 * @param ownerKey identity of the caller that created the session, or {@code null} for an anonymous
 *     creator. Fixed for the generation: a store must persist and return it unchanged, or a restored
 *     session no longer serves its creator
 * @param state lifecycle state
 * @param protocolVersion negotiated MCP protocol version, or {@code null} before negotiation
 * @param enabledExtensionIds negotiated extension identifiers
 * @param loggingLevel client-selected logging threshold, or {@code null} when unset
 * @param expiresAt wall-clock expiry instant
 * @param revision monotonically increasing snapshot revision within the generation
 */
@ExperimentalApi(since = "1.0.0-beta.26")
public record SessionSnapshot(
        SessionKey key,
        @Nullable String ownerKey,
        SessionState state,
        @Nullable String protocolVersion,
        Set<String> enabledExtensionIds,
        @Nullable LoggingLevel loggingLevel,
        Instant expiresAt,
        long revision) {

    /** Validates and defensively copies snapshot state. */
    public SessionSnapshot {
        key = Objects.requireNonNull(key, "key");
        state = Objects.requireNonNull(state, "state");
        enabledExtensionIds = Set.copyOf(enabledExtensionIds);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (revision < 0) {
            throw new IllegalArgumentException("revision cannot be negative");
        }
    }
}
