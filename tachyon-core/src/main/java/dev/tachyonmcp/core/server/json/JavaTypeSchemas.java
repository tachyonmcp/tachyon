/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.json;

import dev.tachyonmcp.api.annotations.InternalApi;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Runtime reflection JSON Schema generator for plain Java types, matching how Jackson binds them:
 * scalars, enums, arrays, collections, maps, {@code Optional}, records (components; required
 * unless {@code Optional} or JSpecify {@code @Nullable}), and POJOs (public getters and fields;
 * only primitives required). Recursive types stop at a bare {@code object}.
 */
@InternalApi
public final class JavaTypeSchemas {

    private static final Map<Class<?>, Map<String, Object>> SCALARS = Map.ofEntries(
            Map.entry(String.class, type("string")),
            Map.entry(char.class, type("string")),
            Map.entry(Character.class, type("string")),
            Map.entry(boolean.class, type("boolean")),
            Map.entry(Boolean.class, type("boolean")),
            Map.entry(byte.class, type("integer")),
            Map.entry(Byte.class, type("integer")),
            Map.entry(short.class, type("integer")),
            Map.entry(Short.class, type("integer")),
            Map.entry(int.class, type("integer")),
            Map.entry(Integer.class, type("integer")),
            Map.entry(long.class, type("integer")),
            Map.entry(Long.class, type("integer")),
            Map.entry(BigInteger.class, type("integer")),
            Map.entry(float.class, type("number")),
            Map.entry(Float.class, type("number")),
            Map.entry(double.class, type("number")),
            Map.entry(Double.class, type("number")),
            Map.entry(BigDecimal.class, type("number")),
            Map.entry(OptionalInt.class, type("integer")),
            Map.entry(OptionalLong.class, type("integer")),
            Map.entry(OptionalDouble.class, type("number")),
            Map.entry(java.util.UUID.class, format("uuid")),
            Map.entry(URI.class, format("uri")),
            Map.entry(URL.class, format("uri")),
            Map.entry(Instant.class, format("date-time")),
            Map.entry(OffsetDateTime.class, format("date-time")),
            Map.entry(ZonedDateTime.class, format("date-time")),
            Map.entry(LocalDateTime.class, type("string")),
            Map.entry(LocalDate.class, format("date")),
            Map.entry(LocalTime.class, type("string")),
            Map.entry(OffsetTime.class, format("time")),
            Map.entry(Duration.class, format("duration")),
            Map.entry(byte[].class, type("string")));

    private JavaTypeSchemas() {}

    /**
     * Generates the schema for {@code type}.
     *
     * @param type the Java type
     * @return the schema as a JSON-compatible map
     */
    public static Map<String, Object> schemaFor(Type type) {
        return schemaFor(type, new HashSet<>());
    }

    /**
     * Returns whether {@code type} binds as a JSON object (record, POJO, or map) rather than a scalar,
     * enum, array, collection, or {@code Optional}.
     *
     * @param type the Java type
     * @return {@code true} if values of {@code type} are JSON objects
     */
    public static boolean isObjectType(Class<?> type) {
        return !type.isPrimitive()
                && !type.isArray()
                && !type.isEnum()
                && (!type.isInterface() || Map.class.isAssignableFrom(type))
                && !SCALARS.containsKey(type)
                && type != Object.class
                && type != Optional.class
                && !Number.class.isAssignableFrom(type)
                && !CharSequence.class.isAssignableFrom(type)
                && !Iterable.class.isAssignableFrom(type)
                && !type.getName().startsWith("java.time.");
    }

    /**
     * Returns whether a value of the annotated type may be absent: {@code Optional}-family or
     * JSpecify {@code @Nullable}.
     *
     * @param type the annotated type of parameter or record component
     * @return {@code true} if the value is optional
     */
    public static boolean isOptional(AnnotatedType type) {
        var raw = rawClass(type.getType());
        return raw == Optional.class
                || raw == OptionalInt.class
                || raw == OptionalLong.class
                || raw == OptionalDouble.class
                || type.isAnnotationPresent(Nullable.class);
    }

