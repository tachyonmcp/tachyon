/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;

/**
 * Verifies an OAuth 2.1 bearer access token (its signature, its issuer, and that it is the kind of
 * token you accept) and reports what it found as a {@link VerifiedToken}. Tachyon extracts the token
 * from the {@code Authorization} header; the verifier never sees the request.
 *
 * <p>Expiry and audience are not yours to check. The MCP authorization spec requires a server to
 * accept only tokens issued for it (RFC 8707); a verifier that forgets is a common hole, so the
 * {@link VerifiedToken} you return must state both, and Tachyon rejects a token that has expired or
 * whose audience does not include this server's resource URI. Report them as the token has them.
 *
 * <p>Report scopes expanded: a token whose scope implies narrower ones ({@code files:write} implying
 * {@code files:read}) is reported with those too, since Tachyon compares scope strings exactly and the
 * hierarchy is yours (see {@link VerifiedToken#scopes()}).
 *
 * <p>Runs on the handler executor, like {@link AuthenticationProvider}.
 */
@ExperimentalApi
@FunctionalInterface
public interface BearerTokenVerifier {

    /**
     * Verifies {@code token}.
     *
     * @param token the access token, without the {@code Bearer} scheme
     * @return the subject, scopes, expiry and audience of the token
     * @throws AuthenticationException to reject the token, usually {@link
     *     AuthenticationException#bearerInvalidToken}. A {@code 401} is answered with the server's own
     *     {@code invalid_token} challenge, which tells the client where to find the server's OAuth
     *     metadata, whatever challenge the exception carries; a {@code 400} is answered as it is
     * @throws Exception when the token cannot be checked at all; answered with {@code 500}
     */
    VerifiedToken verify(String token) throws Exception;
}
