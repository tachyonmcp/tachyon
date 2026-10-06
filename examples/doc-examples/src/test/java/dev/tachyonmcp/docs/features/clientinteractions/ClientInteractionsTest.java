package dev.tachyonmcp.docs.features.clientinteractions;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.function.Consumer;

import static dev.tachyonmcp.docs.JsonRpc.result;
import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;

class ClientInteractionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static TachyonServer startAnnotated() {
        return McpTestServers.start(
                b -> b.session(SessionConfig.Builder::enabled).annotations(a -> a.register(new CityTools())), s -> {});
    }

    private static TachyonServer startProgrammatic() {
        return McpTestServers.start(b -> b.session(SessionConfig.Builder::enabled), ProgrammaticCityTools::register);
    }

    private record RoundTrip(JsonNode elicitation, JsonNode toolResult) {}

    private static RoundTrip elicitationRoundTrip(TachyonServer server, String action, String content)
            throws Exception {
        try (var client = new Mcp20251125Client(server.port())) {
            var initialize = client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                      "protocolVersion":"2025-11-25","capabilities":{"elicitation":{"form":{}}},
                      "clientInfo":{"name":"test","version":"1"}}}
                    """);
            var sessionId = initialize.headers().firstValue("MCP-Session-Id").orElseThrow();
            client.sendInitialized(sessionId);

            try (var stream = client.openPostStream(sessionId, """
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"choose-city","arguments":{}}}
                    """)) {
                var request = stream.await(f -> f.data().contains("elicitation/create"), ofSeconds(5))
                        .json();
                client.post(sessionId, """
                        {"jsonrpc":"2.0","id":%s,"result":{"action":"%s"%s}}
                        """.formatted(request.path("id").toString(), action, content));
                var response = stream.await(f -> f.data().contains("\"id\":2"), ofSeconds(5));
                return new RoundTrip(request, MAPPER.readTree(response.data()).path("result"));
            }
        }
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "accept|,\"content\":{\"city\":\"Paris\"}|Selected Paris|false",
                "decline||City selection declined|true",
                "cancel||City selection cancelled|true"
            })
    @Timeout(30)
    void annotatedToolMapsEveryClientActionToAResult(String action, String content, String text, boolean isError)
            throws Exception {
        try (var server = startAnnotated()) {
            var roundTrip = elicitationRoundTrip(server, action, content == null ? "" : content);

            var request = roundTrip.elicitation().path("params");
            assertThat(request.path("message").asString()).isEqualTo("Choose a forecast city");
            assertThat(request.path("requestedSchema").path("properties").path("city").path("type").asString())
                    .isEqualTo("string");
            assertThat(request.path("requestedSchema").path("required").get(0).asString())
                    .isEqualTo("city");
            assertThat(roundTrip.toolResult().path("content").get(0).path("text").asString())
                    .isEqualTo(text);
            assertThat(roundTrip.toolResult().path("isError").asBoolean()).isEqualTo(isError);
        }
    }

    @Test
    @Timeout(30)
    void programmaticRegistrationBehavesLikeTheAnnotation() throws Exception {
        try (var server = startProgrammatic()) {
            var roundTrip = elicitationRoundTrip(server, "accept", ",\"content\":{\"city\":\"Oslo\"}");

            assertThat(roundTrip.toolResult().path("content").get(0).path("text").asString())
                    .isEqualTo("Selected Oslo");
            assertThat(server.tools().find("choose-city").orElseThrow().description())
                    .isEqualTo("Ask the user to choose a forecast city");
        }
    }

    @Test
    void builderRegistersTheToolWithSessionsEnabled() {
        try (var server = CityServer.build()) {
            assertThat(server.tools().find("choose-city")).isPresent();
        }
    }

    @Test
    void inputRequiredResultCarriesTheFormAndTheOpaqueState() throws Exception {
        Consumer<dev.tachyonmcp.core.server.ServerBuilder> configurer =
                b -> b.annotations(a -> a.register(new InputRequiredCityTools()));
        try (var server = McpTestServers.start(configurer, s -> {})) {
            var response = result(server, "tools/call", """
                    {"name":"choose-city","arguments":{}}
                    """);

            assertThat(response.path("resultType").asString()).isEqualTo("input_required");
            assertThat(response.path("requestState").asString()).isEqualTo("forecast-draft-42");
            var city = response.path("inputRequests").path("city");
            assertThat(city.path("method").asString()).isEqualTo("elicitation/create");
            assertThat(city.path("params").path("message").asString()).isEqualTo("Choose a forecast city");
            assertThat(city.path("params").path("requestedSchema").path("required").get(0).asString())
                    .isEqualTo("city");
        }
    }
}
