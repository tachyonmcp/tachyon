/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;

/**
 * Verifies an OAuth 2.1 bearer access token (signature, expiry, issuer and audience) and maps it to
 * a {@link SecurityContext}. Tachyon extracts the token from the {@code Authorization} header; the
 * verifier never sees the request.
 *
 * <p>The MCP authorization spec requires the audience check: accept only tokens issued for this
 * server (RFC 8707). Runs on the handler executor, like {@link AuthenticationProvider}.
 */
@ExperimentalApi
@FunctionalInterface
public interface BearerTokenVerifier {

    /**
     * Verifies {@code token}.
     *
     * @param token the access token, without the {@code Bearer} scheme
     * @return the context of the token's subject
     * @throws AuthenticationException to reject the token, usually {@link
     *     AuthenticationException.Reason#INVALID_TOKEN}
     * @throws Exception when the token cannot be checked at all; answered with {@code 500}
     */
    SecurityContext verify(String token) throws Exception;
}
