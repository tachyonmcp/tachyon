/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.BearerTokenAuthenticationProvider;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import dev.tachyonmcp.testkit.TestTaskConnector;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A bearer-secured server with the tasks extension, a task-producing {@code start} tool and a
 * connector that records who called it, so a test can prove which calls reached the connector.
 * The connector authorizes nothing itself, as a real one might not: whether a caller may reach a
 * task is Tachyon's decision here. The {@code start} tool stores the task with its caller's owner
 * key, or the public one for an anonymous caller, as an application must for the owner to outlive
 * the server's cache, a restart, or a node.
 */
public final class OwnedTasksFixture {

    public static final Instant CREATED_AT = Instant.parse("2026-10-15T07:00:00Z");

    private final TestBearerTokens tokens = new TestBearerTokens();
    private final TestTaskConnector externalSystem = new TestTaskConnector();
    private final List<String> connectorCalls = new CopyOnWriteArrayList<>();
    private final boolean admitsAnonymous;

    /** A fixture whose server admits signed-in callers only. */
    public OwnedTasksFixture() {
        this(false);
    }

    private OwnedTasksFixture(boolean admitsAnonymous) {
        this.admitsAnonymous = admitsAnonymous;
    }

    /** A fixture whose server also lets a request without credentials through as anonymous. */
    public static OwnedTasksFixture admittingAnonymous() {
        return new OwnedTasksFixture(true);
    }

    /** Secures the server with the fixture's tokens and wires the recording connector. */
    public void configure(ServerBuilder builder) {
        builder.capabilities(c -> c.tools())
                .security(admitsAnonymous ? this::bearerOrAnonymous : TestBearerTokens.secured(tokens))
                .withExtension(TasksExtension.class, t -> t.connector(recordingConnector()));
    }

    private void bearerOrAnonymous(SecurityConfig.Builder security) {
        final var bearer =
                new BearerTokenAuthenticationProvider(TestBearerTokens.bearerConfig(tokens), Clock.systemUTC());
        security.accessPolicy(AccessPolicy.permitAll())
                .authenticationProvider(
                        request -> request.headers("Authorization").isEmpty()
                                ? SecurityContext.anonymous()
                                : bearer.authenticate(request));
    }

    /**
     * Registers {@code start}: it starts the task named by its {@code taskId} argument and returns it;
     * and {@code adopt}: it returns the task named by {@code taskId} without starting anything, as a
     * tool that takes a task id from its caller would.
     */
    public void register(TachyonServer server) {
        server.tools()
                .register(
                        b -> b.name("adopt").taskSupport(TaskSupport.REQUIRED),
                        (context, request) -> ToolResult.task(
                                TaskSnapshot.working(request.arguments().stringOr("taskId", ""), CREATED_AT, 1)));
        server.tools().register(b -> b.name("start").taskSupport(TaskSupport.REQUIRED), (context, request) -> {
            var snapshot = TaskSnapshot.builder()
                    .from(TaskSnapshot.working(request.arguments().stringOr("taskId", ""), CREATED_AT, 1))
                    .ownerKey(TaskSnapshot.ownerKeyOf(context.securityContext()))
                    .build();
            externalSystem.start(snapshot);
            return ToolResult.task(snapshot);
        });
    }

    /** Starts a task in the external system without going through the server. */
    public void startExternally(TaskSnapshot snapshot) {
        externalSystem.start(snapshot);
    }

    /** Starts a public task in the external system: the only kind every caller reaches. */
    public void startPublicExternally(TaskSnapshot snapshot) {
        externalSystem.start(TaskSnapshot.builder()
                .from(snapshot)
                .ownerKey(TaskSnapshot.PUBLIC_OWNER_KEY)
                .build());
    }

    /** Returns every connector call as {@code <method>:<taskId>:<caller>}, in order. */
    public List<String> connectorCalls() {
        return List.copyOf(connectorCalls);
    }

    @SuppressWarnings("deprecation")
    private TaskConnector recordingConnector() {
        return TaskConnector.builder()
                .get((ctx, request) -> {
                    record("get", request.taskId(), ctx);
                    return externalSystem.refresh(ctx, request);
                })
                .cancel((ctx, request) -> {
                    record("cancel", request.taskId(), ctx);
                    externalSystem.cancel(ctx, request);
                })
                .update((ctx, request) -> {
                    record("update", request.taskId(), ctx);
                    externalSystem.submitInput(ctx, request);
                })
                .list((ctx, request) -> {
                    record("list", "-", ctx);
                    return externalSystem.list(ctx, request);
                })
                .awaitResult((ctx, request) -> {
                    record("awaitResult", request.taskId(), ctx);
                    return externalSystem.awaitResult(ctx, request);
                })
                .build();
    }

    private void record(String method, String taskId, InteractionContext ctx) {
        var principal = ctx.securityContext().principal();
        connectorCalls.add(method + ":" + taskId + ":" + (principal == null ? "anonymous" : principal.getName()));
    }
}
