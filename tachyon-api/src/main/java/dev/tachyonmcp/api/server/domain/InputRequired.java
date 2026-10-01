/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Shared shape of an outcome that requests additional input from the caller. Implemented by
 * {@code ToolResult.InputRequired} and {@code PromptResult.InputRequired} so both carry identical
 * elicitation state without duplicating the accessor logic.
 */
public interface InputRequired extends HasMeta {

    /**
     * Returns the requested inputs and opaque state to echo back.
     *
     * @return the input request bundle
     */
    InputRequestBundle request();

    /**
     * Returns the requested inputs, keyed by request id.
     *
     * @return the requested inputs, keyed by request id
     */
    default Map<String, ? extends InputRequest> inputRequests() {
        return request().inputRequests();
    }

    /**
     * Returns the opaque state token to echo back with the caller's response, or {@code null}.
     *
     * @return the opaque state token to echo back with the caller's response, or {@code null}
     */
    default @Nullable String requestState() {
        return request().requestState();
    }
}
