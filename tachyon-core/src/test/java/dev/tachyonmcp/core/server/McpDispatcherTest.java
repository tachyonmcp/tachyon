/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static dev.tachyonmcp.core.test.TestUtils.parseJson;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.SessionEvent;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class McpDispatcherTest {

    static Stream<Arguments> jsonNodeParams() {
        return Stream.of(Arguments.of("{\"key\":\"value\"}"), Arguments.of("{\"_meta\":{\"trace\":7}}"));
    }

    static Stream<Arguments> nonObjectParams() {
        return Stream.of(Arguments.of("[1,\"two\"]"), Arguments.of("\"scalar\""), Arguments.of("42"));
    }

    private static McpDispatcher.DispatchResult.Response asResponse(McpDispatcher.DispatchResult result) {
        assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Response.class);
        return (McpDispatcher.DispatchResult.Response) result;
    }

    private static ServerEngine newEngine(Consumer<ServerBuilder> configurer) {
        var builder = TachyonServer.builder();
        configurer.accept(builder);
        return (ServerEngine) builder.build();
    }

    @Test
    void shouldAllowDifferentIdsInSameSession() {
        try (ServerEngine server = newEngine(b -> {})) {
            server.createSession("sess_diff");
            var dispatcher = new McpDispatcher(server, server.executor());

            var first = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess_diff")
                    .join());
            assertThat(first.responseBodyString()).contains("result");

            var second = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(2), "ping", null, "sess_diff")
                    .join());
            assertThat(second.responseBodyString()).contains("result");
        }
    }

    @Test
    void shouldAllowSameIdInDifferentSessions() {
        try (ServerEngine server = newEngine(b -> {})) {
            server.createSession("sess_a");
            server.createSession("sess_b");
            var dispatcher = new McpDispatcher(server, server.executor());

            var first = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(42), "ping", null, "sess_a")
                    .join());
            assertThat(first.responseBodyString()).contains("result");

            var second = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(42), "ping", null, "sess_b")
                    .join());
            assertThat(second.responseBodyString()).contains("result");
        }
    }

    @Test
    void shouldRejectNonInitializeRequestBeforeSessionIsActive() {
        try (ServerEngine server = (ServerEngine)
                TachyonServer.builder().session(s -> s.enabled(true)).build()) {
            server.createSession("sess_init");
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "tools/list", null, "sess_init")
                    .join());
            var body = result.responseBodyString();
            assertThat(body).contains("error");
            assertThat(body).contains("-32600");
        }
    }

    @Test
    void shouldAcceptPingBeforeSessionIsActive() {
        try (ServerEngine server = newEngine(b -> {})) {
            server.createSession("sess_ping");
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess_ping")
                    .join());
            var body = result.responseBodyString();
            assertThat(body).contains("result");
            assertThat(body).doesNotContain("error");
        }
    }

    @Test
    void shouldAcceptRequestAfterSessionIsActive() {
        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("sess_active");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess_active")
                    .join());
            var body = result.responseBodyString();
            assertThat(body).contains("result");
        }
    }

    @ParameterizedTest
    @MethodSource("jsonNodeParams")
    void requestEventRetainsJsonNodeParams(String paramsJson) {
        try (ServerEngine server = newEngine(b -> b.session(s -> s.enabled(true)))) {
            var session = server.createSession("sess_params");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", parseJson(paramsJson), session.id())
                    .join();

            var requestEvent = server.replay(session.id(), -1).stream()
                    .filter(SessionEvent.RequestEvent.class::isInstance)
                    .map(SessionEvent.RequestEvent.class::cast)
                    .findFirst()
                    .orElseThrow();
            assertThat(requestEvent.paramsJson()).isEqualTo(paramsJson);
        }
    }

    @ParameterizedTest
    @MethodSource("nonObjectParams")
    void nonObjectParamsAreRejectedAsInvalidParams(String paramsJson) {
        try (ServerEngine server = newEngine(b -> b.session(s -> s.enabled(true)))) {
            var session = server.createSession("sess_bad_params");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var body = asResponse(dispatcher
                            .dispatchRequestAsync(RequestId.of(1), "ping", parseJson(paramsJson), session.id())
                            .join())
                    .responseBodyString();

            assertThatJson(body).inPath("$.error.code").isEqualTo(-32602);
            assertThatJson(body).isObject().doesNotContainKey("result");
        }
    }

    @Test
    void cancellationFromAnotherSessionDoesNotFailPendingRequest() {
        try (ServerEngine server = (ServerEngine)
                TachyonServer.builder().session(s -> s.enabled(true)).build()) {
            var owner = server.createSession("sess_cancel-owner");
            owner.activate();
            var other = server.createSession("sess_cancel-other");
            other.activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var pending = server.sendRequest(owner, "sampling/createMessage", Map.of());
            var requestId = server.replay(owner.id(), -1).stream()
                    .filter(SessionEvent.OutboundRequestEvent.class::isInstance)
                    .map(SessionEvent.OutboundRequestEvent.class::cast)
                    .map(SessionEvent.OutboundRequestEvent::requestId)
                    .findFirst()
                    .orElseThrow();

            var params = Map.of("requestId", requestId.toString(), "reason", "User cancelled");
            var result = dispatcher.dispatchNotification("notifications/cancelled", params, other.id());
            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Accepted.class);
            assertThat(pending).isNotDone();
            assertThat(server.completePendingRequest(requestId, owner.id(), null, "{}"))
                    .isTrue();
        }
    }

    @Test
    void cancelsWithoutSessionLogsAndAccepts() {
        try (ServerEngine server = newEngine(b -> {})) {
            var dispatcher = new McpDispatcher(server, server.executor());

            var params = Map.of("requestId", 1, "reason", "no-session");
            var result = dispatcher.dispatchNotification("notifications/cancelled", params, null);
            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Accepted.class);
        }
    }

    @Test
    void cancelsWithUnknownRequestIdIsAccepted() {
        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("sess_cancel-unknown");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = dispatcher.dispatchNotification(
                    "notifications/cancelled", Map.of("requestId", "missing"), "sess_cancel-unknown");
            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Accepted.class);
        }
    }

    @Test
    void cancelsWithMalformedRequestIdIsAcceptedWithoutThrowing() {
        try (ServerEngine server = newEngine(b -> {})) {
            var session = server.createSession("sess_cancel-malformed");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = dispatcher.dispatchNotification(
                    "notifications/cancelled", Map.of("requestId", true), "sess_cancel-malformed");
            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Accepted.class);
        }
    }

    @Test
    void cancelsWithEmptyParamsIsAccepted() {
        try (ServerEngine server = newEngine(b -> {})) {
            server.createSession("sess_cancel-empty");
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = dispatcher.dispatchNotification("notifications/cancelled", Map.of(), "sess_cancel-empty");
            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Accepted.class);
        }
    }

    @Test
    void cancelsWithNullParamsIsAccepted() {
        try (ServerEngine server = newEngine(b -> {})) {
            server.createSession("sess_cancel-null");
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = dispatcher.dispatchNotification("notifications/cancelled", null, "sess_cancel-null");
            assertThat(result).isInstanceOf(McpDispatcher.DispatchResult.Accepted.class);
        }
    }

    @Test
    void shouldRejectRequestAfterSessionIsClosed() {
        try (ServerEngine server = (ServerEngine)
                TachyonServer.builder().session(s -> s.enabled(true)).build()) {
            var session = server.createSession("sess_closed");
            session.activate();
            session.close();
            var dispatcher = new McpDispatcher(server, server.executor());

            var result = asResponse(dispatcher
                    .dispatchRequestAsync(RequestId.of(1), "ping", null, "sess_closed")
                    .join());
            var body = result.responseBodyString();
            assertThat(body).contains("error");
            assertThat(body).contains("-32600");
        }
    }
}
