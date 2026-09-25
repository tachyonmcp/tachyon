/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import static dev.tachyonmcp.api.server.security.AuthenticationException.Reason.INVALID_TOKEN;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.AuthenticationException;
import dev.tachyonmcp.api.server.security.BearerTokenVerifier;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.TachyonServer;
import java.security.Principal;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Bearer verifier over fixed tokens, plus a {@code whoami} tool that echoes the caller. Records the
 * threads verification ran on and every caller the tool saw, so tests prove where authentication ran
 * and whether a rejected request reached dispatch.
 */
public final class TestBearerTokens implements BearerTokenVerifier {

    public static final String ALICE = "alice-token";
    public static final String BOB = "bob-token";
    /** Makes the verifier fail as if its introspection endpoint were down. */
    public static final String UNVERIFIABLE = "unverifiable-token";

    public static final String WHOAMI = "whoami";

    private final Queue<Thread> verifierThreads = new ConcurrentLinkedQueue<>();
    private final Set<String> toolCallers = ConcurrentHashMap.newKeySet();

    private record User(String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    @Override
    public SecurityContext verify(String token) throws AuthenticationException {
        verifierThreads.add(Thread.currentThread());
        return switch (token) {
            case ALICE -> SecurityContext.authenticated(new User("alice"), List.of("tools:read", "tools:call"));
            case BOB -> SecurityContext.authenticated(new User("bob"), List.of("tools:read"));
            case UNVERIFIABLE -> throw new IllegalStateException("Introspection endpoint unavailable");
            default -> throw new AuthenticationException(INVALID_TOKEN, "Unknown token");
        };
    }

    /** Registers {@value #WHOAMI}: answers {@code "<name> <sorted scopes>"}, or {@code "anonymous"}. */
    public void registerWhoAmI(TachyonServer server) {
        server.tools().register(d -> d.name(WHOAMI).description("Echoes the authenticated caller"), (ctx, request) -> {
            final var security = ctx.securityContext();
            final var principal = security.principal();
            final var caller =
                    principal == null ? "anonymous" : principal.getName() + " " + new TreeSet<>(security.scopes());
            toolCallers.add(caller);
            return ToolResult.text(caller);
        });
    }

    public List<Thread> verifierThreads() {
        return List.copyOf(verifierThreads);
    }

    public Set<String> toolCallers() {
        return Set.copyOf(toolCallers);
    }
}
