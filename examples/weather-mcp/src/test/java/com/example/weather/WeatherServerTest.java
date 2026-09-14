/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather;

import com.example.weather.service.WeatherService;
import dev.tachyonmcp.core.server.TachyonServer;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

class WeatherServerTest {

    private static final TestWeatherProvider weatherProvider = new TestWeatherProvider();
    private static final TestCityProvider cityProvider = new TestCityProvider();
    private static final WeatherService weatherService = new WeatherService(weatherProvider, cityProvider);
    private static TachyonServer handle;
    private static HttpClientStreamableHttpTransport clientTransport;
    private static McpSyncClient client;
    private static McpSchema.InitializeResult initResult;

    @BeforeAll
    static void beforeAll() {
        handle = WeatherServer.buildServer("localhost", 0, null, weatherService);
        handle.start();
        int port = handle.port();

        clientTransport = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port)
            .build();
        client = McpClient.sync(clientTransport)
            .elicitation(request -> new McpSchema.ElicitResult(
                McpSchema.ElicitResult.Action.ACCEPT, Map.of("city", "Tallinn")))
            .build();

        initResult = client.initialize();
    }

    @AfterAll
    static void afterAll() {
        if (client != null) {
            client.close();
        }
        if (clientTransport != null) {
            clientTransport.close();
        }
        if (handle != null) {
            handle.close();
        }
    }

    @Test
    void shouldGetServerInfo() {
        assertThat(initResult.serverInfo()).usingRecursiveComparison().ignoringFields("icons").isEqualTo(
            McpSchema.Implementation.builder("weather-server", "1.0")
                .title("Weather Server")
                .websiteUrl("https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp")
                .description("Weather MCP server")
                .build());
        assertThat(initResult.serverInfo().icons()).singleElement().satisfies(icon -> {
            assertThat(icon.src()).startsWith("data:image/png;base64,");
            assertThat(icon.mimeType()).isEqualTo("image/png");
            assertThat(icon.sizes()).containsExactly("256x256");
        });
        assertThat(initResult.protocolVersion()).isEqualTo("2025-11-25");
        assertThat(initResult.instructions()).isEqualTo("Test instructions");
        assertThat(initResult.capabilities()).isEqualTo(McpSchema.ServerCapabilities.builder()
            .tools(null)
            .resources(null, null)
            .prompts(null)
            .logging()
            .completions()
            .build());
    }

    @Test
    void shouldListTools() {
        final var result = client.listTools();
        assertThat(result).isNotNull();
        assertThat(result.tools()).hasSize(1);
        McpSchema.Tool tool = result.tools().getFirst();
        assertThat(tool.name()).isEqualTo("get-weather");
        assertThat(tool.title()).isEqualTo("Current Weather");
        assertThat(tool.description()).isEqualTo("Get current weather for a city");
        assertThat(tool.inputSchema()).isEqualTo(Map.of(
            "$schema", "https://json-schema.org/draft/2020-12/schema",
            "$id", "GetWeatherRequest",
            "description", "Input for looking up the current weather in a city.",
            "type", "object",
            "required", List.of("city", "units"),
            "additionalProperties", false,
            "properties", Map.of(
                "city", Map.of(
                    "description", "City name (e.g., London, Tokyo, New York)",
                    "type", "string"),
                "units", Map.of(
                    "description", "Temperature unit (default: celsius)",
                    "oneOf", List.of(
                        Map.of("type", "null"),
                        Map.of("$ref", "#/$defs/TemperatureUnit")))),
            "$defs", Map.of(
                "TemperatureUnit", Map.of(
                    "type", "string",
                    "description", "Unit used to represent temperature.",
                    "enum", List.of("celsius", "fahrenheit")))));
        assertThat(tool.outputSchema()).isEqualTo(Map.of(
            "$schema", "https://json-schema.org/draft/2020-12/schema",
            "$id", "GetWeatherResponse",
            "description", "Current weather observation for a city.",
            "type", "object",
            "required", List.of("city", "condition", "temperature", "unit", "humidity", "windSpeed"),
            "additionalProperties", false,
            "properties", Map.of(
                "city", Map.of("description", "City name", "type", "string"),
                "condition", Map.of("description", "Weather condition", "type", "string"),
                "temperature", Map.of("description", "Temperature in the response unit", "type", "number"),
                "unit", Map.of("description", "Temperature unit", "$ref", "#/$defs/TemperatureUnit"),
                "humidity", Map.of("description", "Relative humidity percentage", "type", "integer"),
                "windSpeed", Map.of("description", "Wind speed in km/h", "type", "number")),
            "$defs", Map.of(
                "TemperatureUnit", Map.of(
                    "type", "string",
                    "description", "Unit used to represent temperature.",
                    "enum", List.of("celsius", "fahrenheit")))));
        assertThat(tool.meta()).isNull();
    }

    @Test
    void shouldCallWeatherTool() {
        final var result = client.callTool(McpSchema.CallToolRequest.builder("get-weather")
            .arguments(Map.of("city", "London", "units", "celsius"))
            .build());

        assertThat(result).isNotNull();
        assertThat(result.structuredContent()).isEqualTo(Map.of(
            "city", "London",
            "condition", "Clear sky",
            "temperature", 18.5,
            "unit", "celsius",
            "humidity", 52,
            "windSpeed", 12.0));
    }

    @Test
    void shouldEmitProgressWhileFetchingWeather() {
        var progressNotifications = new CopyOnWriteArrayList<McpSchema.ProgressNotification>();
        var progressScheduler = Schedulers.newSingle("weather-progress");
        var progressClient = McpClient.async(HttpClientStreamableHttpTransport
            .builder("http://localhost:" + handle.port())
            .build())
            .progressConsumer(notification -> Mono.<Void>fromRunnable(() -> progressNotifications.add(notification))
                .subscribeOn(progressScheduler))
            .build();
        try {
            progressClient.initialize().block();
            var arguments = new HashMap<String, Object>();
            arguments.put("city", "London");
            arguments.put("units", null);
            final var result = progressClient.callTool(McpSchema.CallToolRequest.builder("get-weather")
                .arguments(arguments)
                .progressToken("weather-progress")
                .build())
                .block();

            assertThat(result.isError()).isNotEqualTo(true);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(progressNotifications)
                    .extracting(
                        McpSchema.ProgressNotification::progressToken,
                        McpSchema.ProgressNotification::progress,
                        McpSchema.ProgressNotification::total,
                        McpSchema.ProgressNotification::message)
                    .containsExactly(
                        tuple("weather-progress", 0.1, 1.0, "Fetching weather for London"),
                        tuple("weather-progress", 1.0, 1.0, "Weather retrieved for London")));
        } finally {
            progressClient.close();
            progressScheduler.dispose();
        }
    }

    @Test
    void shouldCallWeatherToolAfterElicitingAnotherCity() {
        var arguments = new HashMap<String, Object>();
        arguments.put("city", "Unknown");
        arguments.put("units", null);
        final var result = client.callTool(McpSchema.CallToolRequest.builder("get-weather")
            .arguments(arguments)
            .build());

        assertThat(result.structuredContent()).isEqualTo(Map.of(
            "city", "Tallinn",
            "condition", "Clear sky",
            "temperature", 18.5,
            "unit", "celsius",
            "humidity", 52,
            "windSpeed", 12.0));
    }

    @Test
    void shouldListResources() {
        final var result = client.listResources();

        assertThat(result.resources()).hasSize(2);

        var article = result.resources().stream()
            .filter(resource -> resource.uri().equals("weather://prediction/article"))
            .findFirst().orElseThrow();
        assertThat(article.uri()).isEqualTo("weather://prediction/article");
        assertThat(article.name()).isEqualTo("prediction-article");
        assertThat(article.title()).isEqualTo("Weather Prediction");
        assertThat(article.description()).isEqualTo("Weather prediction article");
        assertThat(article.mimeType()).isEqualTo("text/markdown");
        assertThat(article.size()).isEqualTo(weatherService.predictionArticle().getBytes(UTF_8).length);
        assertThat(article.annotations().audience())
            .containsExactly(McpSchema.Role.USER, McpSchema.Role.ASSISTANT);
        assertThat(article.annotations().priority()).isEqualTo(0.8);
        assertThat(article.annotations().lastModified()).isEqualTo("2026-07-23T00:00:00Z");
        assertThat(article.icons()).singleElement().satisfies(icon -> {
            assertThat(icon.src()).startsWith("data:image/png;base64,");
            assertThat(icon.mimeType()).isEqualTo("image/png");
            assertThat(icon.sizes()).containsExactly("256x256");
            assertThat(icon.theme()).isEqualTo("light");
        });

        var weather = result.resources().stream()
            .filter(resource -> resource.uri().equals("weather://featured/current"))
            .findFirst().orElseThrow();
        assertThat(weather.uri()).isEqualTo("weather://featured/current");
        assertThat(weather.name()).isEqualTo("featured-current-weather");
        assertThat(weather.title()).isEqualTo("Featured Current Weather");
        assertThat(weather.description()).isEqualTo("Current weather in Tallinn");
        assertThat(weather.mimeType()).isEqualTo("application/json");
        assertThat(weather.annotations()).isEqualTo(article.annotations());
        assertThat(weather.icons()).isEqualTo(article.icons());
    }

    @Test
    void shouldReadTextResource() {
        final var listResult = client.listResources();
        var article = listResult.resources().stream()
            .filter(r -> r.uri().equals("weather://prediction/article"))
            .findFirst().orElseThrow();

        final var result = client.readResource(article);

        var contents = result.contents().getFirst();
        assertThat(contents).isInstanceOf(McpSchema.TextResourceContents.class);
        var textContents = ((McpSchema.TextResourceContents) contents);
        assertThat(textContents.uri()).isEqualTo("weather://prediction/article");
        assertThat(textContents.mimeType()).isEqualTo("text/markdown");
        assertThat(textContents.text().trim())
            .startsWith("# Weather Prediction")
            .endsWith("reports, ocean buoys, and over 30 polar-orbiting and geostationary satellites.");
    }

    @Test
    void shouldReadCurrentWeatherResource() {
        final var listResult = client.listResources();
        var weather = listResult.resources().stream()
            .filter(r -> r.uri().equals("weather://featured/current"))
            .findFirst().orElseThrow();

        final var result = client.readResource(weather);

        var contents = result.contents().getFirst();
        assertThat(contents).isInstanceOf(McpSchema.TextResourceContents.class);
        var textContents = ((McpSchema.TextResourceContents) contents);
        assertThat(textContents.uri()).isEqualTo("weather://featured/current");
        assertThat(textContents.mimeType()).isEqualTo("application/json");
        assertThat(textContents.text()).contains("Clear sky");
    }

    @Test
    void shouldListResourceTemplates() {
        final var result = client.listResourceTemplates();

        assertThat(result.resourceTemplates()).hasSize(1);
        var template = result.resourceTemplates().getFirst();
        assertThat(template.uriTemplate()).isEqualTo("weather://current/{city}");
        assertThat(template.name()).isEqualTo("current-weather");
        assertThat(template.mimeType()).isEqualTo("application/json");
    }

    @Test
    void shouldReadCurrentWeatherFromTemplate() {
        final var result = client.readResource(
            McpSchema.ReadResourceRequest.builder("weather://current/London").build());

        var contents = result.contents().getFirst();
        assertThat(contents).isInstanceOf(McpSchema.TextResourceContents.class);
        var textContents = ((McpSchema.TextResourceContents) contents);
        assertThat(textContents.uri()).isEqualTo("weather://current/London");
        assertThat(textContents.mimeType()).isEqualTo("application/json");
        assertThat(textContents.text()).contains("Clear sky");
    }

    @Test
    void shouldReturnInvalidParamsWhenTemplateCityIsUnknown() {
        assertThatThrownBy(() -> client.readResource(
                McpSchema.ReadResourceRequest.builder("weather://current/Unknown").build()))
            .isInstanceOf(McpError.class)
            .extracting(e -> ((McpError) e).getJsonRpcError().code())
            .isEqualTo(-32602);
    }

    @Test
    void shouldCompleteCityNameForCurrentWeatherTemplate() {
        final var result = client.completeCompletion(McpSchema.CompleteRequest.builder(
                new McpSchema.ResourceReference("weather://current/{city}"),
                new McpSchema.CompleteRequest.CompleteArgument("city", "Lo"))
            .build());

        assertThat(result.completion().values()).containsExactlyInAnyOrder("London", "Los Angeles");
        assertThat(result.completion().hasMore()).isNotEqualTo(true);
    }

    @Test
    void shouldReturnEmptyCompletionForBlankQuery() {
        final var result = client.completeCompletion(McpSchema.CompleteRequest.builder(
                new McpSchema.ResourceReference("weather://current/{city}"),
                new McpSchema.CompleteRequest.CompleteArgument("city", ""))
            .build());

        assertThat(result.completion().values()).isEmpty();
    }

    @Test
    void shouldCompleteStyleNameForRewriteForecastPrompt() {
        final var result = client.completeCompletion(McpSchema.CompleteRequest.builder(
                new McpSchema.PromptReference("rewrite-forecast"),
                new McpSchema.CompleteRequest.CompleteArgument("style", "pi"))
            .build());

        assertThat(result.completion().values()).containsExactly("pirate");
    }

    @Test
    void shouldReturnEmptyCompletionForNonStyleArgumentOfRewriteForecastPrompt() {
        final var result = client.completeCompletion(McpSchema.CompleteRequest.builder(
                new McpSchema.PromptReference("rewrite-forecast"),
                new McpSchema.CompleteRequest.CompleteArgument("forecast", "Rain"))
            .build());

        assertThat(result.completion().values()).isEmpty();
    }

    @Test
    void shouldListPrompts() {
        final var result = client.listPrompts();

        assertThat(result.prompts()).hasSize(1);
        var prompt = result.prompts().getFirst();
        assertThat(prompt.name()).isEqualTo("rewrite-forecast");
        assertThat(prompt.description()).isEqualTo("Rewrites a weather forecast in a chosen style");
        assertThat(prompt.arguments()).hasSize(2);
    }

    @Test
    void shouldGetPrompt() {
        final var result = client.getPrompt(
            McpSchema.GetPromptRequest.builder("rewrite-forecast")
                .arguments(Map.of("forecast", "Rain in London", "style", "pirate"))
                .build());

        assertThat(result).isNotNull();
        assertThat(result.messages()).hasSize(1);
        var message = result.messages().getFirst();
        assertThat(message.role()).isEqualTo(McpSchema.Role.USER);
        assertThat(message.content()).isInstanceOf(McpSchema.TextContent.class);
        var textContent = ((McpSchema.TextContent) message.content());
        assertThat(textContent.text())
            .isEqualTo("Rewrite the following weather forecast in pirate style. Preserve factual details:\n\n```Rain in London\n```");
    }
}
