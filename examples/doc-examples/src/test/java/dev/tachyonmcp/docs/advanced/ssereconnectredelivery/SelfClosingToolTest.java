package dev.tachyonmcp.docs.advanced.ssereconnectredelivery;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class SelfClosingToolTest {

    private static final Pattern EVENT_ID = Pattern.compile("^id: (\\d+)#(\\w+)$", Pattern.MULTILINE);

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(
                b -> b.session(SessionConfig.Builder::enabled),
                s -> s.tools().register(d -> d.name("self-closing").description("Closes its SSE stream"), SelfClosingTool::handle));
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void responseReachesTheClientThatResumesTheClosedStream() throws Exception {
        try (var client = new Mcp20251125Client(server.port())) {
            var sessionId = client.initialize();

            var post = client.post(sessionId, """
                    {"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"self-closing","arguments":{}}}
                    """);

            assertThat(post.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            assertThat(post.body()).doesNotContain("resumed-payload");
            var priming = EVENT_ID.matcher(post.body());
            assertThat(priming.find()).as("priming event id <n>#<key> in:\n%s", post.body()).isTrue();
            var primingId = Long.parseLong(priming.group(1));
            var streamKey = priming.group(2);

            try (var resumed = client.openGetStream(primingId + "#" + streamKey)) {
                var response = resumed.await(frame -> frame.data().contains("resumed-payload"), Duration.ofSeconds(5));

                assertThat(response.json().path("id").asInt()).isEqualTo(9);
                var responseId = EVENT_ID.matcher("id: " + response.id());
                assertThat(responseId.matches()).as("response event id %s", response.id()).isTrue();
                assertThat(Long.parseLong(responseId.group(1))).isGreaterThan(primingId);
                assertThat(responseId.group(2)).isEqualTo(streamKey);
            }
        }
    }
}
