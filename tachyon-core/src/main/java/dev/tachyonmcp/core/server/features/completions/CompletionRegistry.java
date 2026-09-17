/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.completions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.features.completions.CompletionFn;
import dev.tachyonmcp.api.server.features.completions.Completions;

@InternalApi
public interface CompletionRegistry extends Completions {

    boolean isEmpty();

    /**
     * Registers a derived completion function for a prompt only when that prompt has no handler
     * yet. Used for generated fallbacks (such as enum-constant completion) so an explicitly
     * declared handler — registered by any service, before or after — always wins.
     *
     * @param promptName the prompt name
     * @param fn the fallback completion function
     * @return {@code true} if the function was registered, {@code false} if one was already present
     */
    boolean registerForPromptIfAbsent(String promptName, CompletionFn fn);

    /**
     * Registers a derived completion function for a resource URI or template only when that
     * reference has no handler yet.
     *
     * @param uriOrTemplate the resource URI or template
     * @param fn the fallback completion function
     * @return {@code true} if the function was registered, {@code false} if one was already present
     */
    boolean registerForResourceIfAbsent(String uriOrTemplate, CompletionFn fn);
}
