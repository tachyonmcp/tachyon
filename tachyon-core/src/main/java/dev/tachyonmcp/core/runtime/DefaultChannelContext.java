/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.runtime;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.runtime.AttributeKey;
import dev.tachyonmcp.api.runtime.ClientContext;
import dev.tachyonmcp.api.runtime.ContextNotifications;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.protocol.Protocol;
import dev.tachyonmcp.core.server.session.WireClientContext;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

@InternalApi
public class DefaultChannelContext implements ChannelContext {

    private final Protocol protocol;

    private final Map<AttributeKey<?>, Object> attributes = new ConcurrentHashMap<>(3);
    private final Set<String> enabledExtensions = ConcurrentHashMap.newKeySet();

    private volatile Lifecycle lifecycle = Lifecycle.INITIALIZATION;

    private volatile SecurityContext securityContext = SecurityContext.anonymous();

    private final AtomicReference<@Nullable Session> sessionHolder = new AtomicReference<>();

    public DefaultChannelContext(Protocol protocol) {
        this.protocol = protocol;
    }

    @Override
    public Protocol protocol() {
        return protocol;
    }

    @Override
    public Lifecycle lifecycle() {
        return lifecycle;
    }

    @Override
    public void setLifecycle(Lifecycle lifecycle) {
        this.lifecycle = lifecycle;
    }

    @Override
    @Nullable
    public Session session() {
        return sessionHolder.get();
    }

    @Override
    public void setSession(@Nullable Session session) {
        this.sessionHolder.set(session);
    }

    @Override
    public SecurityContext securityContext() {
        return securityContext;
    }

    @Override
    public void setSecurityContext(SecurityContext securityContext) {
        this.securityContext = Objects.requireNonNull(securityContext, "securityContext");
    }

    @Override
    public void enableExtension(String extensionId) {
        enabledExtensions.add(extensionId);
    }

    @Override
    public boolean isExtensionEnabled(String extensionId) {
        return enabledExtensions.contains(extensionId);
    }

    @Override
    public ContextNotifications notifications() {
        throw new UnsupportedOperationException("notifications are not supported in this context");
    }

    @Override
    public CompletableFuture<String> sendRequest(String method, Object params) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("sendRequest is not supported in this context"));
    }

    @Override
    public ClientContext client() {
        return new WireClientContext(this);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(AttributeKey<T> key) {
        return Optional.ofNullable((T) attributes.get(key));
    }

    @Override
    public <T> void set(AttributeKey<T> key, T value) {
        attributes.put(key, value);
    }
}
