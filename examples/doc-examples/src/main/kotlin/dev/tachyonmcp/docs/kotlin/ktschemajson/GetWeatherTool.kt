package dev.tachyonmcp.docs.kotlin.ktschemajson

import com.example.weather.model.GetWeatherRequest
import com.example.weather.model.GetWeatherResponse
import com.example.weather.model.TemperatureUnit
import com.example.weather.spi.WeatherObservation
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.TachyonServer
import dev.tachyonmcp.kotlin.server.buildServer
import dev.tachyonmcp.kotlin.server.config.ToolScope
import dev.tachyonmcp.kotlin.server.domain.stringOrNull
import dev.tachyonmcp.kotlin.server.features.tools.ToolDescriptor
import dev.tachyonmcp.kotlin.server.json.KxSerializationSerde
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator

// snips-start: ktschema_descriptor
private val schemaGenerator =
    ReflectionClassJsonSchemaGenerator(
        json = kotlinx.serialization.json.Json { encodeDefaults = false },
        config = JsonSchemaConfig.Default,
    )

val getWeatherToolDescriptor =
    ToolDescriptor {
        name = "get-weather"
        title = "Current Weather"
        description = "Get current weather for a city"
        inputSchema(schemaGenerator.generateSchemaString(GetWeatherRequest::class))
        outputSchema(schemaGenerator.generateSchemaString(GetWeatherResponse::class))
    }
// snips-end: ktschema_descriptor

fun ToolScope.getWeather(weatherService: WeatherService): ToolResult {
    val city = arguments.stringValue("city")
    val progressToken = request.progressToken()
    val temperatureUnit =
        when (arguments.stringOrNull("units")?.lowercase()) {
            "fahrenheit" -> TemperatureUnit.Fahrenheit
            else -> TemperatureUnit.Celsius
        }

    // snips-start: ktschema_attempt
    fun attempt(city: String): ToolResult =
        try {
            ToolResult.structured(
                toResponse(
                    city,
                    fetchWithProgress(
                        ctx,
                        progressToken,
                        weatherService,
                        city,
                        temperatureUnit,
                    ),
                ),
            )
        } catch (e: Exception) {
            if (e is CityNotFoundException) throw e
            internalError(e)
        }
    // snips-end: ktschema_attempt

    return attempt(city)
}

// snips-start: ktschema_to_response
private fun toResponse(city: String, weather: WeatherObservation): GetWeatherResponse =
    GetWeatherResponse(
        city = city,
        condition = weather.condition,
        temperature = weather.temperature,
        temperatureUnit = weather.temperatureUnit,
        humidity = weather.humidity,
        windSpeed = weather.windSpeed,
    )
// snips-end: ktschema_to_response

fun assembleServer(
    port: Int,
    weatherService: WeatherService,
): TachyonServer {
    // snips-start: ktschema_build_server
    return buildServer {
        network { this.port = port }
        json { serde = KxSerializationSerde.Default }

        tool(getWeatherToolDescriptor) { getWeather(weatherService) }
    }
    // snips-end: ktschema_build_server
}

// snips-start: ktschema_city_schema
private val CITY_SCHEMA =
    JsonSchema.parse(
        schemaGenerator.generateSchemaString(CityElicitationInput::class),
    )

private data class CityElicitationInput(
    val city: String,
)
// snips-end: ktschema_city_schema

internal fun citySchema(): JsonSchema = CITY_SCHEMA
