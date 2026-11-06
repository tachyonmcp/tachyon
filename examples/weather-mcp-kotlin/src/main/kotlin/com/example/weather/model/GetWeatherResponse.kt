/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather.model

import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.kpavlov.kt.schema.Description

@Serializable
@SerialName("GetWeatherResponse")
data class GetWeatherResponse(
    @Description("City name")
    @Size(min = 1, max = 100)
    val city: String,
    @Description("Weather condition")
    val condition: String,
    @Description("Temperature in the response unit")
    val temperature: Double,
    @Description("Temperature unit")
    val temperatureUnit: TemperatureUnit,
    @Description("Relative humidity percentage")
    @PositiveOrZero
    val humidity: Int,
    @Description("Wind speed in km/h")
    @PositiveOrZero
    val windSpeed: Double,
)
