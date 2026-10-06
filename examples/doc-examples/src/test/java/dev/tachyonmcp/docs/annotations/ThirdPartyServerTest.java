package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.annotations.mcpjava.McpJavaAnnotationProvider;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;

import static dev.tachyonmcp.docs.JsonRpc.names;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static org.assertj.core.api.Assertions.assertThat;

class ThirdPartyServerTest {

    @Test
    void providerRegistersEveryServiceThatFollowsIt() {
        try (var server = ThirdPartyServer.build()) {
            assertThat(server.tools().find("temperature")).isPresent();
            assertThat(server.tools().find("add")).isPresent();
        }
    }

    @Test
    void registerUsesTheNativeProviderUntilWithProviderSwitchesIt() throws Exception {
        var server = McpTestServers.start(
                b -> b.annotations(a -> a.register(new WeatherService())
                        .withProvider(new McpJavaAnnotationProvider())
                        .register(new ThirdPartyServer.CalculatorService())),
                s -> {});
        try (server) {
            assertThat(names(result(server, "tools/list"), "tools"))
                    .containsExactlyInAnyOrder("forecast", "greet", "add");
            assertThat(text(result(server, "tools/call", """
                    {"name":"add","arguments":{"left":2,"right":3}}
                    """))).isEqualTo("5");
        }
    }
}
