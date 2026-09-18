/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.session;

import dev.tachyonmcp.api.runtime.InteractionContext;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Derives the session id for a newly initialized session from the incoming {@code initialize}
 * request. Lets operators base the id on request headers (tenant id, auth subject, a
 * client-supplied id, …) or the request URI.
 *
 * <p>Only consulted when sessions are enabled ({@code session(s -> s.enabled())});
 * stateless servers never create a session and never call this.
 *
 * <p>{@code T} is the transport-specific request type handed to {@link #generate}. The HTTP
 * transport passes a Netty {@code HttpRequest}; keep the type variable open (e.g.
 * {@code SessionIdGenerator<? super HttpRequest>}) so a generator stays reusable across
 * transports and does not pin the public API to a concrete request type.
 *
 * <p>Example — derive the id from a tenant header, falling back to a random id:
 *
 * <pre>{@code
 * SessionIdGenerator<HttpRequest> byTenant = (ctx, request) -> {
 *     String tenant = request.headers().get("X-Tenant-Id");
 *     return tenant != null ? "sess_" + tenant + "_" + UUID.randomUUID() : SessionIdGenerator.DEFAULT.generate(ctx, request);
 * };
 *
 * server.session(s -> s.enabled().sessionIdGenerator(byTenant));
 * }</pre>
 *
 * <p><b>A session id is not a credential.</b> It identifies protocol state, not the caller. An
 * application adding authentication must verify the principal even on requests attaching to an
 * existing session, or possession of the id becomes authorization.
 *
 * @param <T> the transport-specific request type (a Netty {@code HttpRequest} for the HTTP
 *     transport); use {@link Object} for a request-independent generator like {@link #DEFAULT}
 * @author Konstantin Pavlov
 */
@FunctionalInterface
public interface SessionIdGenerator<T> {

    /**
     * Default generator: {@code sess_<UUID>}, ignoring the request.
     */
    SessionIdGenerator<Object> DEFAULT = new SessionIdGenerator<>() {
        @Override
        public String generate(InteractionContext channelContext, Object request) {
            return "sess_" + UUID.randomUUID().toString().replace("-", "");
        }

        @Override
        public boolean readsRequest() {
            return false;
        }
    };

    /**
     * Derives a session id from the initialize request (headers, URI, …).
     *
     * <p>Contract for the <em>initialize</em> flow: a {@code null} or blank ({@code ""} or
     * whitespace-only) return value, or any thrown exception, causes the server to respond with
     * an {@code internal-error} and abort session creation.
     *
     * @param channelContext the per-channel interaction (protocol version, lifecycle phase,
     *     attribute scratch space); may be {@code null} in contexts that create a session without
     *     an established channel
     * @param request the incoming initialize request; may be {@code null} when
     *     {@link #readsRequest()} is {@code false}
     * @return the non-blank session id to assign
     */
    String generate(@Nullable InteractionContext channelContext, @Nullable T request);

    /**
     * Whether {@link #generate} inspects the request (headers, URI, …). When {@code false}, the
     * transport skips detaching a per-request snapshot for the async session-creation dispatch.
     *
     * <p>Defaults to {@code true} so every custom generator is handed a valid request; override to
     * {@code false} for a request-independent id (like {@link #DEFAULT}) to opt into the fast path.
     */
    default boolean readsRequest() {
        return true;
    }
}
