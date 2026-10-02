package example;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.post;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.docs.ForkedMain;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GreetingApplicationTest {

    private static final String GREET = """
            {"name":"greet","arguments":{"name":"Ada"}}
            """;

    @Test
    @Timeout(90)
    void applicationServesTheDiscoveredToolWithTheConfiguredIdentity() throws Exception {
        try (var ignored = ForkedMain.start("example.GreetingApplication")) {
            var info = result(DOCUMENTED_PORT, "server/discover").path("serverInfo");
            assertThat(info.path("name").asString()).isEqualTo("greeting-server");
            assertThat(info.path("version").asString()).isEqualTo("1.0.0");

            assertThat(text(result(DOCUMENTED_PORT, "tools/call", GREET))).isEqualTo("Hello, Ada!");
            for (var arguments : new String[] {"{}", "{\"name\":42}"}) {
                assertThatResponse(post(DOCUMENTED_PORT, "tools/call", "{\"name\":\"greet\",\"arguments\":%s}".formatted(arguments)))
                        .hasStatus(400)
                        .isJsonRpcError()
                        .hasErrorCode(-32602);
            }
        }
    }

    @Test
    @Timeout(90)
    void tachyonPortControlsMcpWhileServerPortAloneDoesNot() throws Exception {
        try (var ignored = ForkedMain.start("example.GreetingApplication", Map.of("SERVER_PORT", "18999"), 8080)) {
            assertThat(text(result(8080, "tools/call", GREET))).isEqualTo("Hello, Ada!");
        }
        try (var ignored = ForkedMain.start("example.GreetingApplication", Map.of("TACHYON_PORT", "18093"), 18093)) {
            assertThat(text(result(18093, "tools/call", GREET))).isEqualTo("Hello, Ada!");
        }
    }
}
