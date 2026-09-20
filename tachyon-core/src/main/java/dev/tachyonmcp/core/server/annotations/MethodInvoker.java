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
import dev.tachyonmcp.api.server.features.annotations.ResolvedParameter;
import dev.tachyonmcp.api.server.features.completions.CompletionRequest;
import dev.tachyonmcp.core.server.json.JavaTypeSchemas;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/** Binds MCP argument values to an annotated method's parameters and invokes it. */
final class MethodInvoker {

    private sealed interface Binding {}

    private record ContextBinding() implements Binding {}

    private record CompletionBinding() implements Binding {}

    private record MetaBinding(ResolvedParameter parameter) implements Binding {}

    private record WholeBinding(ResolvedParameter parameter) implements Binding {}

    private record NamedBinding(
            ResolvedParameter parameter,
            String name,
            @Nullable String description,
            boolean required) implements Binding {}

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

    /**
     * Tool shape: a single record/POJO/map parameter receives the whole arguments object. Parameter
     * types are read as {@code annotatedType} sees them, which matters when {@code method} is declared
     * on a generic interface or superclass.
     */
    static MethodInvoker forTool(
            Object instance, Class<?> annotatedType, Method method, AnnotationRegistrationContext context) {
        final var parameters = AnnotationInvocationSupport.parameters(method, annotatedType);
        final var values = valueParameters(parameters);
        if (values.size() == 1
                && !values.getFirst().parameter().isAnnotationPresent(McpParam.class)
                && JavaTypeSchemas.isObjectType(values.getFirst().rawType())) {
            return new MethodInvoker(instance, method, bindings(method, parameters, WholeBinding::new), context);
        }
        return new MethodInvoker(instance, method, namedBindings(method, parameters, false), context);
    }

    /** Resource/prompt shape: every parameter is one named scalar argument. */
    static MethodInvoker forArguments(
            Object instance, Class<?> annotatedType, Method method, AnnotationRegistrationContext context) {
        final var parameters = AnnotationInvocationSupport.parameters(method, annotatedType);
        return new MethodInvoker(instance, method, namedBindings(method, parameters, true), context);
    }

    static MethodInvoker forCompletion(
            Object instance, Class<?> annotatedType, Method method, AnnotationRegistrationContext context) {
        final var parameters = AnnotationInvocationSupport.parameters(method, annotatedType);
        final var values = valueParameters(parameters);
        if (values.size() == 1 && values.getFirst().rawType() == CompletionRequest.class) {
            return new MethodInvoker(
                    instance, method, bindings(method, parameters, ignored -> new CompletionBinding()), context);
        }
        if (values.isEmpty() || values.getFirst().rawType() != String.class) {
            validateParameters(method, parameters);
            throw new IllegalStateException(
                    "@McpCompletion requires CompletionRequest or a first String argument: " + method);
        }
        return forArguments(instance, annotatedType, method, context);
    }

    @Nullable
    Object invokeCompletion(InteractionContext ctx, CompletionRequest request) throws Exception {
        final var values = new LinkedHashMap<>(request.resolvedArguments());
        values.put(request.argumentName(), request.argumentValue());
        return invoke(ctx, values, request.meta(), request);
    }

