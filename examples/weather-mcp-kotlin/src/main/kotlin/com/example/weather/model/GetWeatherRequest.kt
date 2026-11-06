/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather.model

import jakarta.validation.constraints.Size
import kotlinx.serialization.SerialName
import me.kpavlov.kt.schema.Description

@SerialName("GetWeatherRequest")
data class GetWeatherRequest(
    @Description("City name (e.g., London, Tokyo, New York)")
    @Size(min = 1, max = 100)
    val city: String,
    @Description("Temperature unit (default: Celsius)")
    val units: TemperatureUnit = TemperatureUnit.Celsius,
)
