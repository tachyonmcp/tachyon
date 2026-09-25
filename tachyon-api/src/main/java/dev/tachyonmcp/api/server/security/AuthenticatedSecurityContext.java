/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import java.security.Principal;
import java.util.Objects;
import java.util.Set;

record AuthenticatedSecurityContext(Principal principal, Set<String> scopes) implements SecurityContext {

    AuthenticatedSecurityContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(scopes, "scopes");
    }
}
