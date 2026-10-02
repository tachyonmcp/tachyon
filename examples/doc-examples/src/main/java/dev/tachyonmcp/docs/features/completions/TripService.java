package dev.tachyonmcp.docs.features.completions;

import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import java.util.List;
import java.util.Locale;

final class TripService {

    // snips-start: completions_siblings
    @McpPrompt(name = "trip")
    public String trip(String city, @org.jspecify.annotations.Nullable String country) {
        return "Plan a trip to " + city + (country == null ? "" : ", " + country);
    }

    @McpCompletion(prompt = "trip")
    public List<String> citiesForTrip(String city, @org.jspecify.annotations.Nullable String country) {
        var candidates = "France".equals(country)
                ? List.of("Paris", "Lyon") : List.of("London", "Paris", "Lyon");
        return candidates.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(city.toLowerCase(Locale.ROOT)))
                .toList();
    }
    // snips-end: completions_siblings
}
