// snips-start: ktschema_temperature_unit
package com.example.weather.model

import kotlinx.serialization.SerialName
import me.kpavlov.kt.schema.Description

@Description("Unit used to represent temperature")
@SerialName("TemperatureUnit")
enum class TemperatureUnit {
    Celsius,
    Fahrenheit,
}
// snips-end: ktschema_temperature_unit
