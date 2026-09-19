/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.annotations.mcpjava;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.json.PayloadDeserializer;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Annotations;
import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.PromptArgument;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.annotations.AnnotationInvocationSupport;
import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.api.server.features.annotations.AnnotationRegistrationContext;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.prompts.PromptFn;
import dev.tachyonmcp.api.server.features.prompts.PromptRequest;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.api.server.features.resources.ResourceFn;
import dev.tachyonmcp.api.server.features.resources.ResourceRequest;
import dev.tachyonmcp.api.server.features.resources.ResourceTemplateDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.mcpjava.server.prompts.Prompt;
import org.mcpjava.server.prompts.PromptArg;
import org.mcpjava.server.resources.Resource;
import org.mcpjava.server.resources.ResourceTemplate;
import org.mcpjava.server.resources.ResourceTemplateArg;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;
import org.mcpjava.server.tools.ToolResponse;

/**
 * {@link AnnotationProvider} that discovers mcp-java annotations
 * ({@code @Tool}, {@code @Resource}, {@code @ResourceTemplate}, {@code @Prompt})
 * on an annotated instance and registers the corresponding Tachyon descriptors.
 *
 * <p>Usage:
 * <pre>{@code
 * serverBuilder.annotations(ctx -> {
 *     ctx.withProvider(new McpJavaAnnotationProvider());
 *     ctx.register(new MyToolClass());
 * });
 * }</pre>
 */
public class McpJavaAnnotationProvider implements AnnotationProvider {

    private static final String ELEMENT_NAME = "<<element name>>";
    private static final McpJavaAnnotationProvider INSTANCE = new McpJavaAnnotationProvider();

    public static McpJavaAnnotationProvider instance() {
        return INSTANCE;
    }

    @Override
    public void register(Object instance, AnnotationRegistrationContext context) {
        Class<?> clazz = instance.getClass();
        PayloadSerializer serializer = context.payloadSerializer();
        PayloadDeserializer deserializer = context.payloadDeserializer();
        List<Method> methods = AnnotationInvocationSupport.discoverMethods(
                clazz, Tool.class, Resource.class, ResourceTemplate.class, Prompt.class);
        for (Method method : methods) {
            registerTools(instance, method, context, serializer, deserializer);
            registerResources(instance, method, context, serializer, deserializer);
            registerResourceTemplates(instance, method, context, serializer, deserializer);
            registerPrompts(instance, method, context, serializer, deserializer);
        }
    }

    private void registerTools(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        Tool annotation = method.getAnnotation(Tool.class);
        if (annotation == null) return;

        String name = resolveName(annotation.name(), method);
        String description = annotation.description().isEmpty() ? null : annotation.description();
        String title = annotation.title().isEmpty() ? null : annotation.title();

        ToolAnnotations toolAnnotations = mapToolAnnotations(annotation.annotations());
        JsonSchema inputSchema = buildInputSchema(method, ToolArg.class);

        ToolDescriptor descriptor = ToolDescriptor.builder()
                .name(name)
                .description(description)
                .title(title)
                .inputSchema(inputSchema)
                .annotations(toolAnnotations)
                .build();

        ToolFn fn = (ctx, req) -> invokeTool(instance, method, ctx, req, serializer, deserializer);
        context.tools().register(descriptor, fn);
    }

    private ToolAnnotations mapToolAnnotations(Tool.Annotations ann) {
        return ToolAnnotations.builder()
                .readOnlyHint(ann.readOnlyHint())
                .destructiveHint(ann.destructiveHint())
                .idempotentHint(ann.idempotentHint())
                .openWorldHint(ann.openWorldHint())
                .build();
    }

    private ToolResult invokeTool(
            Object instance,
            Method method,
            InteractionContext ctx,
            ToolRequest req,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer)
            throws Exception {
        Object[] args = resolveArgs(method, ctx, req.arguments().asMap(), ToolArg.class, serializer, deserializer);
        Object result = invoke(method, instance, args);
        return convertToolResult(result, serializer);
    }

    /**
     * Invokes {@code method} on {@code instance}, unwrapping {@link InvocationTargetException} to its cause.
     */
    private Object invoke(Method method, Object instance, Object... args) throws Exception {
        try {
            return method.invoke(instance, args);
        } catch (InvocationTargetException e) {
            throw AnnotationInvocationSupport.unwrap(e);
        }
    }

