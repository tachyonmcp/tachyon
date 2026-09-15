/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.McpCompletion;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.features.annotations.AnnotationInvocationSupport;
import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.completions.CompletionFn;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.core.server.json.JavaTypeSchemas;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@link AnnotationProvider} for Tachyon's own {@link McpTool @McpTool},
 * {@link McpResource @McpResource}, {@link McpPrompt @McpPrompt}, and {@link McpCompletion @McpCompletion}.
 * It is the default provider of {@code
 * ServerBuilder.annotations(...)}, so {@code annotations(a -> a.register(service))} needs no
 * further setup.
 *
 * <p>Discovery covers non-private methods declared on the instance's class, its superclasses, and
 * its public interfaces, so framework subclass proxies keep working. Registration fails fast on a
 * method carrying more than one feature annotation, on a duplicate tool/prompt name or resource
 * URI within one instance, on unsupported parameter types, and on parameter names missing because
 * the class was compiled without {@code -parameters}. A named {@link McpCompletion @McpCompletion}
 * whose argument is not declared by its known prompt or resource template also fails.
 *
 * <p>Enum-typed prompt and resource template arguments complete automatically from constant names
 * (case-insensitive prefix) unless the same instance declares an {@code @McpCompletion} for that
 * target.
 */
@ExperimentalApi
public final class TachyonAnnotationProvider implements AnnotationProvider {

    private static final TachyonAnnotationProvider INSTANCE = new TachyonAnnotationProvider();
    private static final List<Class<? extends Annotation>> FEATURES =
            List.of(McpTool.class, McpResource.class, McpPrompt.class, McpCompletion.class);
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\{[+#./;?&]?([^}]*)}");
    private static final String JSON = "application/json";
    private static final String PROMPT = "prompt:";
    private static final String RESOURCE = "resource:";

    private TachyonAnnotationProvider() {}

    /**
     * Returns the shared stateless provider.
     *
     * @return the provider
     */
    public static TachyonAnnotationProvider instance() {
        return INSTANCE;
    }

    /**
     * Returns whether {@code type} declares any {@link McpTool @McpTool},
     * {@link McpResource @McpResource}, {@link McpPrompt @McpPrompt}, or {@link McpCompletion @McpCompletion} method.
     * Lets DI containers select candidate beans without instantiating them.
     *
     * @param type the class to inspect
     * @return {@code true} if registering an instance of {@code type} would expose features
     */
    public static boolean declaresFeatures(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (isFeature(method)) return true;
            }
        }
        for (Method method : type.getMethods()) {
            if (isFeature(method)) return true;
        }
        return false;
    }

    @Override
    public void register(Object instance, AnnotationRegistrationContext context) {
        register(instance, instance.getClass(), context);
    }

    /**
     * Registers features declared by a target type, invoking methods on the supplied instance.
     * A DI container may supply an interface proxy and its target class separately. Every exposed
     * method must be invocable on the proxy; the target is never invoked directly.
     *
     * @param instance the invocation receiver, including any framework advice
     * @param annotatedType the type carrying feature and parameter metadata
     * @param context the registries and payload codecs
     */
    public void register(Object instance, Class<?> annotatedType, AnnotationRegistrationContext context) {
        var methods = AnnotationInvocationSupport.discoverMethods(
                annotatedType, McpTool.class, McpResource.class, McpPrompt.class, McpCompletion.class);
        var keys = new HashSet<String>();
        final var completed = new HashSet<String>();
        for (Method method : methods) {
            if (FEATURES.stream().filter(method::isAnnotationPresent).count() > 1) {
                throw new IllegalStateException(
                        "Method must carry only one of @McpTool, @McpResource, @McpPrompt, @McpCompletion: " + method);
            }
            final var completion = method.getAnnotation(McpCompletion.class);
            if (completion != null) {
                completed.add(PROMPT + completion.prompt());
                completed.add(RESOURCE + completion.resource());
            }
        }
        final var declared = new HashMap<String, List<String>>();
        for (Method method : methods) {
            var tool = method.getAnnotation(McpTool.class);
            var resource = method.getAnnotation(McpResource.class);
            var prompt = method.getAnnotation(McpPrompt.class);
            if (tool != null) registerTool(instance, method, tool, context, keys);
            if (resource != null) registerResource(instance, method, resource, context, keys, declared, completed);
            if (prompt != null) registerPrompt(instance, method, prompt, context, keys, declared, completed);
        }
        for (Method method : methods) {
            final var completion = method.getAnnotation(McpCompletion.class);
            if (completion != null) registerCompletion(instance, method, completion, context, keys, declared);
        }
    }

