/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

@JsonClassDescription("Input for looking up the current weather in a city.")
@JsonTypeName("GetWeatherRequest")
public record GetWeatherRequest(
        @JsonPropertyDescription("City name (e.g., London, Tokyo, New York)")
        @Size(min = 1, max = 100)
        String city,
        @JsonPropertyDescription("Temperature unit (default: celsius)") @Nullable TemperatureUnit units) {
}
