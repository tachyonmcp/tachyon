/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.annotations.spring.ai;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.domain.UriTemplate;
import dev.tachyonmcp.api.server.features.annotations.AnnotationInvocationSupport;
import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.annotations.ResolvedParameter;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptFn;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpProgressToken;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;

/**
 * {@link AnnotationProvider} that discovers Spring AI MCP annotations
 * ({@code @McpTool}, {@code @McpResource}, {@code @McpPrompt}) on an annotated instance and
 * registers the corresponding Tachyon descriptors.
 *
 * <p>Resource methods whose {@code uri} contains URI-template variables (e.g.
 * {@code file:///{path}}) are registered as resource templates.
 *
 * <p>Usage:
 * <pre>{@code
 * serverBuilder.annotations(ctx -> {
 *     ctx.withProvider(new SpringAiAnnotationProvider());
 *     ctx.register(new MyToolClass());
 * });
 * }</pre>
 */
public class SpringAiAnnotationProvider implements AnnotationProvider {

    private static final SpringAiAnnotationProvider INSTANCE = new SpringAiAnnotationProvider();

    /** Creates a provider. Prefer {@link #instance()}. */
    public SpringAiAnnotationProvider() {}

    /**
     * Returns the shared provider instance.
     *
     * @return the shared instance
     */
    public static SpringAiAnnotationProvider instance() {
        return INSTANCE;
    }

    @Override
    public void register(Object instance, AnnotationRegistrationContext context) {
        Class<?> clazz = instance.getClass();
        PayloadSerializer serializer = context.payloadSerializer();
        PayloadDeserializer deserializer = context.payloadDeserializer();
        for (Method method :
                AnnotationInvocationSupport.discoverMethods(clazz, McpTool.class, McpResource.class, McpPrompt.class)) {
            registerTool(instance, method, context, serializer, deserializer);
            registerResource(instance, method, context, serializer, deserializer);
            registerPrompt(instance, method, context, serializer, deserializer);
        }
    }

    private void registerTool(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        McpTool annotation = method.getAnnotation(McpTool.class);
        if (annotation == null) return;

        final List<ResolvedParameter> parameters = AnnotationInvocationSupport.parameters(method, instance.getClass());
        ToolDescriptor descriptor = ToolDescriptor.builder()
                .name(resolveName(annotation.name(), method))
                .description(blankToNull(annotation.description()))
                .title(blankToNull(annotation.title()))
                .inputSchema(buildInputSchema(method, parameters, deserializer))
                .annotations(mapToolAnnotations(annotation))
                .build();

        ToolFn fn = (ctx, req) -> invokeTool(instance, method, parameters, ctx, req, serializer, deserializer);
        context.tools().register(descriptor, fn);
    }

    private static ToolAnnotations mapToolAnnotations(McpTool annotation) {
        McpTool.McpAnnotations ann = annotation.annotations();
        return ToolAnnotations.builder()
                .title(blankToNull(ann.title()))
                .readOnlyHint(ann.readOnlyHint())
                .destructiveHint(ann.destructiveHint())
                .idempotentHint(ann.idempotentHint())
                .openWorldHint(ann.openWorldHint())
                .build();
    }

    private ToolResult invokeTool(
            Object instance,
            Method method,
            List<ResolvedParameter> parameters,
            InteractionContext ctx,
            ToolRequest req,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer)
            throws Exception {
        Object result = invoke(
                method, instance, resolveArgs(parameters, ctx, req.arguments().asMap(), serializer, deserializer));
        return convertToolResult(result);
    }

