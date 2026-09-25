/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.security;

import java.security.Principal;
import java.util.Set;
import org.jspecify.annotations.Nullable;

enum AnonymousSecurityContext implements SecurityContext {
    INSTANCE;

    @Override
    public @Nullable Principal principal() {
        return null;
    }

    @Override
    public Set<String> scopes() {
        return Set.of();
    }
}
