package dev.tachyonmcp.docs.extensions.customextensions;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.declaring;
import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.names;
import static dev.tachyonmcp.docs.JsonRpc.request;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ExtensionSettings;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.testkit.McpTestClients;
import dev.tachyonmcp.testkit.McpTestServers;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class CustomExtensionsTest {

    private static final String GREETINGS = "com.example/greetings";

    private static TachyonServer startWith(ServerExtension... extensions) {
        return McpTestServers.start(b -> b.withExtensions(extensions), s -> {});
    }

    @Test
    void mainGreetsADeclaringClientWithTheResponseShownInTheDocs() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.extensions.customextensions.MyMcpServer");
                var client = declaring(DOCUMENTED_PORT, GREETINGS)) {
            var response = client.post(request("com.example/greet", """
                    {"name":"Ada"}
                    """));

            assertThatResponse(response)
                    .hasStatus(200)
                    .isSuccess()
                    .hasId(1)
                    .hasResult("""
                            {"message":"Hello, Ada!"}
                            """);
        }
    }

    @Test
    void undeclaredClientsAreServedByDefaultAndTheHandlerSeesTheExtensionAsNotEnabled() throws Exception {
        var observing = new ServerExtension() {
            @Override
            public String extensionId() {
                return "com.example/observed";
            }

            @Override
            public AdvertiseMode advertiseMode() {
                return AdvertiseMode.ALWAYS;
            }

            @Override
            public void bootstrap(ExtensionContext context) {
                context.registerHandler(
                        "com.example/enabled",
                        (interaction, params) -> Map.of("enabled", interaction.isExtensionEnabled(extensionId())));
            }
        };
        try (var server = startWith(new GreetingsExtension())) {
            assertThat(result(server, "com.example/greet", """
                    {"name":"Ada"}
                    """).path("message").asString()).isEqualTo("Hello, Ada!");
            assertThat(result(server, "com.example/greet").path("message").asString())
                    .isEqualTo("Hello, stranger!");
        }
        try (var server = startWith(observing)) {
            assertThat(result(server, "com.example/enabled").path("enabled").asBoolean())
                    .isFalse();
            try (var client = declaring(server.port(), "com.example/observed")) {
                var response = client.post(request("com.example/enabled", "{}"));
                assertThat(assertThatResponse(response)
                                .hasStatus(200)
                                .isSuccess()
                                .result()
                                .path("enabled")
                                .asBoolean())
                        .isTrue();
            }
        }
    }

    @Test
    void requiredNegotiationRejectsUndeclaredCallsWithTheErrorShownInTheDocs() throws Exception {
        try (var server = startWith(new RequiredGreetingsExtension())) {
            var rejected = dev.tachyonmcp.docs.JsonRpc.post(server, "com.example/greet", """
                    {"name":"Ada"}
                    """);

            assertThatResponse(rejected).hasStatus(400).isJsonRpcError().hasErrorCode(-32021);
            assertThatJson(rejected.body())
                    .node("error")
                    .isEqualTo("""
                            {"code":-32021,"message":"Requires the 'com.example/greetings' extension",
                             "data":{"requiredCapabilities":{"extensions":{"com.example/greetings":{}}}}}
                            """);

            try (var client = declaring(server.port(), GREETINGS)) {
                var accepted = client.post(request("com.example/greet", """
                        {"name":"Ada"}
                        """));
                assertThatResponse(accepted).hasStatus(200).isSuccess().hasResult("""
                        {"message":"Hello, Ada!"}
                        """);
            }
        }
    }

    @Test
    void unknownMethodIsMethodNotFound() throws Exception {
        try (var server = startWith(new GreetingsExtension())) {
            var response = dev.tachyonmcp.docs.JsonRpc.post(server, "com.example/greeet", "{}");

            assertThatResponse(response).hasStatus(404).isJsonRpcError().hasErrorCode(-32601);
        }
    }

    @Test
    void extensionGatedToolIsHiddenFromClientsThatDidNotDeclareTheExtension() throws Exception {
        try (var server = startWith(new FarewellExtension())) {
            assertThat(names(result(server, "tools/list"), "tools")).doesNotContain("farewell");
            var unknown = dev.tachyonmcp.docs.JsonRpc.post(server, "tools/call", """
                    {"name":"farewell","arguments":{}}
                    """);
            assertThatResponse(unknown).isJsonRpcError().hasErrorCode(-32602).hasErrorMessageContaining("Unknown tool: farewell");

            try (var client = declaring(server.port(), "com.example/farewells")) {
                var list = assertThatResponse(client.post(request("tools/list", "{}")))
                        .hasStatus(200)
                        .isSuccess()
                        .result();
                assertThat(names(list, "tools")).containsExactly("farewell");
                var call = assertThatResponse(client.post(request("tools/call", """
                                {"name":"farewell","arguments":{}}
                                """)))
                        .hasStatus(200)
                        .isSuccess()
                        .result();
                assertThat(text(call)).isEqualTo("Goodbye!");
            }
        }
    }

    @Test
    void serverSettingsAreAdvertisedWithTheExtension() throws Exception {
        try (var server = startWith(new SettingsExtension())) {
            var extensions = result(server, "server/discover").path("capabilities").path("extensions");

            assertThatJson(extensions.path(GREETINGS).toString()).isEqualTo("""
                    {"version":"1.0"}
                    """);
        }
    }

    @Test
    void clientSettingsReachOnConnectionInit() throws Exception {
        var languages = new CopyOnWriteArrayList<String>();
        var spying = new SettingsExtension() {
            @Override
            public void onConnectionInit(InteractionContext interaction, ExtensionSettings clientSettings) {
                super.onConnectionInit(interaction, clientSettings);
                languages.add(clientSettings.values().stringOr("language", "en"));
            }
        };
        try (var server = startWith(spying);
                var client = declaring(server.port(), GREETINGS, """
                        {"language":"fr"}
                        """)) {
            assertThatResponse(client.post(request("tools/list", "{}"))).hasStatus(200).isSuccess();

            assertThat(languages).containsExactly("fr");
        }
    }

    @Test
    void shutdownRunsWhenTheServerIsClosed() {
        var extension = new SchedulerExtension();
        var server = startWith(extension);

        assertThat(extension.schedulerStopped()).isFalse();
        server.close();
        assertThat(extension.schedulerStopped()).isTrue();
    }
}
