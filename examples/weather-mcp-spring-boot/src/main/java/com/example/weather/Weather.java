/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

/**
 * Current weather, returned as structured tool content and JSON resource contents.
 * @param city city name
 * @param condition human-readable conditions
 * @param temperature temperature in the selected unit
 * @param unit temperature unit
 * @param humidity relative humidity percentage
 * @param windSpeed wind speed in km/h
 */
public record Weather(
        String city, String condition, double temperature, TemperatureUnit unit, int humidity, double windSpeed) {}
