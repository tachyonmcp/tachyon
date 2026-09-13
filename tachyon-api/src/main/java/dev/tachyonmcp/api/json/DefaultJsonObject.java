/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.json;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;

final class DefaultJsonObject implements JsonObject {

    static final JsonObject EMPTY = new DefaultJsonObject(Map.of());

    private final Map<String, @Nullable Object> values;
    private volatile @Nullable String json;
    private final Map<String, JsonObject> objects = new ConcurrentHashMap<>();
    private final Map<String, JsonArray> arrays = new ConcurrentHashMap<>();

    DefaultJsonObject(Map<String, ? extends @Nullable Object> values) {
        Objects.requireNonNull(values, "values");
        var copy = new LinkedHashMap<String, @Nullable Object>(values.size());
        values.forEach((name, value) -> {
            if (name == null) {
                throw new IllegalArgumentException("JSON object property name must not be null");
            }
            copy.put(name, JsonValues.copyValue(value, name));
        });
        this.values = Collections.unmodifiableMap(copy);
    }

    private DefaultJsonObject(Map<String, @Nullable Object> values, @Nullable String json) {
        this.values = values;
        this.json = json;
    }

    static DefaultJsonObject fromImmutableValues(Map<String, @Nullable Object> values, @Nullable String json) {
        return new DefaultJsonObject(values, json);
    }

    @Override
    public String json() {
        var current = json;
        if (current == null) {
            current = JsonValues.writeJson(values);
            json = current;
        }
        return current;
    }

    @Override
    public boolean has(String name) {
        return values.containsKey(name);
    }

    @Override
    public Optional<JsonObject> objectOpt(String name) {
        var value = values.get(name);
        return value == null
                ? Optional.empty()
                : Optional.of(objects.computeIfAbsent(name, ignored -> JsonValues.object(value, location(name))));
    }

    @Override
    public Optional<JsonArray> arrayOpt(String name) {
        var value = values.get(name);
        return value == null
                ? Optional.empty()
                : Optional.of(arrays.computeIfAbsent(name, ignored -> JsonValues.array(value, location(name))));
    }

    @Override
    public Optional<String> stringOpt(String name) {
        return JsonValues.optionalValue(values.get(name), String.class, "string", location(name));
    }

    @Override
    public Optional<Boolean> boolOpt(String name) {
        return JsonValues.optionalValue(values.get(name), Boolean.class, "boolean", location(name));
    }

    @Override
    public Optional<BigDecimal> decimalOpt(String name) {
        return JsonValues.decimalOpt(values.get(name), location(name));
    }

    @Override
    public OptionalInt intOpt(String name) {
        return JsonValues.intOpt(values.get(name), location(name));
    }

    @Override
    public OptionalLong longOpt(String name) {
        return JsonValues.longOpt(values.get(name), location(name));
    }

    @Override
    public OptionalDouble doubleOpt(String name) {
        return JsonValues.doubleOpt(values.get(name), location(name));
    }

    @Override
    public Map<String, @Nullable Object> asMap() {
        return values;
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return type.isInstance(values) ? Optional.of(type.cast(values)) : Optional.empty();
    }

    private static String location(String name) {
        return "property '" + name + "'";
    }
}
