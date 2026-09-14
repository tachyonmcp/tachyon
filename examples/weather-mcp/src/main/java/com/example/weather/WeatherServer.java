/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather;

import com.example.weather.integration.OpenMeteoProvider;
import com.example.weather.model.RewriteForecastPromptRequest;
import com.example.weather.service.NarrationStyle;
import com.example.weather.service.WeatherService;
import com.example.weather.spi.CityNotFoundException;
import com.example.weather.spi.WeatherObservation;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.UriTemplateValue;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptRequest;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.CapabilitiesConfig;
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener;
import io.opentelemetry.exporter.logging.LoggingMetricExporter;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.semconv.ServiceAttributes;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

public final class WeatherServer {

    private static final Logger log = LoggerFactory.getLogger(WeatherServer.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOGO = classpathDataUri("/images/logo.png", "image/png");
    static final String SUN_AND_CLOUD = classpathDataUri("/images/sun-and-cloud.png", "image/png");

    private static final WeatherService weatherService;

    /**
     * Prints spans/metrics to the log with zero external infra, and also ships them via the
     * standard OTLP/HTTP exporter (default endpoint {@code http://localhost:4318}) -- point a
     * local collector (Jaeger, Grafana Tempo, Honeycomb, ...) there to see them land. With no
     * collector running, the OTLP exporter logs periodic export failures; that's expected and
     * harmless, and only the logging exporter's output matters for this demo.
     */
    private static final Resource OTEL_RESOURCE = Resource.getDefault().toBuilder()
            .put(ServiceAttributes.SERVICE_NAME, "weather-mcp")
            .build();

    private static final OpenTelemetrySdk OTEL = OpenTelemetrySdk.builder()
            .setTracerProvider(SdkTracerProvider.builder()
                    .setResource(OTEL_RESOURCE)
                    .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                    .addSpanProcessor(BatchSpanProcessor.builder(
                                    OtlpHttpSpanExporter.builder().build())
                            .build())
                    .build())
            .setMeterProvider(SdkMeterProvider.builder()
                    .setResource(OTEL_RESOURCE)
                    .registerMetricReader(PeriodicMetricReader.builder(LoggingMetricExporter.create())
                            .setInterval(Duration.ofSeconds(10))
                            .build())
                    .registerMetricReader(PeriodicMetricReader.builder(
                                    OtlpHttpMetricExporter.builder().build())
                            .setInterval(Duration.ofSeconds(10))
                            .build())
                    .build())
            .build();

    static {
        HttpClient httpClient = HttpClient.newBuilder()
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
        final var openMeteoProvider = new OpenMeteoProvider(httpClient);
        weatherService = new WeatherService(openMeteoProvider, openMeteoProvider);
    }

    public static void main(String... args) {
        final var server = buildServer(
            System.getenv().getOrDefault("HOST", "localhost"),
            Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")),
            System.getenv("ALLOWED_HOST"),
            weatherService
        );
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(OTEL::close));
        log.info("Connect your MCP client to http://{}:{}/mcp", server.host(), server.port());
    }

