package dev.tachyonmcp.docs.annotations;

import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.named;
import static dev.tachyonmcp.docs.JsonRpc.names;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WeatherServiceTest {

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(b -> b.annotations(a -> a.register(new WeatherService())), s -> {});
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void everyAnnotatedMethodIsRegisteredUnderItsMethodName() throws Exception {
        assertThat(names(result(server, "tools/list"), "tools")).containsExactlyInAnyOrder("forecast", "greet");
        assertThat(names(result(server, "prompts/list"), "prompts")).containsExactlyInAnyOrder("trip", "opener");
        var templates = result(server, "resources/templates/list").path("resourceTemplates");
        assertThat(items(templates))
                .extracting(t -> t.path("uriTemplate").asString())
                .containsExactly("weather://cities/{city}");
    }

    @Test
    void recordArgumentBecomesTheInputSchemaAndRecordResultBecomesStructuredContent() throws Exception {
        var forecast = named(result(server, "tools/list"), "tools", "forecast");

        assertThat(forecast.path("description").asString()).isEqualTo("Forecast for a city");
        var properties = forecast.path("inputSchema").path("properties");
        assertThat(properties.path("city").path("type").asString()).isEqualTo("string");
        assertThat(properties.path("days").path("type").asString()).isEqualTo("integer");
        assertThat(forecast.path("outputSchema").path("properties").path("celsius").path("type").asString())
                .isEqualTo("number");

        var call = result(server, "tools/call", """
                {"name":"forecast","arguments":{"city":"Oslo","days":3}}
                """);
        assertThat(call.path("structuredContent").path("city").asString()).isEqualTo("Oslo");
        assertThat(call.path("structuredContent").path("days").asInt()).isEqualTo(3);
        assertThat(call.path("structuredContent").path("celsius").asDouble()).isEqualTo(18.5);
    }

    @Test
    void namedParametersAreRequiredUnlessNullableAndContextIsNeverAdvertised() throws Exception {
        var schema = named(result(server, "tools/list"), "tools", "greet").path("inputSchema");

        assertThat(items(schema.path("required"))).extracting(n -> n.asString()).containsExactly("name");
        assertThat(schema.path("properties").propertyNames()).containsExactlyInAnyOrder("name", "title");

        assertThat(text(result(server, "tools/call", """
                {"name":"greet","arguments":{"name":"Ada"}}
                """))).isEqualTo("Hello, Ada");
        assertThat(text(result(server, "tools/call", """
                {"name":"greet","arguments":{"name":"Ada","title":"Dr."}}
                """))).isEqualTo("Hello, Dr. Ada");
    }

    @Test
    void resourceTemplateVariableBindsToTheSameNamedParameter() throws Exception {
        var contents = result(server, "resources/read", """
                {"uri":"weather://cities/Oslo"}
                """).path("contents").get(0);

        assertThat(contents.path("uri").asString()).isEqualTo("weather://cities/Oslo");
        assertThat(contents.path("text").asString()).isEqualTo("Oslo has a temperate climate");
    }

    @Test
    void promptArgumentsAreRequiredUnlessOptionalAndRoleDefaultsToUser() throws Exception {
        var trip = named(result(server, "prompts/list"), "prompts", "trip");
        assertThat(items(trip.path("arguments")))
                .extracting(a -> a.path("name").asString() + ":" + a.path("required").asBoolean())
                .containsExactlyInAnyOrder("city:true", "season:false");

        var year = result(server, "prompts/get", """
                {"name":"trip","arguments":{"city":"Oslo"}}
                """).path("messages").get(0);
        assertThat(year.path("role").asString()).isEqualTo("user");
        assertThat(year.path("content").path("text").asString()).isEqualTo("Plan a year-round trip to Oslo");

        var winter = result(server, "prompts/get", """
                {"name":"trip","arguments":{"city":"Oslo","season":"winter"}}
                """).path("messages").get(0);
        assertThat(winter.path("content").path("text").asString()).isEqualTo("Plan a winter trip to Oslo");

        var opener = result(server, "prompts/get", """
                {"name":"opener","arguments":{"city":"Oslo"}}
                """).path("messages").get(0);
        assertThat(opener.path("role").asString()).isEqualTo("assistant");
        assertThat(opener.path("content").path("text").asString()).isEqualTo("Welcome to Oslo!");
    }

    @Test
    void builderRegistersTheServiceFeatures() {
        try (var built = WeatherServer.build()) {
            assertThat(built.tools().find("forecast")).isPresent();
            assertThat(built.tools().find("greet")).isPresent();
            assertThat(built.prompts().find("trip")).isPresent();
            assertThat(built.prompts().find("opener")).isPresent();
        }
    }
}
