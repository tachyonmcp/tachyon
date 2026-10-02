package dev.tachyonmcp.docs.annotations;

import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CompletionsTest {

    private static List<String> values(JsonNode result) {
        return items(result.path("completion").path("values")).stream()
                .map(JsonNode::asString)
                .toList();
    }

    private static TachyonServer startWith(Object completions) {
        return McpTestServers.start(
                b -> b.annotations(a -> a.register(new WeatherService()).register(completions)), s -> {});
    }

    @Test
    void simpleSignatureCompletesTheNamedArgumentAndReadsSiblingsFromContext() throws Exception {
        try (var server = startWith(new SimpleCompletions())) {
            assertThat(values(result(server, "completion/complete", """
                    {"ref":{"type":"ref/prompt","name":"trip"},
                     "argument":{"name":"city","value":"B"},
                     "context":{"arguments":{"country":"DE"}}}
                    """))).containsExactly("Berlin", "Bonn", "Bremen");

            assertThat(values(result(server, "completion/complete", """
                    {"ref":{"type":"ref/prompt","name":"trip"},
                     "argument":{"name":"city","value":"B"}}
                    """))).containsExactly("Bergen", "Berlin", "Bonn", "Bremen");
        }
    }

    @Test
    void simpleSignatureReturnsNothingForADifferentArgument() throws Exception {
        try (var server = startWith(new SimpleCompletions())) {
            assertThat(values(result(server, "completion/complete", """
                    {"ref":{"type":"ref/prompt","name":"trip"},
                     "argument":{"name":"season","value":"s"}}
                    """))).isEmpty();
        }
    }

    @Test
    void completionRequestSignatureHandlesSeveralArguments() throws Exception {
        try (var server = startWith(new RequestCompletions())) {
            assertThat(values(result(server, "completion/complete", """
                    {"ref":{"type":"ref/prompt","name":"trip"},
                     "argument":{"name":"season","value":"s"}}
                    """))).containsExactly("spring", "summer");

            assertThat(values(result(server, "completion/complete", """
                    {"ref":{"type":"ref/prompt","name":"trip"},
                     "argument":{"name":"city","value":"Os"},
                     "context":{"arguments":{"country":"NO"}}}
                    """))).containsExactly("Oslo");
        }
    }
}
