/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.BearerConfig;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.BearerTokenVerifier;
import dev.tachyonmcp.core.server.security.VerifiedToken;
import java.net.URI;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Bearer verifier over fixed tokens, plus a {@code whoami} tool that echoes the caller. Records the
 * threads verification ran on and every caller the tool saw, so tests prove where authentication ran
 * and whether a rejected request reached dispatch.
 *
 * <p>Like a real verifier it reports what each token says and leaves the decision to Tachyon: the
 * expiry and the audience of the three odd tokens below are exactly what a verifier that forgot to
 * check them would let through.
 */
public final class TestBearerTokens implements BearerTokenVerifier {

    /** The canonical URI the test servers are configured with; the audience of every good token. */
    public static final URI RESOURCE = URI.create("https://mcp.example.test/mcp");

    public static final URI AUTHORIZATION_SERVER = URI.create("https://auth.example.test");

    public static final List<String> SCOPES = List.of("tools:read", "tools:call");

    /** Where the test servers publish their Protected Resource Metadata: {@link #RESOURCE} path-inserted. */
    public static final String METADATA_URL = "https://mcp.example.test/.well-known/oauth-protected-resource/mcp";

    /** The challenge of a request with no usable credentials. */
    public static final String CHALLENGE =
            "Bearer resource_metadata=\"" + METADATA_URL + "\", scope=\"tools:read tools:call\"";

    /** The challenge of a token that is present but not good. */
    public static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\", resource_metadata=\""
            + METADATA_URL + "\", scope=\"tools:read tools:call\"";

    public static final String ALICE = "alice-token";
    public static final String BOB = "bob-token";
    /** Verifies to a {@link Tenant}: a {@link Principal} that carries a claim of its own. */
    public static final String CAROL = "carol-token";
    /** A valid token of {@code short}, expiring {@link #SHORT_LIFE} after each verification. */
    public static final String SHORT_LIVED = "short-lived-token";
    /** Issued for another server. */
    public static final String WRONG_AUDIENCE = "wrong-audience-token";
    /** Issued for no one: the audience is empty. */
    public static final String NO_AUDIENCE = "no-audience-token";
    /** Valid in every way except that it expired a minute ago. */
    public static final String EXPIRED = "expired-token";
    /** Makes the verifier fail as if its introspection endpoint were down. */
    public static final String UNVERIFIABLE = "unverifiable-token";
    /** Verifies to an authenticated principal whose name is {@code null}, which a provider must never return. */
    public static final String NAMELESS = "nameless-token";

    public static final Duration SHORT_LIFE = Duration.ofSeconds(2);

    public static final String WHOAMI = "whoami";

    private final Queue<Thread> verifierThreads = new ConcurrentLinkedQueue<>();
    private final Set<String> toolCallers = ConcurrentHashMap.newKeySet();
    private volatile @Nullable Instant lastShortLivedExpiry;

    private record User(@Nullable String name) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    /** A principal with a verified claim beyond its name, as a user's own {@link Principal} subtype would have. */
    public record Tenant(String name, String tenant) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    /** Configures a server to require bearer tokens for {@link #RESOURCE}, verified by {@code verifier}. */
    public static Consumer<BearerConfig.Builder> bearer(BearerTokenVerifier verifier) {
        return b -> b.resource(RESOURCE)
                .authorizationServers(AUTHORIZATION_SERVER)
                .scopesSupported(SCOPES.toArray(String[]::new))
                .verifier(verifier);
    }

    /**
     * Secures a server with {@link #bearer} and lets every authenticated caller see every feature: a
     * server that authenticates must say so explicitly.
     */
    public static Consumer<SecurityConfig.Builder> secured(BearerTokenVerifier verifier) {
        return s -> s.bearerToken(bearer(verifier)).accessPolicy(AccessPolicy.permitAll());
    }

    /** The configuration {@link #bearer} applies, for a provider built outside the server builder. */
    public static BearerConfig bearerConfig(BearerTokenVerifier verifier) {
        final var builder = BearerConfig.builder();
        bearer(verifier).accept(builder);
        return builder.build();
    }

    private static VerifiedToken valid(String name, List<String> scopes) {
        return new VerifiedToken(
                new User(name), Set.copyOf(scopes), Instant.now().plus(Duration.ofHours(1)), Set.of(RESOURCE));
    }

    @Override
    public VerifiedToken verify(String token) throws AuthenticationException {
        verifierThreads.add(Thread.currentThread());
        return switch (token) {
            case ALICE -> valid("alice", List.of("tools:read", "tools:call"));
            case BOB -> valid("bob", List.of("tools:read"));
            case CAROL ->
                new VerifiedToken(
                        new Tenant("carol", "acme"),
                        Set.of("tools:read"),
                        Instant.now().plus(Duration.ofHours(1)),
                        Set.of(RESOURCE));
            case SHORT_LIVED -> {
                final var expiresAt = Instant.now().plus(SHORT_LIFE);
                lastShortLivedExpiry = expiresAt;
                yield new VerifiedToken(new User("short"), Set.of("tools:read"), expiresAt, Set.of(RESOURCE));
            }
            case WRONG_AUDIENCE ->
                new VerifiedToken(
                        new User("mallory"),
                        Set.of(),
                        Instant.now().plus(Duration.ofHours(1)),
                        Set.of(URI.create("https://other.example.test/mcp")));
            case NO_AUDIENCE ->
                new VerifiedToken(new User("mallory"), Set.of(), Instant.now().plus(Duration.ofHours(1)), Set.of());
            case EXPIRED ->
                new VerifiedToken(
                        new User("alice"), Set.of(), Instant.now().minus(Duration.ofMinutes(1)), Set.of(RESOURCE));
            case NAMELESS ->
                new VerifiedToken(new User(null), Set.of(), Instant.now().plus(Duration.ofHours(1)), Set.of(RESOURCE));
            case UNVERIFIABLE -> throw new IllegalStateException("Introspection endpoint unavailable");
            default -> throw AuthenticationException.bearerInvalidToken("Unknown token");
        };
    }

    /** Registers {@value #WHOAMI}: answers {@code "<name> <sorted scopes>"}, or {@code "anonymous"}. */
    public void registerWhoAmI(TachyonServer server) {
        server.tools().register(d -> d.name(WHOAMI).description("Echoes the authenticated caller"), (ctx, request) -> {
            final var security = ctx.securityContext();
            final var principal = security.principal();
            final var claim = principal instanceof Tenant tenant ? " tenant=" + tenant.tenant() : "";
            final var caller = principal == null
                    ? "anonymous"
                    : principal.getName() + " " + new TreeSet<>(security.scopes()) + claim;
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

    /** When the most recent verification of {@link #SHORT_LIVED} said the token expires. */
    public Instant lastShortLivedExpiry() {
        return Objects.requireNonNull(lastShortLivedExpiry, "no short-lived token was verified");
    }
}
