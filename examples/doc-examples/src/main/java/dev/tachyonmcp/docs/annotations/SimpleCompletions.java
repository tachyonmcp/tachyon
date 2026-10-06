package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.api.annotations.McpCompletion;
import org.jspecify.annotations.Nullable;

import java.util.List;

final class SimpleCompletions {
    private final CityIndex cities = new CityIndex();

    // snips-start: annotations_completion_simple
    @McpCompletion(prompt = "trip")
    List<String> completeCity(String city, @Nullable String country) {
        return cities.findStartingWith(city, country);
    }
    // snips-end: annotations_completion_simple
}
