package dev.tachyonmcp.docs.features;

import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;

import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static org.assertj.core.api.Assertions.assertThat;

class FeaturesServerTest {

    @Test
    void builderRegistersTheAnnotatedServiceOnTheDocumentedPort() {
        try (var server = FeaturesServer.build()) {
            var greet = server.tools().find("greet");

            assertThat(greet).isPresent();
            assertThat(greet.get().description()).isEqualTo("Say hello to someone");
        }
    }

    @Test
    void registeredServiceAnswersToolCalls() throws Exception {
        try (var server = McpTestServers.start(
                b -> b.annotations(annotations -> annotations.register(new GreetingService())), s -> {})) {
            assertThat(text(result(server, "tools/call", """
                    {"name":"greet","arguments":{"name":"Ada"}}
                    """))).isEqualTo("Hello, Ada!");
        }
    }
}
