/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather

import com.example.weather.integration.OpenMeteoProvider
import com.example.weather.model.TemperatureUnit
import com.example.weather.service.NarrationStyle
import com.example.weather.service.WeatherService
import com.example.weather.spi.CityNotFoundException
import com.example.weather.spi.WeatherObservation
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.domain.Args
import dev.tachyonmcp.api.server.domain.InvalidArgumentException
import dev.tachyonmcp.api.server.domain.PromptMessage
import dev.tachyonmcp.api.server.domain.Role
import dev.tachyonmcp.api.server.features.completions.CompletionResult
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.domain.Annotations
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.features.prompts.PromptDescriptor
import dev.tachyonmcp.kotlin.server.features.resources.ResourceDescriptor
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import dev.tachyonmcp.opentelemetry.McpOpenTelemetryListener
import io.opentelemetry.exporter.logging.LoggingMetricExporter
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.ServiceAttributes
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors

private val log = LoggerFactory.getLogger("com.example.weather.WeatherServer")
private val MAPPER = ObjectMapper()
internal val LOGO by lazy { classpathDataUri("/images/logo.png", "image/png") }
internal val SUN_AND_CLOUD by lazy { classpathDataUri("/images/sun-and-cloud.png", "image/png") }

private val schemaGenerator =
    ReflectionClassJsonSchemaGenerator(
        json = kotlinx.serialization.json.Json { encodeDefaults = false },
        config = JsonSchemaConfig.Default,
    )

/**
 * Prints spans/metrics to the log with zero external infra, and also ships them via the
 * standard OTLP/HTTP exporter (default endpoint `http://localhost:4318`) -- point a local
 * collector (Jaeger, Grafana Tempo, Honeycomb, ...) there to see them land. With no collector
 * running, the OTLP exporter logs periodic export failures; that's expected and harmless, and
 * only the logging exporter's output matters for this demo.
 */
private val openTelemetryResource =
    Resource
        .getDefault()
        .toBuilder()
        .put(ServiceAttributes.SERVICE_NAME, "weather-mcp-kotlin")
        .build()

private val openTelemetry =
    OpenTelemetrySdk
        .builder()
        .setTracerProvider(
            SdkTracerProvider
                .builder()
                .setResource(openTelemetryResource)
                .addSpanProcessor(SimpleSpanProcessor.create(LoggingSpanExporter.create()))
                .addSpanProcessor(
                    BatchSpanProcessor
                        .builder(OtlpHttpSpanExporter.builder().build())
                        .build(),
                ).build(),
        ).setMeterProvider(
            SdkMeterProvider
                .builder()
                .setResource(openTelemetryResource)
                .registerMetricReader(
                    PeriodicMetricReader
                        .builder(LoggingMetricExporter.create())
                        .setInterval(Duration.ofSeconds(10))
                        .build(),
                ).registerMetricReader(
                    PeriodicMetricReader
                        .builder(OtlpHttpMetricExporter.builder().build())
                        .setInterval(Duration.ofSeconds(10))
                        .build(),
                ).build(),
        ).build()

private data class NarrationStyleInput(
    val forecast: String,
    val style: NarrationStyle,
)

fun main() {
    val server =
        assembleServer(
            port = System.getenv("PORT")?.toInt() ?: 8080,
            host = System.getenv("HOST") ?: "localhost",
            allowedHost = System.getenv("ALLOWED_HOST"),
        )
    server.start()
    Runtime.getRuntime().addShutdownHook(Thread { openTelemetry.close() })
    log.info("Connect your MCP client to http://{}:{}/mcp", server.host(), server.port())
}

fun createWeatherService(): WeatherService {
    val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build()
    val openMeteoProvider = OpenMeteoProvider(httpClient)
    return WeatherService(openMeteoProvider, openMeteoProvider)
}

