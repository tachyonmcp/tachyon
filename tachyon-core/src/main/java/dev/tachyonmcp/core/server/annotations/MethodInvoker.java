/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import dev.tachyonmcp.api.annotations.McpParam;
import dev.tachyonmcp.api.annotations.Meta;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.features.annotations.AnnotationInvocationSupport;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.core.server.json.JavaTypeSchemas;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Binds MCP argument values to an annotated method's parameters and invokes it. */
final class MethodInvoker {

    private sealed interface Binding {}

    private record ContextBinding() implements Binding {}

    private record CompletionBinding() implements Binding {}

    private record MetaBinding(Parameter parameter) implements Binding {}

    private record WholeBinding(Parameter parameter) implements Binding {}

    private record NamedBinding(Parameter parameter, String name, boolean required) implements Binding {}

    private final Object instance;
    private final Method method;
    private final List<Binding> bindings;
    private final PayloadSerializer serializer;
    private final PayloadDeserializer deserializer;

    private MethodInvoker(
            Object instance, Method method, List<Binding> bindings, AnnotationRegistrationContext context) {
        this.instance = instance;
        this.method = invocationMethod(instance, method);
        this.bindings = List.copyOf(bindings);
        this.serializer = context.payloadSerializer();
        this.deserializer = context.payloadDeserializer();
    }

    private static Method invocationMethod(Object instance, Method metadata) {
        if (metadata.getDeclaringClass().isInstance(instance)) return metadata;
        try {
            final var method = instance.getClass().getMethod(metadata.getName(), metadata.getParameterTypes());
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Annotated method is not exposed by the invocation receiver: " + metadata, e);
        }
    }

    /** Tool shape: a single record/POJO/map parameter receives the whole arguments object. */
    static MethodInvoker forTool(Object instance, Method method, AnnotationRegistrationContext context) {
        var parameters = valueParameters(method);
        if (parameters.size() == 1
                && parameters.stream().noneMatch(p -> p.isAnnotationPresent(McpParam.class))
                && JavaTypeSchemas.isObjectType(parameters.getFirst().getType())) {
            var bindings = new ArrayList<Binding>();
            for (Parameter parameter : method.getParameters()) {
                bindings.add(
                        isContext(parameter)
                                ? new ContextBinding()
                                : parameter.isAnnotationPresent(Meta.class)
                                        ? new MetaBinding(parameter)
                                        : new WholeBinding(parameter));
            }
            return new MethodInvoker(instance, method, bindings, context);
        }
        return new MethodInvoker(instance, method, namedBindings(method, false), context);
    }

    /** Resource/prompt shape: every parameter is one named scalar argument. */
    static MethodInvoker forArguments(Object instance, Method method, AnnotationRegistrationContext context) {
        return new MethodInvoker(instance, method, namedBindings(method, true), context);
    }

    static MethodInvoker forCompletion(Object instance, Method method, AnnotationRegistrationContext context) {
        final var parameters = valueParameters(method);
        if (parameters.size() == 1 && parameters.getFirst().getType() == CompletionRequest.class) {
            final var bindings = new ArrayList<Binding>();
            for (final var parameter : method.getParameters()) {
                bindings.add(
                        isContext(parameter)
                                ? new ContextBinding()
                                : parameter.isAnnotationPresent(Meta.class)
                                        ? new MetaBinding(parameter)
                                        : new CompletionBinding());
            }
            return new MethodInvoker(instance, method, bindings, context);
        }
        if (parameters.isEmpty() || parameters.getFirst().getType() != String.class) {
            throw new IllegalStateException(
                    "@McpCompletion requires CompletionRequest or a first String argument: " + method);
        }
        return forArguments(instance, method, context);
    }

    @Nullable
    Object invokeCompletion(InteractionContext ctx, CompletionRequest request) throws Exception {
        final var values = new LinkedHashMap<>(request.resolvedArguments());
        values.put(request.argumentName(), request.argumentValue());
        return invoke(ctx, values, request.meta(), request);
    }

