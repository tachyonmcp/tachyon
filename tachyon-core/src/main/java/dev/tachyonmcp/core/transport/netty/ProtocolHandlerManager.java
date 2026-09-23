/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import dev.tachyonmcp.api.annotations.InternalApi;
import io.netty.channel.ChannelHandler;
import org.jspecify.annotations.Nullable;

@InternalApi
public interface ProtocolHandlerManager {

    String initHandlerName();

    String operationHandlerName();

    ChannelHandler createOperationHandler();

    void onShutdownStarted(@Nullable String sessionId);
}
