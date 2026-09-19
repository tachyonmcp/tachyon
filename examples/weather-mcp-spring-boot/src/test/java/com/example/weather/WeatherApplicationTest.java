/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.support.GenericApplicationContext;

class WeatherApplicationTest {
    private static final WeatherProvider FAKE_PROVIDER = new WeatherProvider() {
        public Weather current(String city) {
            return new Weather(city, "Clear sky", 20.0, TemperatureUnit.CELSIUS, 60, 10.0);
        }

        public List<String> cities(String prefix) {
            return List.of("Tallinn", "Tartu").stream()
                    .filter(city -> city.startsWith(prefix))
                    .toList();
        }
    };

    private final RecordingSpanExporter spans = new RecordingSpanExporter();

    static final class RecordingSpanExporter implements SpanExporter {
        final List<SpanData> exported = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            exported.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }

    @Test
    void servesWeatherInRequestedUnits() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"get-weather","arguments":{"city":"Tallinn","units":"FAHRENHEIT"}}}
                 """)).isSuccess().hasResult("""
                {"content":[{"type":"text","text":"{\\\"city\\\":\\\"Tallinn\\\",\\\"condition\\\":\\\"Clear sky\\\",\\\"temperature\\\":68.0,\\\"unit\\\":\\\"FAHRENHEIT\\\",\\\"humidity\\\":60,\\\"windSpeed\\\":10.0}"}],
                 "structuredContent":{"city":"Tallinn","condition":"Clear sky","temperature":68.0,"unit":"FAHRENHEIT","humidity":60,"windSpeed":10.0},
                     "resultType":"complete"}
                """);
        });
    }

    @Test
    void rewritesForecast() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"prompts/get",
                     "params":{"name":"rewrite-forecast","arguments":{"forecast":"Sunny","style":"PIRATE"}}}
                    """)).isSuccess().hasResult("""
                    {"description":"Rewrite a forecast in PLAIN, CONCISE, or PIRATE style","messages":[{"role":"user","content":{"type":"text","text":"Rewrite the following weather forecast in pirate style. Preserve factual details:\\n\\nSunny"}}],"resultType":"complete"}
                    """);
        });
    }

    @Test
    void completesForecastStyle() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":3,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"rewrite-forecast"},"argument":{"name":"style","value":"pi"}}}
                    """)).isSuccess().hasResult("""
                    {"completion":{"values":["PIRATE"]},"resultType":"complete"}
                    """);
        });
    }

    @Test
    void completesCity() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":4,"method":"completion/complete","params":{
                     "ref":{"type":"ref/resource","uri":"weather://current/{city}"},"argument":{"name":"city","value":"Ta"}}}
                    """)).isSuccess().hasResult("""
                    {"completion":{"values":["Tallinn","Tartu"]},"resultType":"complete"}
                    """);
        });
    }

    @Test
    void readsCurrentWeather() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"weather://current/Tallinn"}}
                    """)).isSuccess().hasResult("""
                {"cacheScope":"public","ttlMs":0,"contents":[{"uri":"weather://current/Tallinn","mimeType":"application/json","text":"{\\\"city\\\":\\\"Tallinn\\\",\\\"condition\\\":\\\"Clear sky\\\",\\\"temperature\\\":20.0,\\\"unit\\\":\\\"CELSIUS\\\",\\\"humidity\\\":60,\\\"windSpeed\\\":10.0}"}],"resultType":"complete"}
                """);
        });
    }

    @Test
    void defaultsToCelsius() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":6,"method":"tools/call",
                     "params":{"name":"get-weather","arguments":{"city":"Tallinn"}}}
                    """)).isSuccess().hasStructuredContent("""
                {"city":"Tallinn","condition":"Clear sky","temperature":20.0,"unit":"CELSIUS","humidity":60,"windSpeed":10.0}
                """);
        });
    }

    @Test
    void rejectsUnknownTemperatureUnit() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":7,"method":"tools/call",
                     "params":{"name":"get-weather","arguments":{"city":"Tallinn","units":"kelvin"}}}
                    """)).isJsonRpcError().hasErrorCode(-32602);
        });
    }

    @Test
    void rejectsMissingCity() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":8,"method":"tools/call",
                     "params":{"name":"get-weather","arguments":{}}}
                    """)).isJsonRpcError().hasErrorCode(-32602);
        });
    }

    @Test
    void ignoresShortCityPrefix() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":9,"method":"completion/complete","params":{
                     "ref":{"type":"ref/resource","uri":"weather://current/{city}"},"argument":{"name":"city","value":"T"}}}
                    """)).isSuccess().hasResult("""
                    {"completion":{"values":[]},"resultType":"complete"}
                    """);
        });
    }

    @Test
    void rejectsLowercaseForecastStyle() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":11,"method":"prompts/get",
                     "params":{"name":"rewrite-forecast","arguments":{"forecast":"Sunny","style":"pirate"}}}
                    """))
                    .isJsonRpcError()
                    .hasErrorCode(-32602)
                    .hasErrorMessage("invalid argument 'style': must be one of [PLAIN, CONCISE, PIRATE]");
        });
    }

    @Test
    void ignoresCompletionForForecastText() {
        withWeather((context, client) -> {
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":10,"method":"completion/complete","params":{
                     "ref":{"type":"ref/prompt","name":"rewrite-forecast"},"argument":{"name":"forecast","value":"pi"}}}
                    """)).isSuccess().hasResult("""
                    {"completion":{"values":[],"hasMore":false},"resultType":"complete"}
                    """);
        });
    }

    @Test
    void observesWeatherOperations() {
        withWeather((context, client) -> {
            final var server = context.getBean(TachyonServer.class);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                 "params":{"name":"get-weather","arguments":{"city":"Tallinn","units":"FAHRENHEIT"}}}
                 """)).isSuccess().hasResult("""
                {"content":[{"type":"text","text":"{\\\"city\\\":\\\"Tallinn\\\",\\\"condition\\\":\\\"Clear sky\\\",\\\"temperature\\\":68.0,\\\"unit\\\":\\\"FAHRENHEIT\\\",\\\"humidity\\\":60,\\\"windSpeed\\\":10.0}"}],
                 "structuredContent":{"city":"Tallinn","condition":"Clear sky","temperature":68.0,"unit":"FAHRENHEIT","humidity":60,"windSpeed":10.0},
                     "resultType":"complete"}
                """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":2,"method":"prompts/get",
                     "params":{"name":"rewrite-forecast","arguments":{"forecast":"Sunny","style":"PIRATE"}}}
                    """)).isSuccess().hasResult("""
                    {"description":"Rewrite a forecast in PLAIN, CONCISE, or PIRATE style","messages":[{"role":"user","content":{"type":"text","text":"Rewrite the following weather forecast in pirate style. Preserve factual details:\\n\\nSunny"}}],"resultType":"complete"}
                    """);
            // language=json
            assertThat(client.post("""
                    {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"weather://current/Tallinn"}}
                    """)).isSuccess().hasResult("""
                {"cacheScope":"public","ttlMs":0,"contents":[{"uri":"weather://current/Tallinn","mimeType":"application/json","text":"{\\\"city\\\":\\\"Tallinn\\\",\\\"condition\\\":\\\"Clear sky\\\",\\\"temperature\\\":20.0,\\\"unit\\\":\\\"CELSIUS\\\",\\\"humidity\\\":60,\\\"windSpeed\\\":10.0}"}],"resultType":"complete"}
                """);
            final var health = context.getBean("tachyonHealthIndicator", HealthIndicator.class)
                    .health();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("port", server.port());
            assertThat(server.config().observability().listeners())
                    .extracting(listener -> listener.getClass().getSimpleName())
                    .contains("McpOpenTelemetryListener", "TachyonMetricsListener");
            final var registry = context.getBean(MeterRegistry.class);
            await().untilAsserted(() -> assertThat(registry.find("mcp.server.operations")
                            .tag("mcp.method.name", "tools/call")
                            .timers())
                    .isNotEmpty());
            assertThat(registry.get("mcp.server.tools").gauge().value()).isEqualTo(1.0);
            assertThat(registry.get("mcp.server.prompts").gauge().value()).isEqualTo(1.0);
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(spans.exported)
                            .extracting(SpanData::getName)
                            .contains("tools/call get-weather", "prompts/get rewrite-forecast", "resources/read"));
        });
    }

    @FunctionalInterface
    private interface WeatherScenario {
        void accept(AssertableApplicationContext context, Mcp20260728Client client) throws Exception;
    }

    private void withWeather(WeatherScenario scenario) {
        new ApplicationContextRunner()
                .withUserConfiguration(WeatherApplication.class)
                .withPropertyValues(
                        "tachyon.port=0",
                        "tachyon.host=127.0.0.1",
                        "management.tracing.sampling.probability=1.0",
                        "management.otlp.metrics.export.enabled=false",
                        "management.tracing.export.otlp.enabled=false")
                .withBean(SpanExporter.class, () -> spans)
                .withBean(WeatherProvider.class, () -> FAKE_PROVIDER)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final var server = context.getBean(TachyonServer.class);
                    try (final var client = McpTestClients.latest(server.port())) {
                        scenario.accept(context, client);
                    }
                });
    }

    @Test
    void actuatorServesHealthAndMetricsOverHttp() throws Exception {
        try (var context = new SpringApplicationBuilder(WeatherApplication.class)
                        .initializers(ctx -> ((GenericApplicationContext) ctx)
                                .registerBean(WeatherProvider.class, () -> FAKE_PROVIDER))
                        .run(
                                "--server.port=0",
                                "--tachyon.port=0",
                                "--management.otlp.metrics.export.enabled=false",
                                "--management.tracing.export.otlp.enabled=false");
                var http = HttpClient.newHttpClient()) {
            final var port = context.getEnvironment().getProperty("local.server.port", Integer.class);
            assertThat(port).isNotNull().isPositive();
            assertThat(context.getBean(TachyonServer.class).port()).isNotEqualTo(port);

            final var health = http.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.body()).contains("\"status\":\"UP\"", "\"tachyon\"");

            final var tools = http.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://127.0.0.1:" + port + "/actuator/metrics/mcp.server.tools"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(tools.statusCode()).isEqualTo(200);
            assertThat(tools.body()).contains("\"name\":\"mcp.server.tools\"", "\"value\":1.0");
        }
    }
}
