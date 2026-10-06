package dev.tachyonmcp.docs.annotations;

// snips-start: annotations_weather_service
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Role;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

class WeatherService {
    record ForecastRequest(String city, int days) {}
    record Forecast(String city, int days, double celsius) {}

    @McpTool(description = "Forecast for a city")
    Forecast forecast(ForecastRequest request) {
        return new Forecast(request.city(), request.days(), 18.5);
    }

    @McpTool
    String greet(String name, @Nullable String title, InteractionContext ctx) {
        return title == null ? "Hello, " + name : "Hello, " + title + " " + name;
    }

    @McpResource(uri = "weather://cities/{city}")
    String city(String city) {
        return city + " has a temperate climate";
    }

    @McpPrompt
    String trip(String city, Optional<String> season) {
        return "Plan a " + season.orElse("year-round") + " trip to " + city;
    }

    @McpPrompt(role = Role.ASSISTANT)
    String opener(String city) {
        return "Welcome to " + city + "!";
    }
}
// snips-end: annotations_weather_service
