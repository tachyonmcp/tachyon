/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Component
class WeatherService {
    private final WeatherProvider provider;

    WeatherService(WeatherProvider provider) {
        this.provider = provider;
    }

    @McpTool(name = "get-weather", description = "Current weather for a city; units: CELSIUS (default) or FAHRENHEIT")
    Weather weather(String city, @Nullable TemperatureUnit units) throws Exception {
        requireCity(city);
        final var unit = units == null ? TemperatureUnit.CELSIUS : units;
        final var observation = provider.current(city);
        final var temperature =
                unit == TemperatureUnit.FAHRENHEIT ? observation.temperature() * 9 / 5 + 32 : observation.temperature();
        return new Weather(
                city, observation.condition(), temperature, unit, observation.humidity(), observation.windSpeed());
    }

    @McpResource(name = "current-weather", uri = "weather://current/{city}", description = "Current weather for a city")
    Weather current(String city) throws Exception {
        requireCity(city);
        return provider.current(city);
    }

    @McpResource(
            name = "featured-current-weather",
            uri = "weather://featured/current",
            description = "Current weather in Tallinn")
    Weather featured() throws Exception {
        return provider.current("Tallinn");
    }

    @McpResource(name = "prediction-article", uri = "weather://prediction/article", mimeType = "text/markdown")
    String article() throws IOException {
        try (final var stream = WeatherService.class.getResourceAsStream("/articles/prediction-article.md")) {
            if (stream == null) throw new IOException("Missing prediction article");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @McpPrompt(name = "rewrite-forecast", description = "Rewrite a forecast in PLAIN, CONCISE, or PIRATE style")
    String rewrite(String forecast, NarrationStyle style) {
        if (forecast.isBlank()) throw new InvalidArgumentException("forecast", "must not be blank");
        return "Rewrite the following weather forecast in %s style. Preserve factual details:\n\n%s"
                .formatted(style.name().toLowerCase(Locale.ROOT), forecast);
    }

    @McpCompletion(resource = "weather://current/{city}")
    List<String> cities(String city) throws Exception {
        return city.length() < 2 ? List.of() : provider.cities(city);
    }

    enum NarrationStyle {
        PLAIN,
        CONCISE,
        PIRATE
    }

    private static void requireCity(String city) {
        if (city.isBlank()) throw new InvalidArgumentException("city", "must not be blank");
    }
}
