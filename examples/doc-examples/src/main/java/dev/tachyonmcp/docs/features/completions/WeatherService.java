package dev.tachyonmcp.docs.features.completions;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpResource;
import java.util.List;
import java.util.Locale;

final class WeatherService {

    // snips-start: completions_resource_variable
    @McpResource(uri = "weather://current/{city}")
    public String weather(String city) {
        return "Forecast for " + city;
    }

    @McpCompletion(resource = "weather://current/{city}")
    public List<String> cities(String city) {
        var prefix = city.toLowerCase(Locale.ROOT);
        return List.of("London", "Paris", "Prague").stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                .toList();
    }
    // snips-end: completions_resource_variable
}
