package dev.tachyonmcp.docs.features.completions;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class CityService {

    private static final List<String> CITIES = List.of("London", "Paris", "Prague");

    CompletionStage<List<String>> search(String prefix) {
        var lower = prefix.toLowerCase(Locale.ROOT);
        return CompletableFuture.supplyAsync(() -> CITIES.stream()
                .filter(city -> city.toLowerCase(Locale.ROOT).startsWith(lower))
                .toList());
    }
}
