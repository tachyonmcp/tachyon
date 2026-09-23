/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.api.runtime.InteractionContext;
import org.jspecify.annotations.Nullable;

/**
 * Handles a single raw JSON-RPC method owned by a {@link ServerExtension}. Transport-neutral: the
 * handler sees the stable {@link InteractionContext} and a provider-neutral {@link JsonObject},
 * never the underlying transport or server internals.
 *
 * <p>Register with {@link ExtensionContext#registerHandler(String, ExtensionMethodHandler)} from
 * {@link ServerExtension#bootstrap(ExtensionContext)}.
 */
@FunctionalInterface
@ExperimentalApi
public interface ExtensionMethodHandler {

    /**
     * Handles the method and returns the result to serialize as the JSON-RPC response.
     *
     * @param interaction the per-channel interaction view (protocol version, lifecycle, session)
     * @param params the method params as an immutable JSON object, or {@code null} when absent
     * @return the result to serialize with the server's configured serde, or {@code null} for an
     *     empty result
     * @throws Exception on handler failure; an {@link
     *     dev.tachyonmcp.api.server.domain.InvalidArgumentException} surfaces as {@code invalid-params}
     *     with its message, a {@code MissingRequiredClientCapabilityException} as
     *     {@code missing-required-client-capability} with its required capabilities, any other
     *     {@link IllegalArgumentException} as a redacted {@code invalid-params}, everything else as
     *     {@code internal-error}
     */
    @Nullable
    Object handle(InteractionContext interaction, @Nullable JsonObject params) throws Exception;
}