    private static Map<String, Object> schemaFor(Type type, Set<Class<?>> visiting) {
        if (type instanceof ParameterizedType pt) {
            var raw = rawClass(pt);
            var args = pt.getActualTypeArguments();
            if (raw == Optional.class) return schemaFor(args[0], visiting);
            if (Iterable.class.isAssignableFrom(raw)) return array(schemaFor(args[0], visiting));
            if (Map.class.isAssignableFrom(raw)) {
                var schema = type("object");
                schema.put("additionalProperties", schemaFor(args[1], visiting));
                return schema;
            }
            return schemaFor(raw, visiting);
        }
        if (type instanceof GenericArrayType gat) return array(schemaFor(gat.getGenericComponentType(), visiting));
        if (!(type instanceof Class<?> cls)) return new LinkedHashMap<>();

        var scalar = SCALARS.get(cls);
        if (scalar != null) return new LinkedHashMap<>(scalar);
        if (cls == Object.class) return new LinkedHashMap<>();
        if (cls.isEnum()) {
            var schema = type("string");
            schema.put(
                    "enum",
                    Arrays.stream(cls.getEnumConstants())
                            .map(e -> ((Enum<?>) e).name())
                            .toList());
            return schema;
        }
        if (cls.isArray()) return array(schemaFor(cls.getComponentType(), visiting));
        if (Iterable.class.isAssignableFrom(cls)) return type("array");
        if (Map.class.isAssignableFrom(cls)) return type("object");
        if (!visiting.add(cls)) return type("object");
        try {
            return cls.isRecord() ? recordSchema(cls, visiting) : pojoSchema(cls, visiting);
        } finally {
            visiting.remove(cls);
        }
    }

    private static Map<String, Object> recordSchema(Class<?> cls, Set<Class<?>> visiting) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (RecordComponent component : cls.getRecordComponents()) {
            properties.put(component.getName(), schemaFor(component.getGenericType(), visiting));
            if (!isOptional(component.getAnnotatedType())) required.add(component.getName());
        }
        return objectSchema(properties, required);
    }

    private static Map<String, Object> pojoSchema(Class<?> cls, Set<Class<?>> visiting) {
        var properties = new TreeMap<String, Object>();
        var required = new ArrayList<String>();
        for (Field field : cls.getFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            addPojoProperty(field.getName(), field.getGenericType(), properties, required, visiting);
        }
        for (Method method : cls.getMethods()) {
            var name = propertyName(method);
            if (name == null || properties.containsKey(name)) continue;
            addPojoProperty(name, method.getGenericReturnType(), properties, required, visiting);
        }
        required.sort(null);
        return objectSchema(new LinkedHashMap<>(properties), required);
    }

    private static void addPojoProperty(
            String name, Type type, Map<String, Object> properties, List<String> required, Set<Class<?>> visiting) {
        properties.put(name, schemaFor(type, visiting));
        if (type instanceof Class<?> c && c.isPrimitive()) required.add(name);
    }

    private static @Nullable String propertyName(Method method) {
        if (Modifier.isStatic(method.getModifiers())
                || method.getParameterCount() != 0
                || method.getDeclaringClass() == Object.class
                || method.getReturnType() == void.class) {
            return null;
        }
        var name = method.getName();
        if (name.startsWith("get") && name.length() > 3) return decapitalize(name.substring(3));
        if (name.startsWith("is") && name.length() > 2 && method.getReturnType() == boolean.class) {
            return decapitalize(name.substring(2));
        }
        return null;
    }

    private static String decapitalize(String name) {
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        var schema = type("object");
        schema.put("properties", properties);
        if (!required.isEmpty()) schema.put("required", List.copyOf(required));
        return schema;
    }

    private static Map<String, Object> array(Map<String, Object> items) {
        var schema = type("array");
        schema.put("items", items);
        return schema;
    }

    private static Map<String, Object> type(String type) {
        var schema = new LinkedHashMap<String, Object>();
        schema.put("type", type);
        return schema;
    }

    private static Map<String, Object> format(String format) {
        var schema = type("string");
        schema.put("format", format);
        return schema;
    }

    private static Class<?> rawClass(Type type) {
        if (type instanceof Class<?> c) return c;
        if (type instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        return Object.class;
    }
}
