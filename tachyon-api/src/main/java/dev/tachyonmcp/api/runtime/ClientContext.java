/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

import dev.tachyonmcp.api.annotations.LegacyApi;

/**
 * Typed access to the client-facing collaboration channels a handler may invoke:
 * elicitation and sampling round-trips.
 *
 * @see InteractionContext#client()
 */
public interface ClientContext {

    /**
     * Returns the elicitation service for requesting additional information from the user.
     *
     * @return the elicitation service
     */
    ElicitationService elicitation();

    /**
     * Returns the sampling service for requesting an LLM completion from the client.
     *
     * <p>Sampling is deprecated as of protocol version 2026-07-28 (SEP-2577)
     *
     * @return the sampling service
     */
    @LegacyApi
    SamplingService sampling();
}
