/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.OutboundSseStreamMessageRouter;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.BearerTokenVerifier;
import dev.tachyonmcp.core.server.security.VerifiedToken;
import java.security.Principal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.jspecify.annotations.Nullable;

/**
 * A bearer-secured server with an {@link AccessPolicy} that hides features by role and by client, and
 * features of every kind to hide: tools, a resource, a resource template, a prompt and completions.
 * Roles and the client id are resolved by the verifier and travel on the {@link Caller} principal, as
 * the policy's contract asks.
 *
 * <ul>
 *   <li>{@code admin-*} features: only {@link #ADMIN}.
 *   <li>{@link #PARTNER_TOOL}: only a token issued to {@link #ALLOWED_CLIENT}, a Client ID Metadata
 *       Document URL.
 *   <li>{@link #READ_FILES}: listed for everyone, requires {@code files:read}; {@link #WRITER}'s token
 *       says {@code files:write}, which the verifier expands to the {@code files:read} it implies.
 * </ul>
 */
public final class GuardedFeatures implements BearerTokenVerifier {

    public static final String ADMIN = "admin-token";
    public static final String USER = "user-token";
    public static final String WRITER = "writer-token";
    public static final String PARTNER = "partner-token";
    public static final String STRANGER = "stranger-token";

    public static final String ALLOWED_CLIENT = "https://partner.example.test/client.json";

    public static final String PUBLIC_TOOL = "public-tool";
    public static final String ADMIN_TOOL = "admin-tool";
    public static final String PARTNER_TOOL = "partner-tool";
    public static final String READ_FILES = "read-files";
    /** Starts its event stream, then finds out the credentials are no good. */
    public static final String LATE_REFUSAL = "late-refusal";
    /** Reports an update of {@link #ADMIN_NOTES} and of {@link #PUBLIC_NOTES}, in that order. */
    public static final String NOTIFY = "notify";
    /** Finds out the credentials are no good before it answers anything. */
    public static final String EARLY_REFUSAL = "early-refusal";

    public static final String PUBLIC_NOTES = "notes://public";
    public static final String ADMIN_NOTES = "notes://admin";
    public static final String ADMIN_TEMPLATE = "secret://{id}";
    public static final String ADMIN_PROMPT = "admin-prompt";
    public static final String PUBLIC_PROMPT = "public-prompt";

    /** The verified caller: who, and which client the token was issued to. */
    public record Caller(String name, @Nullable String clientId) implements Principal {
        @Override
        public String getName() {
            return name;
        }
    }

    /** How the policy fails, when a test wants it to. */
    public enum Sabotage {
        NONE,
        THROWS,
        REJECTS
    }

    private final Queue<String> ran = new ConcurrentLinkedQueue<>();
    private final Queue<Recorded> observed = new ConcurrentLinkedQueue<>();
    private volatile Sabotage sabotage = Sabotage.NONE;

    /** An operation an observer was told about. */
    public record Recorded(OperationInfo info, OperationOutcome outcome) {}

    /** Secures {@code builder} with this verifier, the policy below and a recording observer. */
    public void configure(ServerBuilder builder) {
        builder.capabilities(c -> c.tools().resources(true, false).prompts().completions())
                .security(s -> s.bearerToken(TestBearerTokens.bearer(this)).accessPolicy(this::permits))
                .observability(o -> o.listener(new ObservationListener() {
                    @Override
                    public ObservationScope start(OperationInfo info) {
                        return ObservationScope.NOOP;
                    }

                    @Override
                    public void complete(OperationInfo info, OperationOutcome outcome) {
                        observed.add(new Recorded(info, outcome));
                    }
                }));
    }

    private boolean permits(SecurityContext caller, ServerFeature.Descriptor feature) {
        switch (sabotage) {
            case THROWS -> throw new IllegalStateException("policy bug");
            case REJECTS -> throw AuthenticationException.bearerInvalidToken("revoked while serving");
            case NONE -> {}
        }
        final var principal = caller.principal() instanceof Caller c ? c : null;
        if (feature.name().startsWith("admin")) {
            return principal != null && principal.name().equals("admin");
        }
        if (feature.name().equals(PARTNER_TOOL)) {
            return principal != null && ALLOWED_CLIENT.equals(principal.clientId());
        }
        return true;
    }