fun assembleServer(
    port: Int,
    weatherService: WeatherService = createWeatherService(),
    host: String = "localhost",
    allowedHost: String? = null,
): TachyonServer {
    val predictionArticle = weatherService.predictionArticle
    val resourceAnnotations =
        Annotations {
            audience = listOf(Role.USER, Role.ASSISTANT)
            priority = 0.8
            lastModified = "2026-07-23T00:00:00Z"
        }
    val resourceIcon =
        Icon {
            src = LOGO
            mimeType = "image/png"
            sizes = listOf("256x256")
            theme = "light"
        }
    return buildServer {
        network {
            this.host = host
            this.port = port
            if (!allowedHost.isNullOrBlank()) allowedHosts += allowedHost
        }
        json { serde = KxSerializationSerde.Default }
        info {
            name = "weather-server-kotlin"
            title = "Weather Server (Kotlin)"
            description = "Weather MCP server built with Tachyon Kotlin DSL"
            websiteUrl =
                "https://github.com/tachyonmcp/tachyon/tree/main/examples/weather-mcp-kotlin"
            instructions = "Test instructions"
            icons += Icon(LOGO, "image/png", listOf("256x256"))
            version = "1.0"
        }
        session { enabled = true }
        observability {
            slowRequestLogging()
            listener(McpOpenTelemetryListener.create(openTelemetry))

            payloadCapture {
                requestArgs = true
                responseContent = true
                rawMessage = true
                exceptionDetail = true
            }
        }

        tool(getWeatherToolDescriptor) { getWeather(weatherService) }

        resource(
            ResourceDescriptor {
                name = "prediction-article"
                uri = "weather://prediction/article"
                description = "Weather prediction article"
                mimeType = "text/markdown"
                title = "Weather Prediction"
                annotations = resourceAnnotations
                size = predictionArticle.toByteArray().size.toLong()
                icons = listOf(resourceIcon)
            },
        ) {
            TextResourceContents { text = weatherService.predictionArticle }
        }

        resource(
            ResourceDescriptor {
                name = "featured-current-weather"
                uri = "weather://featured/current"
                description = "Current weather in Tallinn"
                mimeType = "application/json"
                title = "Featured Current Weather"
                annotations = resourceAnnotations
                icons = listOf(resourceIcon)
            },
        ) {
            TextResourceContents {
                text = asJson(weatherService.currentWeather("Tallinn", TemperatureUnit.Celsius))
            }
        }

        prompt(rewriteForecastPromptDescriptor()) {
            rewriteForecast(weatherService, arguments)
        }

        promptCompletion("rewrite-forecast") {
            completeStyle(
                request.argumentName(),
                request.argumentValue(),
            )
        }

        resourceTemplate(
            name = "current-weather",
            uriTemplate = "weather://current/{city}",
            title = "Weather in the city",
            description = "Weather forecast for a city",
            mimeType = "application/json",
        ) {
            TextResourceContents {
                text =
                    handleWeatherTemplate(weatherService, param("city"))
            }
        }

        resourceCompletion("weather://current/{city}") {
            if (request.argumentName() != "city") {
                CompletionResult.empty()
            } else {
                CompletionResult.of(weatherService.searchCities(request.argumentValue()))
            }
        }
    }
}

private fun rewriteForecastPromptDescriptor(): PromptDescriptor =
    PromptDescriptor {
        name = "rewrite-forecast"
        description = "Rewrites a weather forecast in a chosen style"
        argument {
            name = "forecast"
            title = "Forecast"
            description = "Weather forecast to rewrite"
            required = true
        }
        argument {
            name = "style"
            title = "Style"
            description = "plain, concise, or pirate"
            required = true
        }
        inputSchema =
            JsonSchema.parse(schemaGenerator.generateSchemaString(NarrationStyleInput::class))
    }

private fun rewriteForecast(
    weatherService: WeatherService,
    arguments: Args,
): List<PromptMessage> {
    val forecast = arguments.stringValue("forecast")
    val style = NarrationStyle.from(arguments.stringValue("style"))
    return listOf(PromptMessage.user(weatherService.rewriteForecastInstruction(forecast, style)))
}

private fun completeStyle(
    argumentName: String,
    argumentValue: String,
): CompletionResult {
    if (argumentName != "style") return CompletionResult.of(emptyList())
    val query = argumentValue.lowercase(Locale.ROOT)
    val matches = NarrationStyle.styleNames().filter { it.startsWith(query) }
    return CompletionResult.of(matches)
}

private fun handleWeatherTemplate(
    weatherService: WeatherService,
    city: String,
): String =
    try {
        asJson(weatherService.currentWeather(city, TemperatureUnit.Celsius))
    } catch (e: CityNotFoundException) {
        throw InvalidArgumentException("city", e.message ?: "City not found: $city", e)
    } catch (e: Exception) {
        restoreInterruptStatus(e)
        throw IllegalStateException("Could not get weather", e)
    }

private fun asJson(weather: WeatherObservation): String =
    try {
        MAPPER.writeValueAsString(weather)
    } catch (e: Exception) {
        throw IllegalStateException("Could not serialize weather", e)
    }

private object ClasspathResources

private fun classpathDataUri(
    path: String,
    mimeType: String,
): String {
    val bytes =
        ClasspathResources.javaClass.getResourceAsStream(path)?.use { it.readBytes() }
            ?: error("Missing classpath resource: $path")
    return "data:$mimeType;base64,${Base64.getEncoder().encodeToString(bytes)}"
}