    private Object[] resolveArgs(
            Method method,
            InteractionContext ctx,
            Map<String, Object> values,
            Class<? extends Annotation> argAnnotation,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        Parameter[] params = method.getParameters();
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Class<?> type = params[i].getType();
            if (InteractionContext.class.isAssignableFrom(type)) {
                args[i] = ctx;
                continue;
            }
            String paramName = resolveParamName(params[i], argAnnotation);
            Type parameterizedType = params[i].getParameterizedType();
            if (values.containsKey(paramName)) {
                args[i] = AnnotationInvocationSupport.coerce(
                        values.get(paramName), parameterizedType, serializer, deserializer);
            } else {
                Annotation ann = params[i].getAnnotation(argAnnotation);
                String defaultVal = ann != null ? getStringAttribute(ann, "defaultValue") : null;
                Object literal = (defaultVal != null && !defaultVal.isEmpty())
                        ? parseDefaultLiteral(defaultVal, parameterizedType)
                        : null;
                args[i] = AnnotationInvocationSupport.coerce(literal, parameterizedType, serializer, deserializer);
            }
        }
        return args;
    }

    /** Parses an annotation's {@code defaultValue} string literal as the parameter's declared type. */
    private static @Nullable Object parseDefaultLiteral(String literal, Type type) {
        Type effective = AnnotationInvocationSupport.unwrapOptional(type);
        if (effective == int.class || effective == Integer.class) return Integer.valueOf(literal);
        if (effective == long.class || effective == Long.class) return Long.valueOf(literal);
        if (effective == short.class || effective == Short.class) return Short.valueOf(literal);
        if (effective == byte.class || effective == Byte.class) return Byte.valueOf(literal);
        if (effective == double.class || effective == Double.class) return Double.valueOf(literal);
        if (effective == float.class || effective == Float.class) return Float.valueOf(literal);
        if (effective == boolean.class || effective == Boolean.class) return Boolean.valueOf(literal);
        return literal;
    }

    private ToolResult convertToolResult(@Nullable Object result, PayloadSerializer serializer) {
        if (result == null) {
            return ToolResult.empty();
        }
        switch (result) {
            case ToolResult tr -> {
                return tr;
            }
            case ToolResponse tr -> {
                return convertMcpJavaToolResponse(tr);
            }
            case String s -> {
                return ToolResult.text(s);
            }
            case ContentBlock cb -> {
                return ToolResult.content(cb);
            }
            case List<?> list -> {
                List<ContentBlock> blocks = new ArrayList<>();
                for (Object item : list) {
                    blocks.add(toContentBlock(item, serializer));
                }
                return ToolResult.content(blocks.toArray(new ContentBlock[0]));
            }
            default -> {}
        }
        if (result instanceof Number || result instanceof Boolean || result instanceof Character) {
            return ToolResult.text(result.toString());
        }
        return ToolResult.structured(result);
    }

    /**
     * Converts one {@code List} item from an annotated method's return value to a content block:
     * an existing {@link ContentBlock} passes through, a scalar
     * becomes its text form, and anything else (a record, POJO, etc.) is JSON-serialized rather
     * than rendered via {@code toString()}.
     */
    private static ContentBlock toContentBlock(@Nullable Object item, PayloadSerializer serializer) {
        if (item instanceof ContentBlock cb) return cb;
        if (item instanceof String s) return TextContent.of(s);
        if (item instanceof Number || item instanceof Boolean || item instanceof Character) {
            return TextContent.of(String.valueOf(item));
        }
        return TextContent.of(serializer.serialize(item));
    }

    /**
     * Translates mcp-java's native {@link ToolResponse}, preserving its
     * content blocks, structured content, and error status rather than falling back to {@code
     * toString()}.
     */
    private ToolResult convertMcpJavaToolResponse(ToolResponse response) {
        List<ContentBlock> blocks = response.content().stream()
                .map(this::convertMcpJavaContentBlock)
                .toList();
        if (response.isError()) {
            return ToolResult.Error.builder().content(blocks).build();
        }
        return ToolResult.Success.builder()
                .structuredValue(response.structuredContent().orElse(null))
                .content(blocks)
                .build();
    }

    private ContentBlock convertMcpJavaContentBlock(org.mcpjava.server.content.ContentBlock block) {
        return switch (block) {
            case org.mcpjava.server.content.TextContent tc -> TextContent.of(tc.text());
            case org.mcpjava.server.content.ImageContent ic ->
                dev.tachyonmcp.api.server.domain.ImageContent.of(
                        ic.data(), ic.mimeType(), convertMcpJavaAnnotations(ic.annotations()));
            case org.mcpjava.server.content.AudioContent ac ->
                dev.tachyonmcp.api.server.domain.AudioContent.of(
                        ac.data(), ac.mimeType(), convertMcpJavaAnnotations(ac.annotations()));
            case org.mcpjava.server.content.ResourceLink rl ->
                dev.tachyonmcp.api.server.domain.ResourceLink.builder(rl.uri(), rl.name())
                        .title(rl.title())
                        .description(rl.description().orElse(null))
                        .mimeType(rl.mimeType().orElse(null))
                        .annotations(convertMcpJavaAnnotations(rl.annotations()))
                        .size(rl.size().isPresent() ? rl.size().getAsLong() : null)
                        .build();
            case org.mcpjava.server.content.EmbeddedResource er ->
                dev.tachyonmcp.api.server.domain.EmbeddedResource.of(
                        convertMcpJavaResourceContents(er.resource()), convertMcpJavaAnnotations(er.annotations()));
            default ->
                // Escape hatch for a future mcp-java ContentBlock subtype this mapping doesn't know about yet.
                TextContent.of(block.toString());
        };
    }

    private static ResourceContents convertMcpJavaResourceContents(org.mcpjava.server.resources.ResourceContents rc) {
        if (rc instanceof org.mcpjava.server.resources.TextResourceContents trc) {
            return TextResourceContents.of(trc.uri(), trc.text(), trc.mimeType().orElse(null));
        }
        if (rc instanceof org.mcpjava.server.resources.BlobResourceContents brc) {
            return BlobResourceContents.of(brc.uri(), brc.blob(), brc.mimeType().orElse(null));
        }
        throw new IllegalStateException("Unsupported mcp-java resource contents type: " + rc.getClass());
    }

    private static @Nullable Annotations convertMcpJavaAnnotations(
            Optional<org.mcpjava.server.content.Annotations> maybeAnnotations) {
        if (maybeAnnotations.isEmpty()) return null;
        org.mcpjava.server.content.Annotations ann = maybeAnnotations.get();
        List<Role> audience = ann.audience()
                .map(roles -> roles.stream().map(r -> Role.valueOf(r.name())).toList())
                .orElse(List.of());
        Double priority = ann.priority().isPresent() ? ann.priority().getAsDouble() : null;
        String lastModified = ann.lastModified().map(Object::toString).orElse(null);
        return Annotations.of(audience, priority, lastModified);
    }

    private void registerResources(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        Resource annotation = method.getAnnotation(Resource.class);
        if (annotation == null) return;

        for (Parameter param : method.getParameters()) {
            if (InteractionContext.class.isAssignableFrom(param.getType())) continue;
            AnnotationInvocationSupport.requireBindable(param, method);
        }

        String name = resolveName(annotation.name(), method);
        String description = annotation.description().isEmpty() ? null : annotation.description();
        String title = annotation.title().isEmpty() ? null : annotation.title();
        String mimeType = annotation.mimeType().isEmpty() ? null : annotation.mimeType();
        Long size = annotation.size() >= 0 ? (long) annotation.size() : null;

        ResourceDescriptor descriptor = ResourceDescriptor.builder()
                .name(name)
                .uri(annotation.uri())
                .description(description)
                .title(title)
                .mimeType(mimeType)
                .size(size)
                .build();

        ResourceFn fn = (ctx, req) -> invokeResource(instance, method, ctx, req, serializer, deserializer, mimeType);
        context.resources().register(descriptor, fn);
    }

    private ResourceContents invokeResource(
            Object instance,
            Method method,
            InteractionContext ctx,
            ResourceRequest req,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer,
            @Nullable String mimeType)
            throws Exception {
        Object[] args = resolveArgs(method, ctx, Map.of(), ResourceTemplateArg.class, serializer, deserializer);
        Object result = invoke(method, instance, args);
        return convertResourceContents(result, req.uri(), mimeType, serializer);
    }

    private ResourceContents convertResourceContents(
            @Nullable Object result, String uri, @Nullable String mimeType, PayloadSerializer serializer) {
        if (result instanceof ResourceContents rc) return rc;
        if (result == null) {
            throw new IllegalStateException("@Resource method for uri '" + uri + "' returned null");
        }
        if (result instanceof String s) {
            return TextResourceContents.of(uri, s, mimeType);
        }
        if (result instanceof byte[] bytes) {
            return BlobResourceContents.of(uri, bytes, mimeType);
        }
        return TextResourceContents.of(uri, serializer.serialize(result), mimeType);
    }

    private void registerResourceTemplates(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        ResourceTemplate annotation = method.getAnnotation(ResourceTemplate.class);
        if (annotation == null) return;

        for (Parameter param : method.getParameters()) {
            if (InteractionContext.class.isAssignableFrom(param.getType())) continue;
            AnnotationInvocationSupport.requireBindable(param, method);
        }

        String name = resolveName(annotation.name(), method);
        String description = annotation.description().isEmpty() ? null : annotation.description();
        String title = annotation.title().isEmpty() ? null : annotation.title();
        String mimeType = annotation.mimeType().isEmpty() ? null : annotation.mimeType();

        ResourceTemplateDescriptor descriptor = ResourceTemplateDescriptor.builder()
                .name(name)
                .uriTemplate(annotation.uriTemplate())
                .description(description)
                .title(title)
                .mimeType(mimeType)
                .build();

        ResourceFn fn =
                (ctx, req) -> invokeResourceTemplate(instance, method, ctx, req, serializer, deserializer, mimeType);
        context.resources().registerTemplate(descriptor, fn);
    }

    private ResourceContents invokeResourceTemplate(
            Object instance,
            Method method,
            InteractionContext ctx,
            ResourceRequest req,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer,
            @Nullable String mimeType)
            throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        req.params().forEach((name, value) -> values.put(name, value.scalarValue()));
        Object[] args = resolveArgs(method, ctx, values, ResourceTemplateArg.class, serializer, deserializer);
        Object result = invoke(method, instance, args);
        return convertResourceContents(result, req.uri(), mimeType, serializer);
    }

    private void registerPrompts(
            Object instance,
            Method method,
            AnnotationRegistrationContext context,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer) {
        Prompt annotation = method.getAnnotation(Prompt.class);
        if (annotation == null) return;

        List<PromptArgument> arguments = buildPromptArguments(method);

        String name = resolveName(annotation.name(), method);
        String description = annotation.description().isEmpty() ? null : annotation.description();
        String title = annotation.title().isEmpty() ? null : annotation.title();

        PromptDescriptor descriptor = PromptDescriptor.builder()
                .name(name)
                .description(description)
                .title(title)
                .arguments(arguments)
                .build();

        PromptFn fn = (ctx, req) -> invokePrompt(instance, method, ctx, req, serializer, deserializer);
        context.prompts().register(descriptor, fn);
    }

    private List<PromptArgument> buildPromptArguments(Method method) {
        List<PromptArgument> args = new ArrayList<>();
        for (Parameter param : method.getParameters()) {
            if (InteractionContext.class.isAssignableFrom(param.getType())) continue;
            AnnotationInvocationSupport.requireBindable(param, method);

            PromptArg ann = param.getAnnotation(PromptArg.class);
            String paramName = resolveParamName(param, PromptArg.class);
            String desc = (ann != null && !ann.description().isEmpty()) ? ann.description() : null;
            String paramTitle = (ann != null && !ann.title().isEmpty()) ? ann.title() : null;
            Boolean required = (ann != null) ? ann.required() : true;
            args.add(PromptArgument.builder()
                    .name(paramName)
                    .description(desc)
                    .title(paramTitle)
                    .required(required)
                    .build());
        }
        return args;
    }

    private PromptResult invokePrompt(
            Object instance,
            Method method,
            InteractionContext ctx,
            PromptRequest req,
            PayloadSerializer serializer,
            PayloadDeserializer deserializer)
            throws Exception {
        Object[] args = resolveArgs(method, ctx, req.arguments().asMap(), PromptArg.class, serializer, deserializer);
        Object result = invoke(method, instance, args);
        return convertPromptResult(result, serializer);
    }

    private PromptResult convertPromptResult(Object result, PayloadSerializer serializer) {
        switch (result) {
            case PromptResult pr -> {
                return pr;
            }
            case String s -> {
                return PromptResult.messages(List.of(PromptMessage.user(s)));
            }
            case PromptMessage pm -> {
                return PromptResult.messages(List.of(pm));
            }
            case List<?> list -> {
                List<PromptMessage> messages = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof PromptMessage pm) {
                        messages.add(pm);
                    } else if (item instanceof String s) {
                        messages.add(PromptMessage.user(s));
                    } else {
                        messages.add(PromptMessage.user(serializer.serialize(item)));
                    }
                }
                return PromptResult.messages(messages);
            }
            default -> {}
        }
        return PromptResult.messages(List.of(PromptMessage.user(serializer.serialize(result))));
    }

    private static String resolveName(String annotationValue, Method method) {
        if (ELEMENT_NAME.equals(annotationValue) || annotationValue.isEmpty()) {
            return method.getName();
        }
        return annotationValue;
    }

    private static <A extends Annotation> String resolveParamName(Parameter param, Class<A> annotationType) {
        A ann = param.getAnnotation(annotationType);
        if (ann != null) {
            String name = getAnnotationName(ann);
            if (!ELEMENT_NAME.equals(name) && !name.isEmpty()) {
                return name;
            }
        }
        return param.getName();
    }

    private static <A extends Annotation> String getAnnotationName(A ann) {
        String name = getStringAttribute(ann, "name");
        return name != null ? name : "";
    }

    private JsonSchema buildInputSchema(Method method, Class<? extends Annotation> argAnnotation) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : method.getParameters()) {
            if (InteractionContext.class.isAssignableFrom(param.getType())) continue;
            AnnotationInvocationSupport.requireBindable(param, method);

            String paramName = resolveParamName(param, argAnnotation);
            String jsonType = AnnotationInvocationSupport.jsonSchemaType(param.getParameterizedType());

            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", jsonType);

            Annotation ann = param.getAnnotation(argAnnotation);
            String description = getStringAttribute(ann, "description");
            if (description != null && !description.isEmpty()) {
                prop.put("description", description);
            }

            properties.put(paramName, prop);

            boolean isRequired = true;
            if (ann != null) {
                Boolean requiredVal = getBooleanAttribute(ann, "required");
                if (requiredVal != null) isRequired = requiredVal;
                String defaultVal = getStringAttribute(ann, "defaultValue");
                if (defaultVal != null && !defaultVal.isEmpty()) isRequired = false;
            }
            if (AnnotationInvocationSupport.isOptionalType(param.getParameterizedType())) {
                isRequired = false;
            }
            if (isRequired) required.add(paramName);
        }

        return AnnotationInvocationSupport.inputSchema(properties, required);
    }

    /** Reflectively reads a {@link String}-typed annotation attribute, or {@code null} if absent/not a String. */
    private static @Nullable String getStringAttribute(@Nullable Annotation annotation, String attributeName) {
        Object val = getAnnotationAttribute(annotation, attributeName);
        return val instanceof String s ? s : null;
    }

    /** Reflectively reads a {@code boolean}-typed annotation attribute, or {@code null} if absent/not a boolean. */
    private static @Nullable Boolean getBooleanAttribute(@Nullable Annotation annotation, String attributeName) {
        Object val = getAnnotationAttribute(annotation, attributeName);
        return val instanceof Boolean b ? b : null;
    }

    private static @Nullable Object getAnnotationAttribute(@Nullable Annotation annotation, String attributeName) {
        if (annotation == null) return null;
        try {
            Method m = annotation.annotationType().getMethod(attributeName);
            return m.invoke(annotation);
        } catch (Exception e) {
            return null;
        }
    }
}