    JsonSchema inputSchema() {
        for (Binding binding : bindings) {
            if (binding instanceof WholeBinding(Parameter parameter)) {
                if (Map.class.isAssignableFrom(parameter.getType())) {
                    return JsonSchema.from(JavaTypeSchemas.schemaFor(parameter.getParameterizedType()));
                }
                return JsonSchema.generate(parameter.getType());
            }
        }
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (Binding binding : bindings) {
            if (binding instanceof NamedBinding(Parameter parameter, String name, boolean required1)) {
                final var schema = new LinkedHashMap<>(JavaTypeSchemas.schemaFor(parameter.getParameterizedType()));
                final var description = description(parameter);
                if (description != null) schema.put("description", description);
                properties.put(name, schema);
                if (required1) required.add(name);
            }
        }
        return AnnotationInvocationSupport.inputSchema(properties, required);
    }

    List<PromptArgument> promptArguments() {
        var arguments = new ArrayList<PromptArgument>();
        for (Binding binding : bindings) {
            if (binding instanceof NamedBinding(Parameter parameter, String name, boolean required)) {
                arguments.add(PromptArgument.builder()
                        .name(name)
                        .description(description(parameter))
                        .required(required)
                        .build());
            }
        }
        return arguments;
    }

    Map<String, List<String>> enumArguments() {
        final var arguments = new LinkedHashMap<String, List<String>>();
        for (final var binding : bindings) {
            if (binding instanceof NamedBinding named) {
                final var enumType = enumType(named.parameter().getParameterizedType());
                if (enumType != null) arguments.put(named.name(), constantNames(enumType));
            }
        }
        return arguments;
    }

    List<String> argumentNames() {
        return bindings.stream()
                .filter(NamedBinding.class::isInstance)
                .map(binding -> ((NamedBinding) binding).name())
                .toList();
    }

    @Nullable
    Object invoke(
            InteractionContext ctx, Map<String, ? extends @Nullable Object> values, @Nullable Map<String, Object> meta)
            throws Exception {
        return invoke(ctx, values, meta, null);
    }

