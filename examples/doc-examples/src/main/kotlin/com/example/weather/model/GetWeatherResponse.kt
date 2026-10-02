// snips-start: ktschema_get_weather_response
package com.example.weather.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.Description

@Serializable
@SerialName("GetWeatherResponse")
data class GetWeatherResponse(
    @Description("City name")
    val city: String,
    @Description("Weather condition")
    val condition: String,
    @Description("Temperature in the response unit")
    val temperature: Double,
    @Description("Temperature unit")
    val temperatureUnit: TemperatureUnit,
    @Description("Relative humidity percentage")
    val humidity: Int,
    @Description("Wind speed in km/h")
    val windSpeed: Double,
)
// snips-end: ktschema_get_weather_response
