/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import java.util.List;

interface WeatherProvider {
    Weather current(String city) throws Exception;

    List<String> cities(String prefix) throws Exception;
}
