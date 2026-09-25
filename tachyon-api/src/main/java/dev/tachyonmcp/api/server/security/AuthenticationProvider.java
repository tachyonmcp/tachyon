/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;

/**
 * Resolves the {@link SecurityContext} of an inbound transport request.
 *
 * <p>Called once per HTTP request, including requests inside a session: a session never stands in
 * for credentials. Runs on the server's handler executor (virtual threads by default), never on a
 * transport I/O thread, so it may block, for example on token introspection. Bound how long such a
 * call may take; a request waits for it.
 *
 * @param <T> the transport-specific request type (a Netty {@code HttpRequest} for the HTTP transport)
 */
@ExperimentalApi
@FunctionalInterface
public interface AuthenticationProvider<T> {

    /**
     * Authenticates {@code request}.
     *
     * @param request the inbound request; valid only during this call
     * @return the caller's context; {@link SecurityContext#anonymous()} admits the request
     *     unauthenticated
     * @throws AuthenticationException to reject the request; see {@link AuthenticationException.Reason}
     *     for the response each reason produces
     * @throws Exception when credentials cannot be checked at all, for example an introspection
     *     endpoint is unreachable; answered with {@code 500} so the client keeps its credentials
     */
    SecurityContext authenticate(T request) throws Exception;
}