    static TachyonServer buildServer(
        String host, int port, @Nullable String allowedHost, WeatherService weatherService
    ) {
        var predictionArticle = weatherService.predictionArticle();
        var resourceAnnotations =
            Annotations.of(List.of(Role.USER, Role.ASSISTANT), 0.8, "2026-07-23T00:00:00Z");
        var resourceIcon = Icon.of(LOGO, "image/png", List.of("256x256"), "light");
        return TachyonServer.builder()
                .host(host)
                .port(port)
            .network(n -> {
                if (allowedHost != null && !allowedHost.isBlank()) {
                    n.allowedHosts(allowedHost);
                }
            })
                .info(it -> it
                        .name("weather-server")
                        .title("Weather Server")
                        .description("Weather MCP server")
                        .websiteUrl("https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp")
                        .instructions("Test instructions")
                        .icons(Icon.of(LOGO, "image/png", List.of("256x256"), null))
                        .version("1.0"))
                .capabilities(CapabilitiesConfig.Builder::logging)
                .observability(o -> o
                        .slowRequestLogging()
                        .listener(McpOpenTelemetryListener.create(OTEL))
                        .payloadCapture(p -> p.requestArgs(true)
                                .responseContent(true)
                                .rawMessage(true)
                                .exceptionDetail(true)))

                .withTools(tools -> tools.register(GetWeatherTool.DESCRIPTOR, GetWeatherTool.fn(weatherService)))

                .withResources(resources -> resources.register(
                        resource -> resource.name("prediction-article")
                                .uri("weather://prediction/article")
                                .description("Weather prediction article")
                                .title("Weather Prediction")
                                .annotations(resourceAnnotations)
                                .size(predictionArticle.getBytes(StandardCharsets.UTF_8).length)
                                .icons(List.of(resourceIcon))
                                .mimeType("text/markdown"),
                        (ctx, request) ->
                                TextResourceContents.of(request.uri(), predictionArticle, "text/markdown"))

                .registerAsync(
                        resource -> resource.name("featured-current-weather")
                                .uri("weather://featured/current")
                                .description("Current weather in Tallinn")
                                .title("Featured Current Weather")
                                .annotations(resourceAnnotations)
                                .icons(List.of(resourceIcon))
                                .mimeType("application/json"),
                        (ctx, request) -> weatherService.currentWeatherAsync("Tallinn")
                                .thenApply(weather ->
                                        TextResourceContents.of(request.uri(), asJson(weather), "application/json"))))

                .withPrompts(prompts -> prompts.register(
                        prompt -> prompt.name("rewrite-forecast")
                                .description("Rewrites a weather forecast in a chosen style")
                                .addArguments(
                                        PromptArgument.of("forecast", "Forecast", "Weather forecast to rewrite", true),
                                        PromptArgument.of("style", "Style", "plain, concise, or pirate", true))
                                .inputSchema(JsonSchema.generate(RewriteForecastPromptRequest.class)),
                        (ctx, request) -> rewriteForecast(weatherService, request)))

                .withResources(resources -> resources.registerTemplate(
                        template -> template.name("current-weather")
                                .uriTemplate("weather://current/{city}")
                                .title("Weather in the city")
                                .description("Weather forecast for a city")
                                .mimeType("application/json"),
                        (ctx, request) ->
                                handleWeatherTemplate(weatherService, request.uri(), request.params())))

                .withCompletions(completions -> completions
                        .registerForPrompt("rewrite-forecast", (ctx, request) -> completeStyle(request))
                        .registerForResourceAsync(
                                "weather://current/{city}",
                                (ctx, request) -> {
                                    if (!"city".equals(request.argumentName())) {
                                        return CompletableFuture.completedFuture(CompletionResult.of(List.of()));
                                    }
                                    return weatherService.searchCities(request.argumentValue())
                                            .thenApply(CompletionResult::of)
                                            .exceptionally(e -> CompletionResult.of(List.of()));
                                }))
                .session(session -> session.enabled(true))
                .build();
    }

    private WeatherServer() {
    }

    private static CompletionResult completeStyle(CompletionRequest request) {
        if (!"style".equals(request.argumentName())) {
            return CompletionResult.of(List.of());
        }
        var query = request.argumentValue().toLowerCase(Locale.ROOT);
        var matches = NarrationStyle.styleNames().stream()
            .filter(style -> style.startsWith(query))
            .toList();
        return CompletionResult.of(matches);
    }

    private static PromptResult rewriteForecast(WeatherService weatherService, PromptRequest request) {
        var arguments = request.arguments();
        var forecast = arguments.stringOr("forecast", "");
        var style = NarrationStyle.from(arguments.stringOr("style", ""));
        return PromptResult.messages(List.of(PromptMessage.user(weatherService.rewriteForecastInstruction(forecast, style))));
    }

    private static ResourceContents handleWeatherTemplate(
            WeatherService weatherService,
            String uri,
            Map<String, UriTemplateValue> params) {
        var city = ((UriTemplateValue.Scalar) params.get("city")).value();
        try {
            return TextResourceContents.builder()
                .text(asJson(weatherService.currentWeather(city)))
                .mimeType("application/json")
                .uri(uri)
                .build();
        } catch (CityNotFoundException e) {
            throw new InvalidArgumentException("city", e.getMessage());
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not get weather", e);
        }
    }

    private static String asJson(WeatherObservation weather) {
        try {
            return MAPPER.writeValueAsString(weather);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize weather", e);
        }
    }

    private static String classpathDataUri(String path, String mimeType) {
        try (var image = WeatherServer.class.getResourceAsStream(path)) {
            if (image == null) {
                throw new IllegalStateException("Missing classpath resource: " + path);
            }
            return "data:%s;base64,%s".formatted(mimeType, Base64.getEncoder().encodeToString(image.readAllBytes()));
        } catch (IOException e) {
            throw new IllegalStateException("Could not read classpath resource: " + path, e);
        }
    }

}
