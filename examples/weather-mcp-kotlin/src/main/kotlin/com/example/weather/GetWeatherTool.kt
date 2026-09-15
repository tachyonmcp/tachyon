/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather

import com.example.weather.model.GetWeatherRequest
import com.example.weather.model.GetWeatherResponse
import com.example.weather.model.TemperatureUnit
import com.example.weather.model.TemperatureUnit.Celsius
import com.example.weather.model.TemperatureUnit.Fahrenheit
import com.example.weather.service.WeatherService
import com.example.weather.spi.CityNotFoundException
import com.example.weather.spi.WeatherObservation
import dev.tachyonmcp.api.json.JsonSchema
import dev.tachyonmcp.api.runtime.ElicitationRequest
import dev.tachyonmcp.api.runtime.ElicitationResult
import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.domain.InvalidArgumentException
import dev.tachyonmcp.api.server.domain.ProgressToken
import dev.tachyonmcp.api.server.features.tools.ToolResult
import dev.tachyonmcp.kotlin.server.config.ToolScope
import dev.tachyonmcp.kotlin.server.domain.Icon
import dev.tachyonmcp.kotlin.server.domain.ToolAnnotations
import dev.tachyonmcp.kotlin.server.domain.stringOrNull
import dev.tachyonmcp.kotlin.server.features.tools.ToolDescriptor
import me.kpavlov.kt.schema.generator.json.JsonSchemaConfig
import me.kpavlov.kt.schema.generator.json.ReflectionClassJsonSchemaGenerator
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = LoggerFactory.getLogger("com.example.weather.GetWeatherTool")
private const val ELICITATION_TIMEOUT_SECONDS = 600L

private val schemaGenerator =
    ReflectionClassJsonSchemaGenerator(
        json = kotlinx.serialization.json.Json { encodeDefaults = false },
        config = JsonSchemaConfig.Default,
    )

private val CITY_SCHEMA =
    JsonSchema.parse(
        schemaGenerator.generateSchemaString(CityElicitationInput::class),
    )

private data class CityElicitationInput(
    val city: String,
)

val getWeatherToolDescriptor =
    ToolDescriptor {
        name = "get-weather"
        title = "Current Weather"
        description = "Get current weather for a city"
        inputSchema(schemaGenerator.generateSchemaString(GetWeatherRequest::class))
        outputSchema(schemaGenerator.generateSchemaString(GetWeatherResponse::class))
        icons = listOf(Icon(SUN_AND_CLOUD, "image/png", listOf("128x128")))
        annotations =
            ToolAnnotations(
                readOnlyHint = true,
                destructiveHint = false,
                idempotentHint = true,
                openWorldHint = true,
            )
    }

fun ToolScope.getWeather(weatherService: WeatherService): ToolResult {
    val city = arguments.stringValue("city")
    if (city.isBlank()) {
        throw InvalidArgumentException("city", "must not be blank")
    }
    val units = arguments.stringOrNull("units")
    val progressToken = request.progressToken()
    val temperatureUnit =
        when (units?.lowercase(Locale.getDefault())) {
            "celsius" -> {
                Celsius
            }

            "fahrenheit" -> {
                Fahrenheit
            }

            else -> {
                Celsius
            }
        }

    fun attempt(city: String): ToolResult =
        try {
            success(
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

    return try {
        attempt(city)
    } catch (_: CityNotFoundException) {
        try {
            val elicitedCity = elicitCity(ctx, city) ?: return fail("City not found")
            attempt(elicitedCity)
        } catch (_: CityNotFoundException) {
            fail("City not found")
        } catch (e: Exception) {
            internalError(e)
        }
    }
}

private fun fetchWithProgress(
    ctx: InteractionContext,
    progressToken: ProgressToken?,
    weatherService: WeatherService,
    city: String,
    temperatureUnit: TemperatureUnit,
): WeatherObservation {
    ctx.notifications().progress(progressToken, 0.1, 1.0, "Fetching weather for $city")
    val weather = weatherService.currentWeather(city, temperatureUnit)
    ctx
        .notifications()
        .progress(progressToken, 1.0, 1.0, "Weather retrieved for $city")
    return weather
}

private fun toResponse(
    city: String,
    weather: WeatherObservation,
): GetWeatherResponse =
    GetWeatherResponse(
        city = city,
        condition = weather.condition,
        temperature = weather.temperature,
        temperatureUnit = weather.temperatureUnit,
        humidity = weather.humidity,
        windSpeed = weather.windSpeed,
    )

internal fun restoreInterruptStatus(e: Exception) {
    if (e is InterruptedException) {
        Thread.currentThread().interrupt()
    }
}

private fun internalError(e: Exception): ToolResult {
    restoreInterruptStatus(e)
    log.warn("get-weather failed", e)
    return ToolResult.error("Could not get weather")
}

private fun elicitCity(
    ctx: InteractionContext,
    city: String,
): String? {
    val future =
        ctx.client().elicitation().create(
            ElicitationRequest("City '$city' was not found. Enter another city.", CITY_SCHEMA),
        )
    val result =
        try {
            future.get(ELICITATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            restoreInterruptStatus(e)
            throw e
        } catch (_: TimeoutException) {
            return null
        }
    if (result.action() != ElicitationResult.Action.ACCEPT) return null
    return result.content()?.stringOr("city", "")?.takeIf(String::isNotBlank)
}