    /**
     * Invokes {@code method} on {@code instance}, unwrapping {@link InvocationTargetException} to its cause.
     */
    private static @Nullable Object invoke(Method method, Object instance, Object... args) throws Exception {
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw AnnotationInvocationSupport.unwrap(e);
        }
    }

    private void registerResource(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        McpResource annotation = method.getAnnotation(McpResource.class);
        if (annotation == null) return;

        String uri = annotation.uri();
        if (uri.isBlank()) {
            throw new IllegalArgumentException("@McpResource on " + method + " requires a non-blank uri");
        }

        final List<ResolvedParameter> parameters = AnnotationInvocationSupport.parameters(method, instance.getClass());
        for (ResolvedParameter param : parameters) {
            if (InteractionContext.class.isAssignableFrom(param.rawType())) continue;
            requireNotUnsupportedSpringType(param, method);
            AnnotationInvocationSupport.requireBindable(param, method);
        }

        final String mimeType = blankToNull(annotation.mimeType());

        if (uri.contains("{")) {
            UriTemplate.create(uri);
            ResourceTemplateDescriptor descriptor = ResourceTemplateDescriptor.builder()
                    .name(resolveName(annotation.name(), method))
                    .uriTemplate(uri)
                    .description(blankToNull(annotation.description()))
                    .title(blankToNull(annotation.title()))
                    .mimeType(mimeType)
                    .build();
            context.resources().registerTemplate(descriptor, (ctx, req) -> {
                Map<String, Object> values = new LinkedHashMap<>();
                req.params().forEach((name, value) -> values.put(name, value.scalarValue()));
                return convertResourceContents(
                        invoke(method, instance, resolveArgs(parameters, ctx, values, serializer, deserializer)),
                        req.uri(),
                        mimeType,
                        serializer);
            });
        } else {
            ResourceDescriptor descriptor = ResourceDescriptor.builder()
                    .name(resolveName(annotation.name(), method))
                    .uri(uri)
                    .description(blankToNull(annotation.description()))
                    .title(blankToNull(annotation.title()))
                    .mimeType(mimeType)
                    .build();
            context.resources()
                    .register(
                            descriptor,
                            (ctx, req) -> convertResourceContents(
                                    invoke(
                                            method,
                                            instance,
                                            resolveArgs(parameters, ctx, Map.of(), serializer, deserializer)),
                                    req.uri(),
                                    mimeType,
                                    serializer));
        }
    }

    private void registerPrompt(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        McpPrompt annotation = method.getAnnotation(McpPrompt.class);
        if (annotation == null) return;

        PromptDescriptor.Builder builder = PromptDescriptor.builder()
                .name(resolveName(annotation.name(), method))
                .description(blankToNull(annotation.description()))
                .title(blankToNull(annotation.title()));
        final List<ResolvedParameter> parameters = AnnotationInvocationSupport.parameters(method, instance.getClass());
        for (ResolvedParameter resolved : parameters) {
            if (InteractionContext.class.isAssignableFrom(resolved.rawType())) continue;
            requireNotUnsupportedSpringType(resolved, method);
            AnnotationInvocationSupport.requireBindable(resolved, method);
            final Parameter param = resolved.parameter();

            McpArg argAnnotation = param.getAnnotation(McpArg.class);
            String argName = resolveArgName(param);
            String description =
                    (argAnnotation != null && !argAnnotation.description().isBlank())
                            ? argAnnotation.description()
                            : null;
            boolean required = argAnnotation == null || argAnnotation.required();
            builder.addArguments(PromptArgument.of(argName, null, description, required));
        }
        PromptDescriptor descriptor = builder.build();

        PromptFn fn = (ctx, req) -> convertPromptResult(
                invoke(
                        method,
                        instance,
                        resolveArgs(parameters, ctx, req.arguments().asMap(), serializer, deserializer)),
                serializer);
        context.prompts().register(descriptor, fn);
    }

    private Object[] resolveArgs(
            List<ResolvedParameter> parameters,
            InteractionContext ctx,
            @Nullable Map<String, Object> values,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        Object[] resolved = new Object[parameters.size()];
        Map<String, Object> map = values != null ? values : Map.of();
        for (int i = 0; i < resolved.length; i++) {
            final ResolvedParameter param = parameters.get(i);
            if (InteractionContext.class.isAssignableFrom(param.rawType())) {
                resolved[i] = ctx;
            } else {
                resolved[i] = AnnotationInvocationSupport.coerce(
                        map.get(resolveArgName(param.parameter())), param.type(), serializer, deserializer);
            }
        }
        return resolved;
    }

    /** Resolves the argument's wire name, honoring a {@link McpArg#name()} override when present. */
    private static String resolveArgName(Parameter param) {
        McpArg ann = param.getAnnotation(McpArg.class);
        if (ann != null && !ann.name().isBlank()) {
            return ann.name();
        }
        return param.getName();
    }

    private static ToolResult convertToolResult(@Nullable Object result) {
        return switch (result) {
            case null -> ToolResult.empty();
            case ToolResult tr -> tr;
            case String s -> ToolResult.text(s);
            case ContentBlock cb -> ToolResult.content(cb);
            case Number n -> ToolResult.text(n.toString());
            case Boolean b -> ToolResult.text(b.toString());
            case Character c -> ToolResult.text(c.toString());
            default -> ToolResult.structured(result);
        };
    }

    private static ResourceContents convertResourceContents(
            @Nullable Object result, String uri, @Nullable String mimeType, PayloadSerializer serializer) {
        if (result instanceof ResourceContents rc) {
            return rc;
        }
        if (result == null) {
            throw new IllegalStateException("@McpResource method for uri '" + uri + "' returned null");
        }
        if (result instanceof String s) {
            return TextResourceContents.of(uri, s, mimeType);
        }
        return TextResourceContents.of(uri, serializer.serialize(result), mimeType);
    }

    private static PromptResult convertPromptResult(@Nullable Object result, PayloadSerializer serializer) {
        if (result instanceof PromptResult pr) {
            return pr;
        }
        if (result instanceof PromptMessage pm) {
            return PromptResult.messages(List.of(pm));
        }
        if (result instanceof String s) {
            return PromptResult.messages(List.of(PromptMessage.user(s)));
        }
        return PromptResult.messages(List.of(PromptMessage.user(serializer.serialize(result))));
    }

    /**
     * Builds the tool's input schema via Spring AI's own {@link McpJsonSchemaGenerator}, which
     * (unlike {@link AnnotationInvocationSupport#jsonSchemaType}) correctly describes records,
     * enums, {@code List<T>}, and nested POJOs — types the shared scalar-only mapping rejects.
     * Spring AI's generator already excludes its own request-context types (e.g. {@code
     * McpSyncRequestContext}) from a whole-method schema, but knows nothing of Tachyon's {@link
     * InteractionContext} or unwrapping {@code Optional<T>}, so both are still handled here,
     * per-parameter, before delegating the type shape to the generator. Required-ness is decided
     * here too, always required unless {@code @McpToolParam(required = false)} or an {@code
     * Optional<T>} says otherwise — Spring AI's own generator instead reads a configurable
     * {@code PROPERTY_REQUIRED_BY_DEFAULT}.
     */
    private JsonSchema buildInputSchema(
            Method method, List<ResolvedParameter> parameters, PayloadDeserializer deserializer) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (ResolvedParameter resolved : parameters) {
            if (InteractionContext.class.isAssignableFrom(resolved.rawType())) continue;
            requireNotUnsupportedSpringType(resolved, method);
            final Parameter param = resolved.parameter();

            Type type = resolved.type();
            boolean optional = AnnotationInvocationSupport.isOptionalType(type);
            Type schemaType = optional ? AnnotationInvocationSupport.unwrapOptional(type) : type;

            String rawSchema = McpJsonSchemaGenerator.generateFromType(schemaType);
            final var prop = new LinkedHashMap<String, Object>(deserializer.deserialize(rawSchema, Map.class));
            prop.remove("$schema");

            McpToolParam ann = param.getAnnotation(McpToolParam.class);
            if (ann != null && !ann.description().isEmpty()) prop.put("description", ann.description());
            boolean isRequired = (ann == null || ann.required()) && !optional;

            String argName = resolveArgName(param);
            properties.put(argName, prop);
            if (isRequired) required.add(argName);
        }

        return AnnotationInvocationSupport.inputSchema(properties, required);
    }

    /**
     * Special parameter types Spring AI's own method-callback classes (tool/resource/prompt)
     * inject by type rather than by binding a wire argument — confirmed against {@code
     * spring-ai-mcp-annotations}' {@code AbstractMcp*MethodCallback} classes, which recognize
     * these exact mcp-core/mcp-schema types for context/exchange/request injection. None of them
     * live under {@code org.springframework.ai.mcp.annotation}, so the package-prefix check below
     * can't catch them.
     */
    private static final Set<Class<?>> UNSUPPORTED_SPRING_INJECTED_TYPES = Set.of(
            McpTransportContext.class,
            McpSyncServerExchange.class,
            McpAsyncServerExchange.class,
            McpSchema.CallToolRequest.class,
            McpSchema.ReadResourceRequest.class,
            McpSchema.GetPromptRequest.class);

    /**
     * Rejects parameters that Spring AI would inject itself rather than bind from a wire argument
     * at registration time — framework-internal types/annotations Tachyon does not emulate, that
     * no schema can meaningfully describe and no wire value could ever populate. This covers
     * parameter types under Spring AI's own annotation/context package (e.g. {@code
     * McpSyncRequestContext}, {@code McpMeta}), the mcp-core/mcp-schema types Spring AI injects by
     * type ({@link #UNSUPPORTED_SPRING_INJECTED_TYPES}), and {@code @McpProgressToken}, which
     * injects into an ordinary-looking parameter (typically {@code String}) via annotation rather
     * than type, so it can't be caught by a type check at all. User-defined argument types
     * (records, enums, POJOs) are unaffected and flow through to {@link McpJsonSchemaGenerator}.
     */
    private static void requireNotUnsupportedSpringType(ResolvedParameter resolved, Method method) {
        final Parameter param = resolved.parameter();
        Class<?> type = resolved.rawType();
        if (param.isAnnotationPresent(McpProgressToken.class)) {
            throw new IllegalStateException("Unsupported @McpProgressToken parameter '" + param.getName() + "' on "
                    + method
                    + " — Tachyon's Spring AI annotation adapter does not provide Spring AI progress-token"
                    + " injection.");
        }
        boolean unsupported = type.getPackageName().startsWith("org.springframework.ai.mcp.annotation")
                || UNSUPPORTED_SPRING_INJECTED_TYPES.stream().anyMatch(injected -> injected.isAssignableFrom(type));
        if (unsupported) {
            throw new IllegalStateException("Unsupported parameter type " + type.getName() + " for parameter '"
                    + param.getName() + "' on " + method
                    + " — Tachyon's Spring AI annotation adapter does not provide Spring AI request-context"
                    + " emulation for this type.");
        }
    }

    private static String resolveName(String annotationValue, Method method) {
        return annotationValue.isBlank() ? method.getName() : annotationValue;
    }

    /** Spring AI's annotation attributes default to {@code ""}; Tachyon's descriptors want {@code null}. */
    private static @Nullable String blankToNull(String value) {
        return value.isBlank() ? null : value;
    }
}
