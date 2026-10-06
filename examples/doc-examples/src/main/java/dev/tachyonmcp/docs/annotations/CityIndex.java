package dev.tachyonmcp.docs.annotations;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

final class CityIndex {
    private static final Map<String, List<String>> CITIES_BY_COUNTRY = Map.of(
            "DE", List.of("Berlin", "Bonn", "Bremen"),
            "NO", List.of("Bergen", "Oslo"));

    List<String> findStartingWith(String prefix, @Nullable String country) {
        return CITIES_BY_COUNTRY.entrySet().stream()
                .filter(entry -> country == null || entry.getKey().equals(country))
                .flatMap(entry -> entry.getValue().stream())
                .filter(city -> city.startsWith(prefix))
                .sorted()
                .toList();
    }
}
