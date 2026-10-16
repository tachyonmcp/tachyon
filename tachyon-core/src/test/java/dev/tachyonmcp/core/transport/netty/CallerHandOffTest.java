/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.Owner;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The hand-off from authentication to the handlers that process a request: the caller is recorded
 * once, taken once by the handler that processes the request, and passed down explicitly. A request
 * with no recorded caller on a server that authenticates is refused {@code 500} and never processed,
 * whatever its method, and what one request recorded is gone for the next.
 */
class CallerHandOffTest {

    private static final SecurityContext ALICE = SecurityContext.authenticated(() -> "alice", List.of());

    private final List<String> sawCaller = new ArrayList<>();
    private ServerEngine server;
    private EmbeddedChannel channel;

    @AfterEach
    void tearDown() {
        channel.finishAndReleaseAll();
        server.close();
    }

    private void operationHandler(boolean authenticating) {
        server = newEngine(
                b -> {
                    b.stateless();
                    if (authenticating) {
                        b.security(s -> s.accessPolicy(AccessPolicy.permitAll())
                                .authenticationProvider(request -> SecurityContext.anonymous()));
                    }
                },
                s -> s.tools().registerAsync(d -> d.name("hello"), (ctx, request) -> {
                    final var principal = ctx.securityContext().principal();
                    sawCaller.add(principal == null ? "anonymous" : principal.getName());
                    return CompletableFuture.completedFuture(ToolResult.text("hi"));
                }));
        channel = new EmbeddedChannel(
                new InteractionHandler(),
                new McpOperationHandler(server, new McpDispatcher(server, Runnable::run), Runnable::run));
    }

    private void initializationHandler() {
        server = newEngine(b -> b.session(s -> s.enabled())
                .security(s -> s.accessPolicy(AccessPolicy.permitAll())
                        .authenticationProvider(request -> SecurityContext.anonymous())));
        final var dispatcher = new McpDispatcher(server, Runnable::run);
        channel = new EmbeddedChannel(new ProtocolVersionHandler(), new InteractionHandler());
        channel.pipeline()
                .addLast(
                        McpHandlerManager.HANDLER_INIT,
                        new McpInitializationHandler(server, dispatcher, Runnable::run));
        channel.pipeline()
                .addLast(
                        "lifecycle",
                        new LifecyclePipelineCoordinator(new McpHandlerManager(server, dispatcher, Runnable::run)));
    }

    private FullHttpResponse send(HttpMethod method, String body) {
        final var request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1,
                method,
                "/mcp",
                Unpooled.copiedBuffer(body == null ? "" : body, StandardCharsets.UTF_8));
        request.headers()
                .set(HttpHeaderNames.ACCEPT, "application/json, text/event-stream")
                .set("MCP-Protocol-Version", "2025-11-25");
        channel.writeInbound(request);
        channel.runPendingTasks();
        return channel.readOutbound();
    }

    private static final String HELLO = """
            {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hello","arguments":{}}}""";

    private static final String INITIALIZE = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""";

    @ParameterizedTest
    @ValueSource(strings = {"POST", "GET", "DELETE", "OPTIONS"})
    void aRequestOfAnyMethodWithNoRecordedCallerIsRefusedAndNeverProcessed(String method) {
        operationHandler(true);

        final var response = send(HttpMethod.valueOf(method), method.equals("POST") ? HELLO : null);

        assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        assertThat(sawCaller).as("the tool never ran").isEmpty();
        response.release();
    }

    @Test
    void theCallerIsTakenForTheOneRequestAndNothingIsLeftForTheNext() {
        operationHandler(true);

        ChannelHandlerUtils.recordCaller(channel, ALICE);
        final var first = send(HttpMethod.POST, HELLO);
        final var second = send(HttpMethod.POST, HELLO);

        assertThat(first.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(second.status())
                .as("alice is not carried over to the request that follows hers")
                .isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        assertThat(sawCaller).containsExactly("alice");
        first.release();
        second.release();
    }

    @Test
    void aServerThatDoesNotAuthenticateServesAnonymousCallers() {
        operationHandler(false);

        final var response = send(HttpMethod.POST, HELLO);

        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        assertThat(sawCaller).containsExactly("anonymous");
        response.release();
    }

    @Test
    void initializeWithNoRecordedCallerIsRefusedAndCreatesNoSession() {
        initializationHandler();

        final var response = send(HttpMethod.POST, INITIALIZE);

        assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.headers().contains("MCP-Session-Id")).isFalse();
        response.release();
    }

    @Test
    void initializeCreatesTheSessionOfTheRecordedCaller() {
        initializationHandler();

        ChannelHandlerUtils.recordCaller(channel, ALICE);
        final var response = send(HttpMethod.POST, INITIALIZE);

        assertThat(response.status()).isEqualTo(HttpResponseStatus.OK);
        final var sessionId = response.headers().get("MCP-Session-Id");
        assertThat(server.getSession(sessionId).orElseThrow().owner()).isEqualTo(new Owner.Named("alice"));
        response.release();
    }

    @Test
    void anOptionsRequestTakesTheCallerToo() {
        initializationHandler();

        ChannelHandlerUtils.recordCaller(channel, ALICE);
        final var options = send(HttpMethod.OPTIONS, null);
        final var initialize = send(HttpMethod.POST, INITIALIZE);

        assertThat(options.status()).isEqualTo(HttpResponseStatus.NO_CONTENT);
        assertThat(initialize.status())
                .as("alice was taken by the OPTIONS request and is not left for the next one")
                .isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        options.release();
        initialize.release();
    }
}
