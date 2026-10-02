package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.api.annotations.McpCompletion;
import java.util.List;
import org.jspecify.annotations.Nullable;

final class SimpleCompletions {
    private final CityIndex cities = new CityIndex();

    // snips-start: annotations_completion_simple
    @McpCompletion(prompt = "trip")
    List<String> completeCity(String city, @Nullable String country) {
        return cities.findStartingWith(city, country);
    }
    // snips-end: annotations_completion_simple
}
