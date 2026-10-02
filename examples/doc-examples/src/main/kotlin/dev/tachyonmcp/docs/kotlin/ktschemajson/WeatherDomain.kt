package dev.tachyonmcp.docs.kotlin.ktschemajson

import com.example.weather.model.TemperatureUnit
import com.example.weather.spi.WeatherObservation
import dev.tachyonmcp.api.runtime.InteractionContext
import dev.tachyonmcp.api.server.domain.ProgressToken
import dev.tachyonmcp.api.server.features.tools.ToolResult

interface WeatherService {
    fun currentWeather(
        city: String,
        unit: TemperatureUnit,
    ): WeatherObservation
}

class CityNotFoundException(
    city: String,
) : RuntimeException("City not found: $city")

internal fun fetchWithProgress(
    ctx: InteractionContext,
    progressToken: ProgressToken?,
    weatherService: WeatherService,
    city: String,
    temperatureUnit: TemperatureUnit,
): WeatherObservation {
    ctx.notifications().progress(progressToken, 0.1, 1.0, "Fetching weather for $city")
    val weather = weatherService.currentWeather(city, temperatureUnit)
    ctx.notifications().progress(progressToken, 1.0, 1.0, "Weather retrieved for $city")
    return weather
}

internal fun internalError(e: Exception): ToolResult = ToolResult.error("Could not get weather: ${e.message}")
