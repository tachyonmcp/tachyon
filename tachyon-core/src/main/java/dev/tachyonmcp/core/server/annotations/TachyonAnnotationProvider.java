/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.features.annotations.AnnotationInvocationSupport;
import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.core.server.json.JavaTypeSchemas;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * {@link AnnotationProvider} for Tachyon's own {@link McpTool @McpTool}, {@link McpResource
 *
 * @author Konstantin Pavlov
 * @McpResource}, and {@link McpPrompt @McpPrompt}. It is the default provider of {@code
 * ServerBuilder.annotations(...)}, so {@code annotations(a -> a.register(service))} needs no
 * further setup.
 *
 * <p>Discovery covers non-private methods declared on the instance's class, its superclasses, and
 * its public interfaces, so framework subclass proxies keep working. Registration fails fast on a
 * method carrying more than one feature annotation, on a duplicate tool/prompt name or resource
 * URI within one instance, on unsupported parameter types, and on parameter names missing because
 * the class was compiled without {@code -parameters}.
 */
@ExperimentalApi
public final class TachyonAnnotationProvider implements AnnotationProvider {

    private static final TachyonAnnotationProvider INSTANCE = new TachyonAnnotationProvider();
    private static final List<Class<? extends Annotation>> FEATURES =
            List.of(McpTool.class, McpResource.class, McpPrompt.class);
    private static final Pattern TEMPLATE_EXPRESSION = Pattern.compile("\\{[+#./;?&]?([^}]*)}");
    private static final String JSON = "application/json";

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
     * Returns whether {@code type} declares any {@link McpTool @McpTool}, {@link McpResource
     *
     * @param type the class to inspect
     * @return {@code true} if registering an instance of {@code type} would expose features
     * @McpResource}, or {@link McpPrompt @McpPrompt} method. Lets DI containers select candidate
     * beans without instantiating them.
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
        var methods = AnnotationInvocationSupport.discoverMethods(
                instance.getClass(), McpTool.class, McpResource.class, McpPrompt.class);
        var keys = new HashSet<String>();
        for (Method method : methods) {
            var tool = method.getAnnotation(McpTool.class);
            var resource = method.getAnnotation(McpResource.class);
            var prompt = method.getAnnotation(McpPrompt.class);
            if ((tool != null ? 1 : 0) + (resource != null ? 1 : 0) + (prompt != null ? 1 : 0) > 1) {
                throw new IllegalStateException(
                        "Method must carry only one of @McpTool, @McpResource, @McpPrompt: " + method);
            }
            if (tool != null) registerTool(instance, method, tool, context, keys);
            if (resource != null) registerResource(instance, method, resource, context, keys);
            if (prompt != null) registerPrompt(instance, method, prompt, context, keys);
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
            Set<String> keys) {
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
        if (!variables.containsAll(invoker.argumentNames())) {
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
    }

    private static void registerPrompt(
            Object instance, Method method, McpPrompt prompt, AnnotationRegistrationContext context, Set<String> keys) {
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
    }

    private static boolean isFeature(Method method) {
        return FEATURES.stream().anyMatch(method::isAnnotationPresent);
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
