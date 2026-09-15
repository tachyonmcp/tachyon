/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package com.example.weather;

import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class OpenMeteoProvider implements WeatherProvider {
    private static final String GEOCODING = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast";
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    OpenMeteoProvider(HttpClient client) {
        this.client = client;
    }

    @Override
    public Weather current(String city) throws IOException, InterruptedException {
        final var location = get(geocoding(city, 1)).path("results").path(0);
        if (location.isMissingNode()) throw new InvalidArgumentException("city", "not found");
        final var current = get(URI.create(
                        FORECAST + "?latitude=" + location.path("latitude").asDouble()
                                + "&longitude=" + location.path("longitude").asDouble()
                                + "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m&wind_speed_unit=kmh"))
                .path("current");
        if (current.isMissingNode()) throw new IOException("Missing current weather");
        return new Weather(
                city,
                condition(current.path("weather_code").asInt()),
                current.path("temperature_2m").asDouble(),
                TemperatureUnit.CELSIUS,
                current.path("relative_humidity_2m").asInt(),
                current.path("wind_speed_10m").asDouble());
    }

    @Override
    public List<String> cities(String prefix) throws IOException, InterruptedException {
        final var names = new LinkedHashSet<String>();
        for (final var result : get(geocoding(prefix, 10)).path("results")) {
            final var name = result.path("name").asString();
            if (name != null && !name.isBlank()) names.add(name);
        }
        return List.copyOf(names);
    }

    private JsonNode get(URI uri) throws IOException, InterruptedException {
        final var request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        final var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("Open-Meteo returned HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }

    private static URI geocoding(String city, int count) {
        return URI.create(GEOCODING + "?name=" + URLEncoder.encode(city, StandardCharsets.UTF_8) + "&count=" + count
                + "&language=en");
    }

    private static String condition(int code) {
        return switch (code) {
            case 0 -> "Clear sky";
            case 1, 2 -> "Partly cloudy";
            case 3 -> "Overcast";
            case 45, 48 -> "Foggy";
            case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "Rainy";
            case 71, 73, 75, 77, 85, 86 -> "Snowy";
            case 95, 96, 99 -> "Thunderstorms";
            default -> "Unknown";
        };
    }
}
