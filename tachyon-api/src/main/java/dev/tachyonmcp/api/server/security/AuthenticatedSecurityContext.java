/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import java.security.Principal;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

record AuthenticatedSecurityContext(
        Principal principal, Set<String> scopes, @Nullable Instant expiresAt) implements SecurityContext {

    AuthenticatedSecurityContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(scopes, "scopes");
    }
}