    private static void registerTool(
            Object instance, Method method, McpTool tool, AnnotationRegistrationContext context, Set<String> keys) {
        var name = nameOf(tool.name(), method);
        claim(keys, "tool '" + name + "'", method);
        var invoker = MethodInvoker.forTool(instance, method, context);
        var returnType = method.getReturnType();
        var serializer = context.payloadSerializer();
        context.tools()
                .register(
                        builder -> builder.name(name)
                                .description(emptyToNull(tool.description()))
                                .inputSchema(invoker.inputSchema())
                                .outputSchema(
                                        JavaTypeSchemas.isObjectType(returnType)
                                                ? JsonSchema.generate(returnType)
                                                : null),
                        (ctx, request) -> ResultMappers.toolResult(
                                invoker.invoke(ctx, request.arguments().asMap()), serializer));
    }

    private static void registerResource(
            Object instance,
            Method method,
            McpResource resource,
            AnnotationRegistrationContext context,
            Set<String> keys,
            Map<String, List<String>> declared,
            Set<String> completed) {
        var uri = resource.uri();
        if (uri.isBlank()) throw new IllegalStateException("@McpResource uri must not be blank: " + method);
        claim(keys, "resource '" + uri + "'", method);
        var name = nameOf(resource.name(), method);
        var mimeType = mimeTypeOf(resource, method);
        var invoker = MethodInvoker.forArguments(instance, method, context);
        var serializer = context.payloadSerializer();
        var variables = templateVariables(uri);
        if (variables.isEmpty()) {
            if (!invoker.argumentNames().isEmpty()) {
                throw new IllegalStateException("Static @McpResource method must not declare arguments "
                        + invoker.argumentNames() + "; use a {variable} URI template: " + method);
            }
            context.resources()
                    .register(
                            builder -> builder.uri(uri)
                                    .name(name)
                                    .description(emptyToNull(resource.description()))
                                    .mimeType(mimeType),
                            (ctx, request) -> ResultMappers.resourceContents(
                                    invoker.invoke(ctx, Map.of()), request.uri(), mimeType, serializer));
            return;
        }
        if (!variables.containsAll(new LinkedHashSet<>(invoker.argumentNames()))) {
            throw new IllegalStateException("@McpResource parameters " + invoker.argumentNames()
                    + " must match URI template variables " + variables + ": " + method);
        }
        context.resources()
                .registerTemplate(
                        ResourceTemplateDescriptor.builder()
                                .uriTemplate(uri)
                                .name(name)
                                .description(emptyToNull(resource.description()))
                                .mimeType(mimeType)
                                .build(),
                        (ctx, request) -> {
                            var values = new LinkedHashMap<String, @Nullable Object>();
                            request.params().forEach((variable, value) -> values.put(variable, value.scalarValue()));
                            return ResultMappers.resourceContents(
                                    invoker.invoke(ctx, values), request.uri(), mimeType, serializer);
                        });
        declared.put(RESOURCE + uri, List.copyOf(variables));
        registerEnumCompletion(
                invoker, RESOURCE + uri, completed, fn -> context.completions().registerForResource(uri, fn));
    }

    private static void registerPrompt(
            Object instance,
            Method method,
            McpPrompt prompt,
            AnnotationRegistrationContext context,
            Set<String> keys,
            Map<String, List<String>> declared,
            Set<String> completed) {
        var name = nameOf(prompt.name(), method);
        claim(keys, "prompt '" + name + "'", method);
        var invoker = MethodInvoker.forArguments(instance, method, context);
        var serializer = context.payloadSerializer();
        context.prompts()
                .register(
                        builder -> builder.name(name)
                                .description(emptyToNull(prompt.description()))
                                .arguments(invoker.promptArguments()),
                        (ctx, request) -> ResultMappers.promptResult(
                                prompt.role(),
                                invoker.invoke(ctx, request.arguments().asMap()),
                                serializer));
        declared.put(PROMPT + name, invoker.argumentNames());
        registerEnumCompletion(
                invoker, PROMPT + name, completed, fn -> context.completions().registerForPrompt(name, fn));
    }

