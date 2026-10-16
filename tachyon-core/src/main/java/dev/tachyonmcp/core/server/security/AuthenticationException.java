/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.io.Serial;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Rejects a request whose credentials are missing, malformed or invalid, and carries the answer: the
 * status and the {@code WWW-Authenticate} challenge. Only this exception rejects credentials: any
 * other exception from an {@link AuthenticationProvider} means they could not be checked (an
 * introspection endpoint is down) and answers {@code 500}, so a client does not discard a token that
 * may be valid.
 *
 * <p>Built by factory methods, which keep status and challenge consistent: a {@code 401} always
 * carries a challenge (RFC 9110 §11.6.1: a server generating a {@code 401} MUST send {@code
 * WWW-Authenticate} with at least one), because {@link #unauthorized} requires one. A {@code 400} may
 * carry one.
 *
 * <p>For a rejected OAuth 2.1 bearer token, use the {@code bearer…} factories, which pair status and
 * error as RFC 6750 §3.1 does. To tell a client that sent no token how to authenticate, and for any
 * other scheme, state your own challenge with {@link #unauthorized}: RFC 6750 §3 has {@code Bearer}
 * followed by at least one parameter, and a {@code bearer…} factory would answer an {@code ApiKey}
 * client with a {@code Bearer} challenge.
 *
 * <p>Unchecked, so it can be thrown wherever a request is served, not only from the provider: from an
 * {@link AccessPolicy}, or from a tool, resource or prompt handler that finds out late that the
 * credentials are no good (a revoked token, say). The server answers it with the status and challenge it
 * carries, as it would from the provider; in bearer-token mode a {@code 401} is answered with the
 * server's own {@code invalid_token} challenge, which tells the client where to find the OAuth
 * metadata, whatever challenge the exception chose. When the response has already started streaming,
 * the status line is gone, so the client gets a JSON-RPC error on the stream instead.
 *
 * <p>The message is logged at debug and never sent to the client. Keep credentials out of it. No cause
 * is kept: nothing logs one, and exceptions of token libraries can carry parts of the token. It carries
 * no stack trace either: a caller can provoke it at will.
 */
@ExperimentalApi
public final class AuthenticationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final AuthenticationChallenge BEARER = AuthenticationChallenge.of("Bearer");
    private static final AuthenticationChallenge BEARER_INVALID_TOKEN = BEARER.withParam("error", "invalid_token");
    private static final AuthenticationChallenge BEARER_INVALID_REQUEST = BEARER.withParam("error", "invalid_request");

    private final int status;
    private final @Nullable String wwwAuthenticate;

    private AuthenticationException(int status, String message, @Nullable AuthenticationChallenge challenge) {
        super(message, null, false, false);
        this.status = status;
        this.wwwAuthenticate = challenge == null ? null : challenge.headerValue();
    }

    /**
     * Rejects a request without acceptable credentials: {@code 401 Unauthorized} with {@code challenge}.
     *
     * @param message diagnostic message; must not contain the credentials
     * @param challenge how the client can authenticate, in your own scheme
     * @return the exception to throw
     */
    public static AuthenticationException unauthorized(String message, AuthenticationChallenge challenge) {
        return new AuthenticationException(401, message, Objects.requireNonNull(challenge, "challenge"));
    }

    /**
     * Rejects a malformed request: {@code 400 Bad Request} with no challenge.
     *
     * @param message diagnostic message; must not contain the credentials
     * @return the exception to throw
     */
    public static AuthenticationException badRequest(String message) {
        return new AuthenticationException(400, message, null);
    }

    /**
     * Rejects a malformed request: {@code 400 Bad Request} with {@code challenge}.
     *
     * @param message diagnostic message; must not contain the credentials
     * @param challenge how the client can authenticate, in your own scheme
     * @return the exception to throw
     */
    public static AuthenticationException badRequest(String message, AuthenticationChallenge challenge) {
        return new AuthenticationException(400, message, Objects.requireNonNull(challenge, "challenge"));
    }

    /**
     * Rejects an expired, revoked or otherwise invalid bearer token: {@code 401} with {@code Bearer
     * error="invalid_token"} (RFC 6750 §3.1). Thrown from a {@link BearerTokenVerifier}, Tachyon
     * answers with its own {@code invalid_token} challenge, which adds the OAuth discovery parameters.
     *
     * @param message diagnostic message; must not contain the credentials
     * @return the exception to throw
     */
    public static AuthenticationException bearerInvalidToken(String message) {
        return new AuthenticationException(401, message, BEARER_INVALID_TOKEN);
    }

    /**
     * Rejects a malformed bearer request: {@code 400} with {@code Bearer error="invalid_request"}
     * (RFC 6750 §3.1).
     *
     * @param message diagnostic message; must not contain the credentials
     * @return the exception to throw
     */
    public static AuthenticationException bearerInvalidRequest(String message) {
        return new AuthenticationException(400, message, BEARER_INVALID_REQUEST);
    }

    /**
     * Returns the HTTP status of the answer.
     *
     * @return {@code 401} or {@code 400}
     */
    public int status() {
        return status;
    }

    /**
     * Returns the {@code WWW-Authenticate} value of the answer.
     *
     * @return the challenge, always present for {@code 401}; {@code null} for a {@code 400} without one
     */
    public @Nullable String wwwAuthenticate() {
        return wwwAuthenticate;
    }
}
