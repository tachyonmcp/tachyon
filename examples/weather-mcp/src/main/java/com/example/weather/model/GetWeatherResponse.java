/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

@JsonClassDescription("Current weather observation for a city.")
@JsonTypeName("GetWeatherResponse")
public record GetWeatherResponse(
    @JsonPropertyDescription("City name")
    @Size(min = 1, max = 100)
    String city,
    @JsonPropertyDescription("Weather condition")
    String condition,
    @JsonPropertyDescription("Temperature in the response unit")
    @Min(-130)
    @Max(140)
    double temperature,
    @JsonPropertyDescription("Temperature unit")
    TemperatureUnit unit,
    @JsonPropertyDescription("Relative humidity percentage")
    @PositiveOrZero
    int humidity,
    @JsonPropertyDescription("Wind speed in km/h")
    @PositiveOrZero
    double windSpeed) {
}
