/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Shared payload for an MCP {@code input_required} outcome: the pending input requests keyed by
 * name, plus optional opaque continuation state. Values must be non-null; the map is defensively
 * copied and made immutable.
 *
 * @param inputRequests the pending input requests keyed by name
 * @param requestState  optional opaque continuation state
 */
@ExperimentalApi
public record InputRequestBundle(
        Map<String, ? extends InputRequest> inputRequests,
        @Nullable String requestState) {

    /**
     * Creates an input request bundle.
     *
     * @param inputRequests the input requests
     * @param requestState the request state
     */
    public InputRequestBundle {
        Objects.requireNonNull(inputRequests, "inputRequests");
        inputRequests = Map.copyOf(inputRequests);
    }
}
