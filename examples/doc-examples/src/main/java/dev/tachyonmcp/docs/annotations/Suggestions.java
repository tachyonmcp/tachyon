package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import java.util.List;
import java.util.Map;

final class Suggestions {
    private static final List<String> SEASONS = List.of("spring", "summer", "autumn", "winter");

    private final CityIndex cities = new CityIndex();

    CompletionResult complete(String argumentName, String argumentValue, Map<String, String> resolvedArguments) {
        var candidates = switch (argumentName) {
            case "city" -> cities.findStartingWith(argumentValue, resolvedArguments.get("country"));
            case "season" -> SEASONS.stream().filter(season -> season.startsWith(argumentValue)).toList();
            default -> List.<String>of();
        };
        return CompletionResult.of(candidates);
    }
}
