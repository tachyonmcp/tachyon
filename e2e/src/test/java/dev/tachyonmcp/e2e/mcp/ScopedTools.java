/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tools that declare the scopes they require, and record every time one actually ran, so a test can
 * prove a refused call never reached its handler. {@link TestBearerTokens#ALICE} holds {@code
 * tools:read} and {@code tools:call}, {@link TestBearerTokens#BOB} only {@code tools:read}.
 */
public final class ScopedTools {

    /** Requires {@code tools:call}. */
    public static final String WRITE = "write";

    /** Requires {@code tools:call} and {@code admin:audit}, which nobody in the fixture holds. */
    public static final String AUDIT = "audit";

    /** Requires {@code tools:call}, and starts its SSE stream before it does anything else. */
    public static final String STREAM = "stream";

    private final Queue<String> ran = new ConcurrentLinkedQueue<>();

    /** Registers {@link #WRITE}, {@link #AUDIT} and {@link #STREAM}. */
    public void register(TachyonServer server) {
        server.tools().register(d -> d.name(WRITE).requiredScopes("tools:call"), (ctx, request) -> {
            ran.add(WRITE);
            return ToolResult.text("written");
        });
        server.tools().register(d -> d.name(AUDIT).requiredScopes("tools:call", "admin:audit"), (ctx, request) -> {
            ran.add(AUDIT);
            return ToolResult.text("audited");
        });
        server.tools().register(d -> d.name(STREAM).requiredScopes("tools:call"), (ctx, request) -> {
            ran.add(STREAM);
            final var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
            if (stream != null) {
                stream.start();
            }
            return ToolResult.text("streamed");
        });
    }

    /** The tools that ran, in order. */
    public List<String> ran() {
        return List.copyOf(ran);
    }
}
