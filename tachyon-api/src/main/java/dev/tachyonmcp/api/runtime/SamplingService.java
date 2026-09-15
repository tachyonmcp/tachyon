/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.runtime;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.LegacyApi;
import dev.tachyonmcp.api.server.domain.Args;
import java.util.concurrent.CompletableFuture;

/**
 * Requests an LLM completion from the client ({@code sampling/createMessage}).
 *
 * <p>Sampling was deprecated in MCP protocol version 2026-07-28 (SEP-2577) and may be removed from
 * a future protocol revision. Request and response are kept as raw {@link Args} rather than a
 * per-field domain model, since the wire shape isn't a stable long-term contract to model against.
 *
 * <p>SEP-2577 explicitly deprecates sampling.
 * @see ClientContext#sampling()
 */
@ExperimentalApi
@FunctionalInterface
@LegacyApi
public interface SamplingService {

    /**
     * Sends a {@code sampling/createMessage} request to the client and returns a future completed
     * with the raw result.
     *
     * @param params the request parameters, matching {@code CreateMessageRequestParams}
     * @return a future that completes with the client's result
     */
    CompletableFuture<Args> createMessage(Args params);
}
