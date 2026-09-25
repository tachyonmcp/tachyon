/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static dev.tachyonmcp.api.server.security.AuthenticationException.Reason.INVALID_REQUEST;
import static dev.tachyonmcp.api.server.security.AuthenticationException.Reason.INVALID_TOKEN;
import static dev.tachyonmcp.api.server.security.AuthenticationException.Reason.MISSING_CREDENTIALS;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.security.AuthenticationException;
import dev.tachyonmcp.api.server.security.AuthenticationProvider;
import dev.tachyonmcp.api.server.security.BearerTokenVerifier;
import dev.tachyonmcp.api.server.security.SecurityContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authenticates a request by its OAuth 2.1 bearer token (RFC 6750 §2.1), as the MCP authorization
 * spec requires: the token comes from exactly one {@code Authorization: Bearer} header on every
 * request, and never from the query string.
 *
 * <ul>
 *   <li>No {@code Authorization} header, or another scheme such as {@code Basic}: {@link
 *       AuthenticationException.Reason#MISSING_CREDENTIALS}, so the client gets a {@code Bearer}
 *       challenge to start its OAuth flow from.
 *   <li>Several {@code Authorization} headers, or an {@code access_token} query parameter: {@link
 *       AuthenticationException.Reason#INVALID_REQUEST}.
 *   <li>A token that is not RFC 6750 {@code b64token} syntax: {@link
 *       AuthenticationException.Reason#INVALID_TOKEN}, without calling the verifier.
 * </ul>
 */
@ExperimentalApi
public final class BearerTokenAuthenticationProvider implements AuthenticationProvider<HttpRequest> {

    private static final String SCHEME = "Bearer";
    private static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");
    private static final String ACCESS_TOKEN_PARAMETER = "access_token";

    private final BearerTokenVerifier verifier;

    /**
     * @param verifier verifies the extracted token
     */
    public BearerTokenAuthenticationProvider(BearerTokenVerifier verifier) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
    }

    @Override
    public SecurityContext authenticate(HttpRequest request) throws Exception {
        if (new QueryStringDecoder(request.uri()).parameters().containsKey(ACCESS_TOKEN_PARAMETER)) {
            throw new AuthenticationException(INVALID_REQUEST, "Access token in the query string");
        }
        final var authorizations = request.headers().getAll(HttpHeaderNames.AUTHORIZATION);
        if (authorizations.isEmpty()) {
            throw new AuthenticationException(MISSING_CREDENTIALS, "No Authorization header");
        }
        if (authorizations.size() > 1) {
            throw new AuthenticationException(INVALID_REQUEST, "Several Authorization headers");
        }
        final var authorization = authorizations.getFirst().strip();
        if (!isBearer(authorization)) {
            throw new AuthenticationException(MISSING_CREDENTIALS, "Authorization scheme is not Bearer");
        }
        final var token = authorization.substring(SCHEME.length()).strip();
        if (!B64TOKEN.matcher(token).matches()) {
            throw new AuthenticationException(INVALID_TOKEN, "Bearer token is malformed");
        }
        return Objects.requireNonNull(verifier.verify(token), "verifier returned null");
    }

    /** The scheme is case-insensitive (RFC 9110 §11.1); a bare {@code Bearer} is a malformed token. */
    private static boolean isBearer(String authorization) {
        return authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())
                && (authorization.length() == SCHEME.length() || authorization.charAt(SCHEME.length()) == ' ');
    }
}
