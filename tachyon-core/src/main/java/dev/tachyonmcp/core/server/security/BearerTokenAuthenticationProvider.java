/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.config.BearerConfig;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Authenticates a request by its OAuth 2.1 bearer token (RFC 6750 §2.1), as the MCP authorization
 * spec requires: the token comes from exactly one {@code Authorization: Bearer} header on every
 * request, and never from the query string.
 *
 * <ul>
 *   <li>No {@code Authorization} header, or another scheme such as {@code Basic}: {@code 401} with a
 *       {@code Bearer} challenge, to start the client's OAuth flow from.
 *   <li>Several {@code Authorization} headers, or an {@code access_token} query parameter: {@link
 *       AuthenticationException#bearerInvalidRequest}, a {@code 400}.
 *   <li>A token that is not RFC 6750 {@code b64token} syntax: {@code 401 invalid_token}, without
 *       calling the verifier.
 *   <li>A token the verifier accepts but that has expired, or whose audience does not include this
 *       server's resource URI (RFC 8707): {@code 401 invalid_token}. These two checks are made here, not
 *       by the verifier, so none can forget them. The expiry is judged by the server's clock with no
 *       skew allowance: RFC 7519 §4.1.4 only permits some, and an expired token is expired. The
 *       audience is matched with the normalization RFC 3986 §6.2.3 allows: scheme and host in any case,
 *       a default port and an empty path implied; everything else exactly.
 *   <li>The caller's owner key is {@code issuer + "|" + name}, so the same subject from two issuers is
 *       two owners (sessions and tasks of one are closed to the other). The issuer is what the verifier
 *       reports in {@link VerifiedToken#issuer()}; a server with exactly one authorization server
 *       takes that one when the verifier reports none. On a server with several, a token without an
 *       issuer is a verifier failure ({@code 500}): the collision it would allow is not representable.
 * </ul>
 *
 * <p>Every {@code 401} this provider answers carries the discovery parameters of MCP authorization §
 * Protected Resource Metadata Discovery: {@code resource_metadata}, the URL of the metadata this
 * server serves (RFC 9728 §5.1), and, when the server lists scopes, {@code scope} (§ Scope Selection).
 * They are built once, from the configuration. A {@code 401} the verifier throws is answered with the
 * same {@code invalid_token} challenge, whatever challenge the verifier chose, because this provider
 * owns the {@code Bearer} challenge; a {@code 400} from the verifier passes unchanged. A {@code 400
 * invalid_request} has no discovery parameters: it reports a malformed request, which finding the
 * authorization server does not fix, and RFC 9728 §5.1 attaches {@code resource_metadata} to a
 * challenge for a request that lacked valid credentials.
 */
@ExperimentalApi
public final class BearerTokenAuthenticationProvider implements AuthenticationProvider {

    private static final String SCHEME = "Bearer";
    private static final Pattern B64TOKEN = Pattern.compile("[A-Za-z0-9\\-._~+/]+=*");
    private static final String ACCESS_TOKEN_PARAMETER = "access_token";

    private final String resource;
    private final List<URI> authorizationServers;
    private final BearerTokenVerifier verifier;
    private final Clock clock;
    private final AuthenticationChallenge required;
    private final AuthenticationChallenge invalidToken;
    private final String metadata;

    /**
     * @param bearer the bearer-token configuration: the resource a token's audience must include, the
     *     verifier, and what the challenge announces
     * @param clock tells whether the token has expired
     */
    public BearerTokenAuthenticationProvider(BearerConfig bearer, Clock clock) {
        this.resource = canonical(bearer.resource());
        this.authorizationServers = bearer.authorizationServers();
        this.verifier = bearer.verifier();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metadata = ProtectedResourceMetadata.url(bearer.resource()).toString();
        final var scopes = String.join(" ", bearer.scopesSupported());
        this.required = discovery(AuthenticationChallenge.of(SCHEME), metadata, scopes);
        this.invalidToken =
                discovery(AuthenticationChallenge.of(SCHEME).withParam("error", "invalid_token"), metadata, scopes);
    }

    /**
     * Returns the challenge this server answers {@code rejection} with, whoever threw it: a verifier,
     * an {@link AccessPolicy}, a handler. This provider owns the {@code Bearer} challenge.
     *
     * <ul>
     *   <li>{@code 401}: one of its two, both carrying the discovery parameters. A rejection that
     *       already carries {@code required} (no usable credentials) keeps it; any other, whatever
     *       challenge its thrower chose, is answered with {@code invalid_token}.
     *   <li>{@code 403}: {@code error="insufficient_scope"}, every required scope in one {@code scope}
     *       (RFC 6750 §3.1; MCP authorization § Runtime Insufficient Scope, § Server Scope Management)
     *       and the {@code resource_metadata} URL.
     *   <li>Anything else: what the rejection carries.
     * </ul>
     *
     * @param rejection the rejection
     * @return the {@code WWW-Authenticate} value, or {@code null} for none
     */
    public @Nullable String challengeFor(AuthenticationException rejection) {
        return switch (rejection.status()) {
            case 401 ->
                (required.headerValue().equals(rejection.wwwAuthenticate()) ? required : invalidToken).headerValue();
            case 403 ->
                AuthenticationChallenge.of(SCHEME)
                        .withParam("error", "insufficient_scope")
                        .withParam("scope", String.join(" ", rejection.requiredScopes()))
                        .withParam("resource_metadata", metadata)
                        .headerValue();
            default -> rejection.wwwAuthenticate();
        };
    }

    private static AuthenticationChallenge discovery(AuthenticationChallenge base, String metadata, String scopes) {
        final var located = base.withParam("resource_metadata", metadata);
        return scopes.isEmpty() ? located : located.withParam("scope", scopes);
    }

    @Override
    public SecurityContext authenticate(AuthenticationRequest request) throws Exception {
        if (new QueryStringDecoder(request.uri()).parameters().containsKey(ACCESS_TOKEN_PARAMETER)) {
            throw AuthenticationException.bearerInvalidRequest("Access token in the query string");
        }
        final var authorizations = request.headers("Authorization");
        if (authorizations.isEmpty()) {
            throw AuthenticationException.unauthorized("No Authorization header", required);
        }
        if (authorizations.size() > 1) {
            throw AuthenticationException.bearerInvalidRequest("Several Authorization headers");
        }
        final var authorization = authorizations.getFirst().strip();
        if (!isBearer(authorization)) {
            throw AuthenticationException.unauthorized("Authorization scheme is not Bearer", required);
        }
        final var token = authorization.substring(SCHEME.length()).strip();
        if (!B64TOKEN.matcher(token).matches()) {
            throw AuthenticationException.unauthorized("Bearer token is malformed", invalidToken);
        }
        final VerifiedToken verified;
        try {
            verified = Objects.requireNonNull(verifier.verify(token), "verifier returned null");
        } catch (AuthenticationException rejection) {
            if (rejection.status() == 401) {
                throw AuthenticationException.unauthorized(rejection.getMessage(), invalidToken);
            }
            throw rejection;
        }
        if (verified.audience().stream()
                .map(BearerTokenAuthenticationProvider::canonical)
                .noneMatch(resource::equals)) {
            throw AuthenticationException.unauthorized("Token audience does not include this server", invalidToken);
        }
        if (!verified.expiresAt().isAfter(clock.instant())) {
            throw AuthenticationException.unauthorized("Token has expired", invalidToken);
        }
        return SecurityContext.authenticated(
                verified.principal(), verified.scopes(), verified.expiresAt(), ownerKey(verified));
    }

    private String ownerKey(VerifiedToken verified) {
        var issuer = verified.issuer();
        if (issuer == null) {
            if (authorizationServers.size() != 1) {
                throw new IllegalStateException(
                        "The verifier must report the issuer: this server has several authorization servers");
            }
            issuer = authorizationServers.getFirst();
        }
        return issuer + "|" + verified.principal().getName();
    }

    /**
     * RFC 3986 §6.2.3 scheme-based normalization of a URI's text: lower-case scheme and host, no
     * default port, an empty path read as {@code /}. User info is kept as written, so {@code
     * https://alice@h/mcp} is another resource than {@code https://h/mcp}. A URI with no scheme or
     * host is compared as written.
     */
    private static String canonical(URI uri) {
        final var scheme = uri.getScheme();
        final var host = uri.getHost();
        if (scheme == null || host == null) {
            return uri.toString();
        }
        final var lowerScheme = scheme.toLowerCase(Locale.ROOT);
        final var port = uri.getPort();
        final var defaultPort = lowerScheme.equals("https") ? 443 : lowerScheme.equals("http") ? 80 : -1;
        final var path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        final var text = new StringBuilder(lowerScheme).append("://");
        if (uri.getRawUserInfo() != null) {
            text.append(uri.getRawUserInfo()).append('@');
        }
        text.append(host.toLowerCase(Locale.ROOT));
        if (port != -1 && port != defaultPort) {
            text.append(':').append(port);
        }
        text.append(path);
        if (uri.getRawQuery() != null) {
            text.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            text.append('#').append(uri.getRawFragment());
        }
        return text.toString();
    }

    /** The scheme is case-insensitive (RFC 9110 §11.1); a bare {@code Bearer} is a malformed token. */
    private static boolean isBearer(String authorization) {
        return authorization.regionMatches(true, 0, SCHEME, 0, SCHEME.length())
                && (authorization.length() == SCHEME.length() || authorization.charAt(SCHEME.length()) == ' ');
    }
}
