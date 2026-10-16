/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import java.net.URI;
import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link BearerTokenVerifier} found out about a token it accepted: who it is for, what it may
 * do, when it expires and who it was issued to. Tachyon enforces the last two itself, so every part is
 * required and a verifier cannot forget one: it may not hand back a token without saying when it
 * expires or for which audience.
 *
 * @param principal the authenticated identity; its name identifies the caller
 * @param scopes the scopes granted, as plain OAuth scope strings, <b>expanded</b>: when one scope
 *     implies others in your scheme ({@code files:write} implies {@code files:read}, say), include the
 *     implied ones. MCP authorization (2026-07-28 § Step-Up) has servers "account for scope
 *     hierarchies, where a broader scope implies narrower ones"; only the application knows its
 *     hierarchy, and Tachyon compares these strings exactly. Copied
 * @param expiresAt when the token stops being valid. Tachyon rejects a token once it has expired, and
 *     closes the streams the caller opened at this instant. JWT access tokens carry it in {@code exp}
 *     (RFC 9068 §2.2); an introspection response may omit {@code exp} (RFC 7662 §2.2), in which case
 *     report the instant until which you trust your answer
 * @param audience the resources the token was issued for ({@code aud}, RFC 8707). Tachyon accepts the
 *     token only if this includes the server's own resource URI. Leave out values that are not URIs:
 *     they cannot name this server. An empty set is allowed and is never accepted; copied
 * @param issuer who issued the token ({@code iss}), one of the server's authorization servers. With
 *     it, the caller's {@link dev.tachyonmcp.api.server.security.SecurityContext#ownerKey() owner key}
 *     is {@code issuer + "|" + name}, so the same subject from two issuers is two owners. {@code null}
 *     is allowed only on a server with one authorization server, which is then the issuer
 */
@ExperimentalApi
public record VerifiedToken(
        Principal principal,
        Set<String> scopes,
        Instant expiresAt,
        Set<URI> audience,
        @Nullable URI issuer) {

    /** Rejects a missing part and copies the sets. */
    public VerifiedToken {
        Objects.requireNonNull(principal, "principal");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        Objects.requireNonNull(expiresAt, "expiresAt");
        audience = Set.copyOf(Objects.requireNonNull(audience, "audience"));
    }

    /** A token whose issuer is the server's one authorization server. */
    public VerifiedToken(Principal principal, Set<String> scopes, Instant expiresAt, Set<URI> audience) {
        this(principal, scopes, expiresAt, audience, null);
    }
}
