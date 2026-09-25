/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.AttributeKey;
import dev.tachyonmcp.api.runtime.ClientContext;
import dev.tachyonmcp.api.runtime.ContextNotifications;
import dev.tachyonmcp.api.server.domain.LoggingLevel;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.protocol.ProtocolMappers;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.protocol.ProtocolResponseMapper;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.McpProtocol;
import dev.tachyonmcp.core.runtime.Session;
import dev.tachyonmcp.core.server.OutboundSseStream;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.observability.Observation;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;

@InternalApi
public class NoopInteractionContext implements DispatchContext {

    public static final NoopInteractionContext INSTANCE = new NoopInteractionContext();
    private static final dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.McpRequestMapper REQUEST_MAPPER =
            new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs.McpRequestMapper();
    private static final ProtocolResponseMapper RESPONSE_MAPPER =
            Objects.requireNonNull(ProtocolMappers.getMapper("mcp", McpProtocol.VERSION));
    private static final Protocol PROTOCOL = new McpProtocol();

    @Override
    public Protocol protocol() {
        return PROTOCOL;
    }

    @Override
    public @Nullable Lifecycle lifecycle() {
        return Lifecycle.OPERATION;
    }

    @Override
    public void setLifecycle(Lifecycle lifecycle) {}

    @Override
    public @Nullable Session session() {
        return null;
    }

    @Override
    public void setSession(@Nullable Session session) {}

    @Override
    public void setSecurityContext(SecurityContext securityContext) {}

    @Override
    public <T> Optional<T> get(AttributeKey<T> key) {
        return Optional.empty();
    }

    @Override
    public <T> void set(AttributeKey<T> key, T value) {}

    @Override
    public void enableExtension(String extensionId) {}

    @Override
    public boolean isExtensionEnabled(String extensionId) {
        return false;
    }

    @Override
    public ContextNotifications notifications() {
        throw new UnsupportedOperationException("No interaction context available");
    }

    @Override
    public CompletableFuture<String> sendRequest(String method, Object params) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("sendRequest"));
    }

    @Override
    public ClientContext client() {
        return new WireClientContext(this);
    }

    @Override
    public ServerEngine engine() {
        throw new UnsupportedOperationException("No server available");
    }

    @Override
    public void setLoggingLevel(LoggingLevel level) {
        throw new UnsupportedOperationException("No server available");
    }

    @Override
    public @Nullable LoggingLevel getLoggingLevel() {
        return null;
    }

    @Override
    public void setPermittedLogLevel(@Nullable LoggingLevel level) {}

    @Override
    public @Nullable LoggingLevel getPermittedLogLevel() {
        return null;
    }

    @Override
    public @Nullable RequestId requestId() {
        return null;
    }

    @Override
    public ProtocolResponseMapper responseMapper() {
        return RESPONSE_MAPPER;
    }

    @Override
    public ProtocolRequestMapper requestMapper() {
        return REQUEST_MAPPER;
    }

    @Override
    public @Nullable OutboundSseStream outboundStream() {
        return null;
    }

    @Override
    public void setOutboundStream(@Nullable OutboundSseStream stream) {
        throw new UnsupportedOperationException("No outbound stream available");
    }

    @Override
    public Observation observation() {
        return Observation.NONE;
    }
}
