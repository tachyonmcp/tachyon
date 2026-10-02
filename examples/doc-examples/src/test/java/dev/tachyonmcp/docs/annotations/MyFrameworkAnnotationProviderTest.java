package dev.tachyonmcp.docs.annotations;

import static dev.tachyonmcp.docs.JsonRpc.names;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;

class MyFrameworkAnnotationProviderTest {

    static class PingService {
        @MyTool(name = "ping")
        public String ping() {
            return "pong";
        }
    }

    static class OtherPingService {
        @MyTool(name = "ping")
        public String ping() {
            return "other pong";
        }
    }

    @Test
    void customProviderTurnsItsAnnotationIntoATool() throws Exception {
        try (var server = McpTestServers.start(
                b -> b.annotations(a -> a.withProvider(new MyFrameworkAnnotationProvider())
                        .register(new PingService())),
                s -> {})) {
            assertThat(names(result(server, "tools/list"), "tools")).containsExactly("ping");
            assertThat(text(result(server, "tools/call", """
                    {"name":"ping","arguments":{}}
                    """))).isEqualTo("pong");
        }
    }

    @Test
    void registeringTheSameNameTwiceSilentlyReplacesTheFirst() throws Exception {
        try (var server = McpTestServers.start(
                b -> b.annotations(a -> a.withProvider(new MyFrameworkAnnotationProvider())
                        .register(new PingService())
                        .register(new OtherPingService())),
                s -> {})) {
            assertThat(names(result(server, "tools/list"), "tools")).containsExactly("ping");
            assertThat(text(result(server, "tools/call", """
                    {"name":"ping","arguments":{}}
                    """))).isEqualTo("other pong");
        }
    }
}