    private @Nullable Object invoke(
            InteractionContext ctx,
            Map<String, ? extends @Nullable Object> values,
            @Nullable Map<String, Object> meta,
            @Nullable CompletionRequest completion)
            throws Exception {
        var args = new Object[bindings.size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = switch (bindings.get(i)) {
                case ContextBinding ignored -> ctx;
                case MetaBinding binding -> bindMeta(binding.parameter(), meta);
                case CompletionBinding ignored -> Objects.requireNonNull(completion, "completion request");
                case WholeBinding whole ->
                    coerce("arguments", values, whole.parameter().getParameterizedType());
                case NamedBinding named -> bindNamed(named, values);
            };
        }
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw AnnotationInvocationSupport.unwrap(e);
        }
    }

    private @Nullable Object bindMeta(Parameter parameter, @Nullable Map<String, Object> meta) {
        if (parameter.getType() == Map.class) return meta == null ? Map.of() : meta;
        if (meta == null && !JavaTypeSchemas.isOptional(parameter.getAnnotatedType())) {
            throw new InvalidArgumentException("_meta", "is required");
        }
        return coerce("_meta", meta, parameter.getParameterizedType());
    }

    private static @Nullable String description(Parameter parameter) {
        final var annotation = parameter.getAnnotation(McpParam.class);
        return annotation == null || annotation.description().isBlank() ? null : annotation.description();
    }

    private @Nullable Object bindNamed(NamedBinding named, Map<String, ? extends @Nullable Object> values) {
        var raw = values.get(named.name());
        if (raw == null && named.required()) {
            throw new InvalidArgumentException(named.name(), "is required");
        }
        return coerce(named.name(), raw, named.parameter().getParameterizedType());
    }

    private @Nullable Object coerce(String name, @Nullable Object raw, Type type) {
        final var enumType = enumType(type);
        if (enumType != null) {
            final var optional = AnnotationInvocationSupport.isOptionalType(type);
            if (raw == null) return optional ? Optional.empty() : null;
            final var constant = enumConstant(name, raw, enumType);
            return optional ? Optional.of(constant) : constant;
        }
        try {
            return AnnotationInvocationSupport.coerce(raw, type, serializer, deserializer);
        } catch (RuntimeException e) {
            throw new InvalidArgumentException(name, "could not be decoded as " + type.getTypeName(), e);
        }
    }

    private static @Nullable Class<?> enumType(Type type) {
        final var effective = AnnotationInvocationSupport.unwrapOptional(type);
        return effective instanceof Class<?> cls && cls.isEnum() ? cls : null;
    }

    private static Enum<?> enumConstant(String name, Object raw, Class<?> enumType) {
        if (enumType.isInstance(raw)) return (Enum<?>) raw;
        if (raw instanceof String text) {
            for (final var constant : enumType.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(text)) return (Enum<?>) constant;
            }
        }
        throw new InvalidArgumentException(name, "must be one of " + constantNames(enumType));
    }

    private static List<String> constantNames(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant -> ((Enum<?>) constant).name())
                .toList();
    }

    private static List<Binding> namedBindings(Method method, boolean scalarsOnly) {
        validateParameters(method);
        var names = new HashSet<String>();
        var bindings = new ArrayList<Binding>();
        for (Parameter parameter : method.getParameters()) {
            if (isContext(parameter)) {
                bindings.add(new ContextBinding());
                continue;
            }
            if (parameter.isAnnotationPresent(Meta.class)) {
                bindings.add(new MetaBinding(parameter));
                continue;
            }
            final var annotation = parameter.getAnnotation(McpParam.class);
            final var explicitName = annotation == null ? "" : annotation.name();
            if (explicitName.isBlank() && !parameter.isNamePresent()) {
                throw new IllegalStateException("Parameter names unavailable; compile with -parameters: " + method);
            }
            if (scalarsOnly && enumType(parameter.getParameterizedType()) == null) {
                AnnotationInvocationSupport.requireBindable(parameter, method);
            }
            final var name = explicitName.isBlank() ? parameter.getName() : explicitName;
            if (!names.add(name)) throw new IllegalStateException("Duplicate argument name '" + name + "': " + method);
            var required = !JavaTypeSchemas.isOptional(parameter.getAnnotatedType());
            bindings.add(new NamedBinding(parameter, name, required));
        }
        return bindings;
    }

    private static List<Parameter> valueParameters(Method method) {
        validateParameters(method);
        var parameters = new ArrayList<Parameter>();
        for (Parameter parameter : method.getParameters()) {
            if (!isContext(parameter) && !parameter.isAnnotationPresent(Meta.class)) parameters.add(parameter);
        }
        return parameters;
    }

    private static void validateParameters(Method method) {
        var metaCount = 0;
        for (final var parameter : method.getParameters()) {
            final var meta = parameter.isAnnotationPresent(Meta.class);
            final var named = parameter.isAnnotationPresent(McpParam.class);
            if ((meta && named)
                    || (isContext(parameter) && (meta || named))
                    || (parameter.getType() == CompletionRequest.class && (meta || named))) {
                throw new IllegalStateException("Conflicting parameter annotations: " + method);
            }
            if (meta) {
                if (++metaCount > 1) throw new IllegalStateException("Use only one @Meta parameter: " + method);
                if (!JavaTypeSchemas.isObjectType(parameter.getType())) {
                    throw new IllegalStateException("@Meta requires a Map, record or POJO: " + method);
                }
                if (Map.class.isAssignableFrom(parameter.getType())
                        && !parameter
                                .getParameterizedType()
                                .getTypeName()
                                .equals("java.util.Map<java.lang.String, java.lang.Object>")) {
                    throw new IllegalStateException("@Meta requires Map<String, Object>: " + method);
                }
            }
        }
    }

    private static boolean isContext(Parameter parameter) {
        return InteractionContext.class.isAssignableFrom(parameter.getType());
    }
}