    private static void registerEnumCompletion(
            MethodInvoker invoker, String target, Set<String> completed, Consumer<CompletionFn> register) {
        final var constants = invoker.enumArguments();
        if (constants.isEmpty() || completed.contains(target)) return;
        register.accept((ctx, request) -> {
            final var candidates = constants.get(request.argumentName());
            if (candidates == null) return CompletionResult.empty();
            final var prefix = request.argumentValue().toUpperCase(Locale.ROOT);
            return CompletionResult.of(candidates.stream()
                    .filter(candidate -> candidate.toUpperCase(Locale.ROOT).startsWith(prefix))
                    .toList());
        });
    }

    private static boolean isFeature(Method method) {
        return FEATURES.stream().anyMatch(method::isAnnotationPresent);
    }

    private static void registerCompletion(
            Object instance,
            Method method,
            McpCompletion completion,
            AnnotationRegistrationContext context,
            Set<String> keys,
            Map<String, List<String>> declared) {
        final var prompt = !completion.prompt().isBlank();
        if (prompt == !completion.resource().isBlank()) {
            throw new IllegalStateException("@McpCompletion must specify exactly one of prompt or resource: " + method);
        }
        final var target = prompt ? completion.prompt() : completion.resource();
        claim(keys, "completion " + (prompt ? "prompt '" : "resource '") + target + "'", method);
        ResultMappers.requireCompletionReturnType(method);
        final var invoker = MethodInvoker.forCompletion(instance, method, context);
        final var arguments = invoker.argumentNames();
        final String argument = arguments.isEmpty() ? null : arguments.getFirst();
        if (argument != null) {
            final var known = Optional.ofNullable(declared.get((prompt ? PROMPT : RESOURCE) + target))
                    .or(() -> registeredArguments(context, prompt, target));
            if (known.isPresent() && !known.get().contains(argument)) {
                throw new IllegalStateException("@McpCompletion argument '" + argument + "' is not declared by "
                        + (prompt ? "prompt '" : "resource '") + target + "' " + known.get() + ": " + method);
            }
        }
        final CompletionFn fn = (ctx, request) -> {
            if (argument != null && !argument.equals(request.argumentName())) return CompletionResult.empty();
            return ResultMappers.completionResult(invoker.invokeCompletion(ctx, request));
        };
        if (prompt) context.completions().registerForPrompt(target, fn);
        else context.completions().registerForResource(target, fn);
    }

    private static Optional<List<String>> registeredArguments(
            AnnotationRegistrationContext context, boolean prompt, String target) {
        if (prompt) {
            return context.prompts()
                    .find(target)
                    .map(descriptor -> descriptor.arguments().stream()
                            .map(PromptArgument::name)
                            .toList());
        }
        return context.resources().templateDescriptors().stream()
                .filter(descriptor -> descriptor.uriTemplate().equals(target))
                .findFirst()
                .map(descriptor -> List.copyOf(templateVariables(target)));
    }

    private static void claim(Set<String> keys, String key, Method method) {
        if (!keys.add(key)) throw new IllegalStateException("Duplicate " + key + " declared by " + method);
    }

    private static String nameOf(String declared, Method method) {
        return declared.isBlank() ? method.getName() : declared;
    }

    private static @Nullable String mimeTypeOf(McpResource resource, Method method) {
        if (!resource.mimeType().isBlank()) return resource.mimeType();
        return JavaTypeSchemas.isObjectType(method.getReturnType()) ? JSON : null;
    }

    private static Set<String> templateVariables(String uri) {
        var variables = new LinkedHashSet<String>();
        var matcher = TEMPLATE_EXPRESSION.matcher(uri);
        while (matcher.find()) {
            for (String variable : matcher.group(1).split(",")) {
                variables.add(variable.replaceAll("[*]|:\\d+$", "").trim());
            }
        }
        return variables;
    }

    private static @Nullable String emptyToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
