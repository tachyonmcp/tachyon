/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tools;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.runtime.InteractionContext;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Experimental base class for class-based tool handlers.
 *
 * <p>Prefer {@link Tools#register(ToolDescriptor, ToolFn)} or {@link
 * Tools#registerAsync(ToolDescriptor, AsyncToolFn)}. Override exactly one of {@link
 * #handle(InteractionContext, ToolRequest)} or {@link #handleAsync(InteractionContext,
 * ToolRequest)}.
 */
@ExperimentalApi
public abstract class AbstractToolHandler implements ToolHandler {

    private final ToolDescriptor descriptor;

    /**
     * Creates a handler for the given descriptor.
     *
     * @param descriptor the tool descriptor
     */
    protected AbstractToolHandler(ToolDescriptor descriptor) {
        this.descriptor = Objects.requireNonNull(descriptor, "ToolDescriptor must not be null");
    }

    /**
     * Returns the tool descriptor.
     *
     * @return the tool descriptor
     */
    @Override
    public final ToolDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Handles a tool request asynchronously.
     *
     * <p>The default implementation delegates to {@link #handle(InteractionContext, ToolRequest)}.
     *
     * @param context the interaction context
     * @param request the tool request
     * @return the pending tool result
     */
    @Override
    public CompletionStage<? extends ToolResult> handleAsync(InteractionContext context, ToolRequest request) {
        try {
            return CompletableFuture.completedStage(handle(context, request));
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Handles a tool request synchronously.
     *
     * @param context the interaction context
     * @param request the tool request
     * @return the tool result
     * @throws Exception when handling fails
     */
    public ToolResult handle(InteractionContext context, ToolRequest request) throws Exception {
        // don't remove this guardrail!
        assert Thread.currentThread().isVirtual() : "Handler MUST run on virtual thread";
        throw new UnsupportedOperationException("Override handle or handleAsync");
    }
}
