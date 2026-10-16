/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.handlers;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubscriptionsListenHandlerTest {

    private final ServerEngine server = newEngine(b -> {});
    private final McpDispatcher dispatcher = new McpDispatcher(server, server.executor());

    @BeforeEach
    void setUp() {
        var session = server.createSession("sess_sub_listen");
        session.activate();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    @Test
    void rejectsSubscriptionsListenUnderLegacyProtocol() {
        // SEP-2575: subscriptions/listen is 2026-07-28-only; 2025-11-25 has no such method.
        var legacyProtocol = Protocols.list().stream()
                .filter(p -> p.versionString().equals("2025-11-25"))
                .findFirst()
                .orElseThrow();
        var ctx = DefaultDispatchContext.create(legacyProtocol, server);

        var result = (McpDispatcher.DispatchResult.Response) dispatcher
                .dispatchRequestAsync(
                        RequestId.of(1),
                        "subscriptions/listen",
                        null,
                        "sess_sub_listen",
                        null,
                        ctx,
                        SecurityContext.anonymous())
                .join();

        // language=json
        assertThatJson(result.responseBodyString()).isEqualTo("""
            {
              "jsonrpc":"2.0",
              "id":1,
              "error": {
                "code":-32601,
                "message":"Method not found"
              }
            }
            """);
    }

    @Test
    void handleThrowsBecauseTheHandlerIsStreamBased() {
        // subscriptions/listen overrides handleAsync(), so real dispatch never reaches handle() --
        // it can only be exercised by invoking the registered handler directly.
        var handler = server.getHandler("subscriptions/listen");
        var context = DefaultDispatchContext.stateless(server);

        assertThatThrownBy(() -> handler.handle(context, null))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("subscriptions/listen is stream-based");
    }
}
