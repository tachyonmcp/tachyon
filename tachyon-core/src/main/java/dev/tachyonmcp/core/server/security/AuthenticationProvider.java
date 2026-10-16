/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.security.SecurityContext;

/**
 * Resolves the {@link SecurityContext} of an inbound request.
 *
 * <p>Called once per HTTP request, including requests inside a session: a session never stands in
 * for credentials. It is called on the request head, before the body is requested or read, so the
 * {@link AuthenticationRequest} carries a method, a URI and headers and no body. Runs on the server's
 * handler executor (virtual threads by default), never on a transport I/O thread, so it may block, for
 * example on token introspection. A request waits for it at most the configured authentication timeout
 * (10 seconds by default), then is answered {@code 503 Service Unavailable}; the call is not
 * interrupted and its late result is ignored, so bound such a call yourself.
 *
 * <p>Reject a request with an {@link AuthenticationException}, which also chooses the challenge the
 * client is answered with. A provider for a scheme other than Bearer states its own with {@link
 * AuthenticationException#unauthorized}.
 */
@ExperimentalApi
@FunctionalInterface
public interface AuthenticationProvider {

    /**
     * Authenticates {@code request}.
     *
     * @param request a read-only view of the request head; valid only during this call
     * @return the caller's context; {@link SecurityContext#anonymous()} admits the request
     *     unauthenticated
     * @throws AuthenticationException to reject the request; its status and challenge are the answer
     * @throws Exception when credentials cannot be checked at all, for example an introspection
     *     endpoint is unreachable; answered with {@code 500} so the client keeps its credentials
     */
    SecurityContext authenticate(AuthenticationRequest request) throws Exception;
}
