/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN_NOTES;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.ADMIN_TOOL;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.PUBLIC_NOTES;
import static dev.tachyonmcp.e2e.mcp.GuardedFeatures.USER;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.e2e.mcp.GuardedFeatures;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The access policy on a 2025-11-25 session: {@code resources/subscribe} to a resource the caller may
 * not see is accepted exactly like a subscription to a URI nothing names, and no update about it ever
 * reaches the session. A hidden tool answers like an unknown one here too.
 */
@Timeout(30)
class AccessPolicySessionTest extends AbstractStatefulMcpE2eTest {

    private GuardedFeatures guarded = new GuardedFeatures();

    @BeforeEach
    void startGuardedServer() {
        guarded = new GuardedFeatures();
        startServer(guarded::configure, guarded::register);
    }

    private static String subscribe(int id, String uri) {
        // language=JSON
        return """
                {"jsonrpc":"2.0","id":%d,"method":"resources/subscribe","params":{"uri":"%s"}}
                """.formatted(id, uri);
    }

    @Test
    void aSubscriptionToAHiddenResourceIsAcceptedLikeAnUnknownOneAndNeverDelivers() throws Exception {
        try (var user = new Mcp20251125Client(port, USER)) {
            user.initialize();
            final var hidden = user.sendRpc(subscribe(2, ADMIN_NOTES));
            final var unknown = user.sendRpc(subscribe(2, "notes://nothing"));
            final var visible = user.sendRpc(subscribe(3, PUBLIC_NOTES));

            assertThat(hidden.statusCode()).isEqualTo(unknown.statusCode()).isEqualTo(200);
            assertThat(hidden.body()).isEqualTo(unknown.body());
            assertThat(visible.statusCode()).isEqualTo(200);

            // The updates of a call travel on that call's stream, in the order they were reported.
            final var notified = user.post(user.sessionId(), """
                    {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                    """.formatted(GuardedFeatures.NOTIFY));

            assertThat(notified.body())
                    .contains("notifications/resources/updated", PUBLIC_NOTES)
                    .doesNotContain(ADMIN_NOTES);
        }
    }

    @Test
    void aHiddenToolAnswersLikeAnUnknownOne() throws Exception {
        try (var user = new Mcp20251125Client(port, USER)) {
            user.initialize();
            final var hidden = user.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"%s","arguments":{}}}
                    """.formatted(ADMIN_TOOL));
            final var unknown = user.sendRpc("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"no-such-tool","arguments":{}}}
                    """);

            assertThat(hidden.body().replace(ADMIN_TOOL, "<name>"))
                    .isEqualTo(unknown.body().replace("no-such-tool", "<name>"));
            assertThat(guarded.ran()).isEmpty();
        }
    }
}
