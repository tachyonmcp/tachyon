package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.core.server.ServerBuilder;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.docs.RawHttp;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.request;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationDocsTest {

    @Test
    @Timeout(60)
    void basicMainAppliesIdentityAndTurnsSessionsOnThroughTheTtl() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.running.configuration.BasicServer")) {
            var info = result(DOCUMENTED_PORT, "server/discover").path("serverInfo");
            assertThat(info.path("name").asString()).isEqualTo("my-server");
            assertThat(info.path("version").asString()).isEqualTo("1.0");

            try (var client = McpTestClients.forVersion(DOCUMENTED_PORT, "2025-11-25")) {
                assertThat(client.initialize()).as("a session option enables sessions").isNotNull();
            }
        }
    }

    @Test
    void slowToolKeepsThePostStreamAliveWithProgressOrComments() throws Exception {
        var slowTool = new SlowTool();
        try (var server = McpTestServers.start(
                        b -> {}, s -> s.tools().register(slowTool.descriptor(), slowTool::handle));
                var client = McpTestClients.latest(server.port())) {
            var withToken = client.post(request("tools/call", """
                    {"name":"slow-task","arguments":{},"_meta":{"progressToken":"tok-1"}}
                    """));
            assertThat(withToken.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
            assertThat(withToken.body())
                    .contains("notifications/progress")
                    .contains("\"progressToken\":\"tok-1\"")
                    .contains("step 3")
                    .contains("\"text\":\"done\"");

            var withoutToken = client.post(request("tools/call", """
                    {"name":"slow-task","arguments":{}}
                    """));
            assertThat(withoutToken.headers().firstValue("content-type").orElse("")).startsWith("text/event-stream");
            assertThat(withoutToken.body())
                    .contains(": step 0")
                    .contains(": step 3")
                    .contains("\"text\":\"done\"")
                    .doesNotContain("notifications/progress");
        }
    }

    @Test
    void allowedHostsAdmitsTheExtraAuthorityOnly() {
        try (var server = NetworkOptions.withExtraHost()) {
            assertThat(RawHttp.postStatus(server.port(), "localhost:" + server.port())).isEqualTo(200);
            assertThat(RawHttp.postStatus(server.port(), "host.docker.internal:8096")).isEqualTo(200);
            assertThat(RawHttp.postStatus(server.port(), "host.docker.internal:9999"))
                    .as("a port-qualified entry matches only that port")
                    .isEqualTo(403);
            assertThat(RawHttp.postStatus(server.port(), "evil.example.com")).isEqualTo(403);
        }
    }

    @Test
    void explicitEngineWithoutItsTransportFailsAtStartup() {
        assertThatThrownBy(NetworkOptions::withEpoll).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void disablingSessionsWhileAnOptionIsConfiguredFailsAtBuildTime() throws Exception {
        assertThatThrownBy(SessionAlternatives::allThree).isInstanceOf(IllegalStateException.class);

        record Case(String name, java.util.function.Consumer<dev.tachyonmcp.core.server.ServerBuilder> config, boolean sessions) {}
        var cases = java.util.List.of(
                new Case("ttl", b -> b.session(s -> s.sessionTtl(java.time.Duration.ofMinutes(5))), true),
                new Case("enabled", b -> b.session(SessionConfig.Builder::enabled), true),
                new Case("stateless", ServerBuilder::stateless, false),
                new Case("default", b -> {}, false));
        for (var c : cases) {
            try (var server = McpTestServers.start(c.config(), s -> {});
                    var client = McpTestClients.forVersion(server.port(), "2025-11-25")) {
                assertThat(client.initialize() != null).as(c.name()).isEqualTo(c.sessions());
            }
        }
    }

    @Test
    @Timeout(60)
    void observabilityMainStartsAndServes() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.running.configuration.ObservabilityServer")) {
            assertThat(result(DOCUMENTED_PORT, "server/discover").path("serverInfo").path("name").asString())
                    .isNotBlank();
        }
    }

    @Test
    @Timeout(60)
    void capabilitiesOnAdvertisesTheFeaturesWithoutHandlers() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.running.configuration.CapabilitiesServer")) {
            var capabilities = result(DOCUMENTED_PORT, "server/discover").path("capabilities");

            assertThat(capabilities.path("tools").path("listChanged").asBoolean()).isTrue();
            assertThat(capabilities.has("resources")).isTrue();
            assertThat(capabilities.has("completions")).isTrue();
            assertThat(capabilities.has("logging")).isTrue();

            try (var legacy = McpTestClients.forVersion(DOCUMENTED_PORT, "2025-11-25")) {
                var initialize = legacy.post("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25",
                         "capabilities":{},"clientInfo":{"name":"t","version":"1"}}}
                        """);
                var advertised = assertThatResponse(initialize).hasStatus(200).isSuccess().result().path("capabilities");
                assertThat(advertised.path("resources").path("subscribe").asBoolean()).isTrue();
            }
        }
    }
}