    JsonSchema inputSchema() {
        for (Binding binding : bindings) {
            if (binding instanceof WholeBinding(ResolvedParameter parameter)) {
                if (Map.class.isAssignableFrom(parameter.rawType())) {
                    return JsonSchema.from(JavaTypeSchemas.schemaFor(parameter.type()));
                }
                return JsonSchema.generate(parameter.rawType());
            }
        }
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();
        for (Binding binding : bindings) {
            if (binding
                    instanceof
                    NamedBinding(ResolvedParameter parameter, String name, String description, boolean required1)) {
                final var schema = JavaTypeSchemas.schemaFor(parameter.type());
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
            if (binding instanceof NamedBinding named) {
                arguments.add(PromptArgument.builder()
                        .name(named.name())
                        .description(named.description())
                        .required(named.required())
                        .build());
            }
        }
        return arguments;
    }

    Map<String, List<String>> enumArguments() {
        final var arguments = new LinkedHashMap<String, List<String>>();
        for (final var binding : bindings) {
            if (binding instanceof NamedBinding named) {
                final var enumType = enumType(named.parameter().type());
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

    boolean usesMeta() {
        return bindings.stream().anyMatch(MetaBinding.class::isInstance);
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
                    coerce("arguments", values, whole.parameter().type());
                case NamedBinding named -> bindNamed(named, values);
            };
        }
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw AnnotationInvocationSupport.unwrap(e);
        }
    }

    private @Nullable Object bindMeta(ResolvedParameter parameter, @Nullable Map<String, Object> meta) {
        if (parameter.rawType() == Map.class) return meta == null ? Map.of() : meta;
        if (meta == null && !JavaTypeSchemas.isOptional(parameter.parameter().getAnnotatedType())) {
            throw new InvalidArgumentException("_meta", "is required");
        }
        return coerce("_meta", meta, parameter.type());
    }

    private @Nullable Object bindNamed(NamedBinding named, Map<String, ? extends @Nullable Object> values) {
        var raw = values.get(named.name());
        if (raw == null && named.required()) {
            throw new InvalidArgumentException(named.name(), "is required");
        }
        return coerce(named.name(), raw, named.parameter().type());
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

    private static List<Binding> namedBindings(Method method, List<ResolvedParameter> parameters, boolean scalarsOnly) {
        var names = new HashSet<String>();
        return bindings(method, parameters, resolved -> {
            final var parameter = resolved.parameter();
            final var annotation = parameter.getAnnotation(McpParam.class);
            final var explicitName = annotation == null ? "" : annotation.name();
            if (explicitName.isBlank() && !parameter.isNamePresent()) {
                throw new IllegalStateException("Parameter names unavailable; compile with -parameters: " + method);
            }
            if (scalarsOnly && enumType(resolved.type()) == null) {
                AnnotationInvocationSupport.requireBindable(resolved, method);
            }
            final var name = explicitName.isBlank() ? parameter.getName() : explicitName;
            if (!names.add(name)) throw new IllegalStateException("Duplicate argument name '" + name + "': " + method);
            final var description =
                    annotation == null || annotation.description().isBlank() ? null : annotation.description();
            final var required = !JavaTypeSchemas.isOptional(parameter.getAnnotatedType());
            return new NamedBinding(resolved, name, description, required);
        });
    }

    private static List<Binding> bindings(
            Method method, List<ResolvedParameter> parameters, Function<ResolvedParameter, Binding> valueBinding) {
        validateParameters(method, parameters);
        final var bindings = new ArrayList<Binding>();
        for (final var parameter : parameters) {
            if (isContext(parameter)) bindings.add(new ContextBinding());
            else if (parameter.parameter().isAnnotationPresent(Meta.class)) bindings.add(new MetaBinding(parameter));
            else bindings.add(valueBinding.apply(parameter));
        }
        return bindings;
    }

    private static List<ResolvedParameter> valueParameters(List<ResolvedParameter> parameters) {
        return parameters.stream()
                .filter(parameter ->
                        !isContext(parameter) && !parameter.parameter().isAnnotationPresent(Meta.class))
                .toList();
    }

    private static void validateParameters(Method method, List<ResolvedParameter> parameters) {
        var metaCount = 0;
        for (final var resolved : parameters) {
            final var meta = resolved.parameter().isAnnotationPresent(Meta.class);
            final var named = resolved.parameter().isAnnotationPresent(McpParam.class);
            if ((meta && named)
                    || (isContext(resolved) && (meta || named))
                    || (resolved.rawType() == CompletionRequest.class && (meta || named))) {
                throw new IllegalStateException("Conflicting parameter annotations: " + method);
            }
            if (meta) {
                if (++metaCount > 1) throw new IllegalStateException("Use only one @Meta parameter: " + method);
                if (!JavaTypeSchemas.isObjectType(resolved.rawType())) {
                    throw new IllegalStateException("@Meta requires a Map, record or POJO: " + method);
                }
                if (Map.class.isAssignableFrom(resolved.rawType()) && !isRawMetaMap(resolved)) {
                    throw new IllegalStateException("@Meta requires Map<String, Object>: " + method);
                }
            }
        }
    }

    private static boolean isContext(ResolvedParameter parameter) {
        return InteractionContext.class.isAssignableFrom(parameter.rawType());
    }

    private static boolean isRawMetaMap(ResolvedParameter parameter) {
        if (!(parameter.type() instanceof ParameterizedType type) || type.getRawType() != Map.class) {
            return false;
        }
        final var arguments = type.getActualTypeArguments();
        return arguments.length == 2 && arguments[0] == String.class && arguments[1] == Object.class;
    }
}
