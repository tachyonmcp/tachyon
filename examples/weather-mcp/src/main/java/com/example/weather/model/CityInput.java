/*
 * Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors.
 */

package com.example.weather.model;

import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonTypeName;
import jakarta.validation.constraints.Size;

@JsonClassDescription("City to look up weather for.")
@JsonTypeName("CityInput")
public record CityInput(
    @JsonPropertyDescription("City name")
    @Size(min = 1, max = 100)
    String city
) {
}
