/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import io.netty.channel.ChannelHandler;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpHandlerManager implements ProtocolHandlerManager {

    private static final Logger logger = LoggerFactory.getLogger(McpHandlerManager.class);
    static final String HANDLER_INIT = "mcp-phase-init";
    static final String HANDLER_OPS = "mcp-phase-operations";

    private final ServerEngine server;
    private final McpDispatcher dispatcher;
    private final Executor executor;

    public McpHandlerManager(ServerEngine server, McpDispatcher dispatcher) {
        this(server, dispatcher, server.executor());
    }

    public McpHandlerManager(ServerEngine server, McpDispatcher dispatcher, Executor executor) {
        this.server = server;
        this.dispatcher = dispatcher;
        this.executor = executor;
    }

    @Override
    public String initHandlerName() {
        return HANDLER_INIT;
    }

    @Override
    public String operationHandlerName() {
        return HANDLER_OPS;
    }

    @Override
    public ChannelHandler createOperationHandler() {
        return new McpOperationHandler(server, dispatcher, executor);
    }

    @Override
    public void onShutdownStarted(@Nullable String sessionId) {
        if (sessionId != null) {
            try {
                executor.execute(() -> {
                    try {
                        server.removeSession(sessionId);
                    } catch (RuntimeException e) {
                        logger.warn("Session cleanup failed: {}", sessionId, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                logger.debug("Session cleanup rejected during server shutdown: {}", sessionId);
            }
        }
    }
}
