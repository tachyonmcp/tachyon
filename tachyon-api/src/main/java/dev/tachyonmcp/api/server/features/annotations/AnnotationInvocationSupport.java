/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.annotations;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Reflection helpers shared by {@link AnnotationProvider} implementations (mcp-java, LangChain4j,
 * Spring AI): coercing a raw JSON-RPC argument to a parameter's declared type through the
 * server's configured {@link PayloadSerializer}/{@link PayloadDeserializer}, mapping a Java type
 * to its JSON Schema {@code "type"} value, rejecting parameter types neither can describe, and
 * unwrapping an {@link InvocationTargetException} to its cause. {@link #jsonSchemaType} and {@link
 * #coerce} agree on exactly the same set of supported types — String, numeric/boolean
 * primitives and wrappers, and {@code Optional}/{@code OptionalInt}/{@code OptionalLong}/{@code
 * OptionalDouble} wrapping one of those — so a generated schema never advertises a type the
 * coercion can't actually produce, and {@link #requireBindable} rejects everything else at
 * registration time instead of silently misdescribing it.
 *
 * <p>{@link #discoverMethods} already calls {@link Method#setAccessible} on every method it
 * returns, so every provider (mcp-java, LangChain4j, Spring AI, and Tachyon's own) may call
 * {@code method.invoke(...)} directly afterward regardless of the method's visibility or which
 * package the provider lives in. Providers should pass the caught {@link
 * InvocationTargetException} to {@link #unwrap} to rethrow the annotated method's real exception.
 */
@ExperimentalApi
public final class AnnotationInvocationSupport {

    private AnnotationInvocationSupport() {}

    private static final Map<Class<?>, String> JSON_SCHEMA_TYPES = Map.ofEntries(
            Map.entry(String.class, "string"),
            Map.entry(int.class, "integer"),
            Map.entry(Integer.class, "integer"),
            Map.entry(long.class, "integer"),
            Map.entry(Long.class, "integer"),
            Map.entry(short.class, "integer"),
            Map.entry(Short.class, "integer"),
            Map.entry(byte.class, "integer"),
            Map.entry(Byte.class, "integer"),
            Map.entry(double.class, "number"),
            Map.entry(Double.class, "number"),
            Map.entry(float.class, "number"),
            Map.entry(Float.class, "number"),
            Map.entry(boolean.class, "boolean"),
            Map.entry(Boolean.class, "boolean"));

    /**
     * Maps {@code type} to a JSON Schema {@code "type"} value, unwrapping {@code Optional}-family
     * types to their inner type first.
     *
     * @param type the Java type to map
     * @throws IllegalStateException if {@code type} isn't one {@link #coerce} can produce
     */
    public static String jsonSchemaType(Type type) {
        Type effective = unwrapOptional(type);
        if (effective instanceof Class<?> cls) {
            String json = JSON_SCHEMA_TYPES.get(cls);
            if (json != null) return json;
        }
        throw new IllegalStateException(unsupportedTypeMessage(type));
    }

    /**
     * Rejects {@code param} at registration time if its type is neither a plain bindable scalar
     * nor an {@code Optional}-family wrapper of one — anything a generated schema and {@link
     * #coerce} can't agree on. Callers should invoke this only for parameters that aren't handled
     * some other way (e.g. an injected {@code InteractionContext} or a framework-specific request
     * context), after excluding those.
     *
     * @param param  the parameter to validate
     * @param method the declaring method (for diagnostic messages)
     * @throws IllegalStateException if {@code param}'s type isn't bindable
     */
    public static void requireBindable(Parameter param, Method method) {
        try {
            jsonSchemaType(param.getParameterizedType());
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Unsupported parameter type " + param.getParameterizedType().getTypeName() + " for parameter '"
                            + param.getName() + "' on " + method,
                    e);
        }
    }

    /** Returns whether {@code type} is {@code Optional}, {@code OptionalInt}, {@code OptionalLong}, or {@code OptionalDouble}.
     *
     * @param type the type to check
     * @return {@code true} if the type is an Optional-family type
     */
    public static boolean isOptionalType(Type type) {
        return type == OptionalInt.class
                || type == OptionalLong.class
                || type == OptionalDouble.class
                || type == Optional.class
                || (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class);
    }

    /**
     * Returns the inner type of an {@code Optional}-family {@code type} (e.g. {@code String} for
     * {@code Optional<String>}, {@code int.class} for {@code OptionalInt}), or {@code type}
     * unchanged if it isn't one.
     *
     * @param type the type to unwrap
     * @return the inner type, or {@code type} unchanged
     */
    public static Type unwrapOptional(Type type) {
        if (type == OptionalInt.class) return int.class;
        if (type == OptionalLong.class) return long.class;
        if (type == OptionalDouble.class) return double.class;
        if (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
            return pt.getActualTypeArguments()[0];
        }
        return type;
    }

    private static String unsupportedTypeMessage(Type type) {
        return "Unsupported type " + type.getTypeName() + " — supported: String, numeric/boolean primitives and "
                + "wrappers, and Optional<T>/OptionalInt/OptionalLong/OptionalDouble of those.";
    }

    /**
     * Assembles a JSON Schema {@code "object"} envelope around {@code properties} and resolves it
     * through {@link JsonSchema#from(Map)}. {@code properties} iterates in its own order, so pass a
     * {@link LinkedHashMap} to keep parameter order stable in the emitted schema. {@code required}
     * is omitted entirely when empty rather than emitted as an empty array.
     *
     * @param properties the {@code properties} object, keyed by parameter name
     * @param required   the names of the required parameters
     * @return the assembled input schema
     */
    public static JsonSchema inputSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return JsonSchema.from(schema);
    }

    /**
     * Coerces {@code raw} to {@code type} via a round trip through {@code serializer} and {@code
     * deserializer} — the same pattern {@code Args.decode(Type)} uses for whole argument objects,
     * applied here per parameter. Returns {@code raw} unchanged (no round trip) when it's already
     * {@code null} or already an instance of {@code type}. Correctly constructs {@code
     * Optional}-family wrappers around the coerced inner value, since {@code type} carries full
     * generic information (e.g. {@code Optional<Integer>}), not an erased {@link Class}.
     *
     * @param raw          the raw JSON-RPC argument value, or {@code null}
     * @param type         the target parameter type
     * @param serializer   the payload serializer
     * @param deserializer the payload deserializer
     * @return the coerced value, or {@code null}
     */
    public static @Nullable Object coerce(
            @Nullable Object raw, Type type, PayloadSerializer serializer, PayloadDeserializer deserializer) {
        if (type == OptionalInt.class) {
            return raw == null
                    ? OptionalInt.empty()
                    : OptionalInt.of((Integer) coerce(raw, int.class, serializer, deserializer));
        }
        if (type == OptionalLong.class) {
            return raw == null
                    ? OptionalLong.empty()
                    : OptionalLong.of((Long) coerce(raw, long.class, serializer, deserializer));
        }
        if (type == OptionalDouble.class) {
            return raw == null
                    ? OptionalDouble.empty()
                    : OptionalDouble.of((Double) coerce(raw, double.class, serializer, deserializer));
        }
        if (type instanceof ParameterizedType pt && pt.getRawType() == Optional.class) {
            Type inner = pt.getActualTypeArguments()[0];
            return raw == null ? Optional.empty() : Optional.ofNullable(coerce(raw, inner, serializer, deserializer));
        }
        if (raw == null) return null;
        if (type instanceof Class<?> cls && cls.isInstance(raw)) return raw;
        return deserializer.deserialize(serializer.serialize(raw), type);
    }

    /**
     * Returns the real exception an annotated method threw, unwrapped from the {@link
     * InvocationTargetException} that {@link Method#invoke(Object, Object...)} wraps it in. Callers do {@code throw
     * AnnotationInvocationSupport.unwrap(e);} from their {@code catch (InvocationTargetException
     * e)} block. If the cause is an {@link Error}, it is thrown directly from this method instead
     * of being returned, since {@code Error} isn't an {@code Exception}.
     *
     * @param e the invocation target exception to unwrap
     * @return the real exception, or throws the {@link Error} cause directly
     */
    public static Exception unwrap(InvocationTargetException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) return ex;
        if (cause instanceof Error err) throw err;
        return e;
    }

    /**
     * Returns {@code clazz}'s methods annotated with one of {@code annotationTypes} — package-
     * private, protected, and public alike, mirroring the discovery rules Spring itself uses for
     * annotated-bean-method scanning (see {@code org.springframework.util.ReflectionUtils}) —
     * including those inherited from superclasses and interfaces, excluding synthetic and
     * bridge methods. Only {@code private} is rejected, as a matter of policy rather than a JVM
     * limitation — see {@link #requireNotPrivate}. Every accepted method has {@code
     * setAccessible(true)} already applied, so providers can call {@link Method#invoke(Object, Object...)} on it
     * directly regardless of which package they live in.
     *
     * <p>The returned element is the <em>declaration carrying the annotation</em>, which is not
     * always the one {@code clazz} declares: an {@code @McpTool} sitting on an interface method that
     * {@code clazz} implements without re-annotating is returned as the interface's {@link Method}.
     * {@link Method#invoke(Object, Object...)} dispatches virtually, so calling it on an instance still runs the
     * override, and a provider reading {@code method.getAnnotation(...)} or
     * {@code method.getParameters()} sees the annotated declaration. A re-annotated override wins
     * over the declaration it overrides, and an interface method some collected method already
     * overrides is not returned twice.
     *
     * @param clazz          the class to scan for annotated methods
     * @param annotationTypes the annotation types to search for
     * @return discovered methods, in declaration order with duplicates removed
     * @throws IllegalStateException if an annotated method (or its declaring class) is private
     */
    @SafeVarargs
    public static List<Method> discoverMethods(Class<?> clazz, Class<? extends Annotation>... annotationTypes) {
        Map<List<Object>, Method> methods = new LinkedHashMap<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            collectAnnotated(c.getDeclaredMethods(), annotationTypes, methods);
        }
        collectAnnotated(clazz.getMethods(), annotationTypes, methods);
        collectInheritedDeclarations(clazz, annotationTypes, methods);
        return new ArrayList<>(methods.values());
    }

    /**
     * Adds annotated interface declarations that no already-collected method overrides. {@code
     * Class#getMethods} yields the implementing class's method for an abstract interface method, and
     * that method carries none of the interface's annotations, so the declaration has to be reached
     * through the interface itself.
     */
    private static void collectInheritedDeclarations(
            Class<?> clazz, Class<? extends Annotation>[] annotationTypes, Map<List<Object>, Method> methods) {
        for (Class<?> iface : interfacesOf(clazz)) {
            for (Method method : iface.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge() || !hasAnyAnnotation(method, annotationTypes)) continue;
                if (isOverriddenBy(method, methods.values())) continue;
                requireNotPrivate(method);
                method.setAccessible(true);
                methods.putIfAbsent(signature(method), method);
            }
        }
    }

    /** Every interface reachable from {@code clazz} or any of its superclasses, breadth-first. */
    private static Set<Class<?>> interfacesOf(Class<?> clazz) {
        Set<Class<?>> found = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Collections.addAll(pending, c.getInterfaces());
        }
        while (!pending.isEmpty()) {
            Class<?> iface = pending.poll();
            if (found.add(iface)) Collections.addAll(pending, iface.getInterfaces());
        }
        return found;
    }

    /**
     * Whether some {@code collected} method overrides {@code declaration}. Compares erased parameter
     * types by assignability rather than equality, so a generic interface method and the narrower
     * implementation that overrides it are recognised as one method, not two.
     */
    private static boolean isOverriddenBy(Method declaration, Collection<Method> collected) {
        for (Method candidate : collected) {
            if (!candidate.getName().equals(declaration.getName())
                    || candidate.getParameterCount() != declaration.getParameterCount()) continue;
            Class<?>[] candidateParams = candidate.getParameterTypes();
            Class<?>[] declaredParams = declaration.getParameterTypes();
            boolean overrides = true;
            for (int i = 0; i < candidateParams.length && overrides; i++) {
                overrides = declaredParams[i].isAssignableFrom(candidateParams[i]);
            }
            if (overrides) return true;
        }
        return false;
    }

    private static void collectAnnotated(
            Method[] candidates, Class<? extends Annotation>[] annotationTypes, Map<List<Object>, Method> methods) {
        for (Method method : candidates) {
            if (method.isSynthetic() || method.isBridge() || !hasAnyAnnotation(method, annotationTypes)) continue;
            requireNotPrivate(method);
            method.setAccessible(true);
            methods.putIfAbsent(signature(method), method);
        }
    }

    private static List<Object> signature(Method method) {
        List<Object> key = new ArrayList<>();
        key.add(method.getName());
        key.addAll(Arrays.asList(method.getParameterTypes()));
        return key;
    }

    private static boolean hasAnyAnnotation(Method method, Class<? extends Annotation>[] annotationTypes) {
        for (Class<? extends Annotation> annotationType : annotationTypes) {
            if (method.isAnnotationPresent(annotationType)) return true;
        }
        return false;
    }

    /**
     * Rejects {@code method} if it (or its declaring class, or any class it's nested in) is
     * {@code private}. This is a deliberate API-surface policy, not a JVM limitation: {@link
     * Method#setAccessible} can make a private method invocable from outside its declaring class
     * just as well as a package-private one, so Tachyon simply declines to treat a private method
     * as part of an object's annotated MCP surface. Package-private, protected, and public
     * methods are all otherwise discoverable.
     *
     * @param method the method to validate
     * @throws IllegalStateException if {@code method} or a declaring/enclosing class is private
     */
    public static void requireNotPrivate(Method method) {
        if (Modifier.isPrivate(method.getModifiers()) || isDeclaredInPrivateClass(method)) {
            throw new IllegalStateException(
                    "Annotated MCP method must not be private, and must not be declared in a private (nested)"
                            + " class: " + method);
        }
    }

    private static boolean isDeclaredInPrivateClass(Method method) {
        for (Class<?> c = method.getDeclaringClass(); c != null; c = c.getEnclosingClass()) {
            if (Modifier.isPrivate(c.getModifiers())) return true;
        }
        return false;
    }
}
