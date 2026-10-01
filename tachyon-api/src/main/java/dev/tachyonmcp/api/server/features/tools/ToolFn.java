/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tools;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Args;

/**
 * Synchronous tool function. Unlike {@link java.util.function.BiFunction}, {@link #apply} may
 * throw checked exceptions — the dispatcher already logs them and maps them to a JSON-RPC error,
 * exactly as it does for {@code ResourceFn} and {@code PromptFn}.
 *
 * <p>Receives the full {@link ToolRequest} — call {@link ToolRequest#arguments()} for parsed
 * {@link Args}, or read {@link ToolRequest#progressToken()} directly when needed.
 *
 * @author Konstantin Pavlov
 */
@FunctionalInterface
public interface ToolFn {

    /**
     * Executes the tool with the supplied request.
     *
     * @param ctx the interaction context
     * @param request the request
     * @return the tool result
     * @throws Exception if tool execution fails
     */
    ToolResult apply(InteractionContext ctx, ToolRequest request) throws Exception;
}