    @Override
    public VerifiedToken verify(String token) {
        return switch (token) {
            case ADMIN -> valid(new Caller("admin", null), Set.of());
            case USER -> valid(new Caller("user", null), Set.of());
            // The token grants files:write; this application's hierarchy says it implies files:read.
            case WRITER -> valid(new Caller("writer", null), Set.of("files:write", "files:read"));
            case PARTNER -> valid(new Caller("partner", ALLOWED_CLIENT), Set.of());
            case STRANGER -> valid(new Caller("stranger", "https://elsewhere.example.test/client.json"), Set.of());
            default -> throw AuthenticationException.bearerInvalidToken("Unknown token");
        };
    }

    private static VerifiedToken valid(Caller caller, Set<String> scopes) {
        return new VerifiedToken(
                caller, scopes, Instant.now().plus(Duration.ofHours(1)), Set.of(TestBearerTokens.RESOURCE));
    }

    /** Registers one feature of each kind, hidden and not. */
    public void register(TachyonServer server) {
        for (final var name : List.of(PUBLIC_TOOL, ADMIN_TOOL, PARTNER_TOOL)) {
            server.tools().register(d -> d.name(name), (ctx, request) -> {
                ran.add(name);
                return ToolResult.text("ran " + name);
            });
        }
        server.tools().register(d -> d.name(READ_FILES).requiredScopes("files:read"), (ctx, request) -> {
            ran.add(READ_FILES);
            return ToolResult.text("read");
        });
        server.tools().register(d -> d.name(NOTIFY), (ctx, request) -> {
            server.resources().notifyResourceUpdated(ADMIN_NOTES);
            server.resources().notifyResourceUpdated(PUBLIC_NOTES);
            return ToolResult.text("notified");
        });
        server.tools().register(d -> d.name(EARLY_REFUSAL), (ctx, request) -> {
            throw AuthenticationException.bearerInvalidToken("revoked upstream");
        });
        server.tools().register(d -> d.name(LATE_REFUSAL), (ctx, request) -> {
            final var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
            if (stream != null) {
                stream.start().toCompletableFuture().join();
            }
            throw AuthenticationException.bearerInvalidToken("revoked upstream");
        });
        server.resources()
                .register(
                        d -> d.name("public-notes").uri(PUBLIC_NOTES),
                        (ctx, request) -> TextResourceContents.of(PUBLIC_NOTES, "public", "text/plain"))
                .register(
                        d -> d.name("admin-notes").uri(ADMIN_NOTES),
                        (ctx, request) -> TextResourceContents.of(ADMIN_NOTES, "secret", "text/plain"))
                .registerTemplate(
                        d -> d.name("admin-template").uriTemplate(ADMIN_TEMPLATE),
                        (ctx, request) -> TextResourceContents.of(request.uri(), "secret", "text/plain"));
        server.prompts()
                .register(
                        PromptDescriptor.of(PUBLIC_PROMPT, "open"), (ctx, request) -> PromptResult.messages(List.of()))
                .register(
                        PromptDescriptor.of(ADMIN_PROMPT, "closed"),
                        (ctx, request) -> PromptResult.messages(List.of()));
        server.completions()
                .registerForPrompt(ADMIN_PROMPT, (ctx, request) -> CompletionResult.of(List.of("root")))
                .registerForResource(ADMIN_TEMPLATE, (ctx, request) -> CompletionResult.of(List.of("42")));
    }

    public void sabotage(Sabotage how) {
        this.sabotage = how;
    }

    /** The tools that ran, in order. */
    public List<String> ran() {
        return List.copyOf(ran);
    }

    /** Every operation the observer completed. */
    public List<Recorded> observed() {
        return List.copyOf(observed);
    }
}
