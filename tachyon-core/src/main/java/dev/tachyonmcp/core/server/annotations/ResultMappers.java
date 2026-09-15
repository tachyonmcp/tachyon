/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.annotations;

import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.ResourceContents;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.features.completions.CompletionResult;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Maps annotated-method return values onto MCP results.
 */
final class ResultMappers {

    private ResultMappers() {}

    static void requireCompletionReturnType(Method method) {
        if (CompletionResult.class.isAssignableFrom(method.getReturnType())) return;
        if (List.class.isAssignableFrom(method.getReturnType())
                && method.getGenericReturnType() instanceof ParameterizedType type
                && type.getActualTypeArguments()[0] == String.class) return;
        throw new IllegalStateException("@McpCompletion must return CompletionResult or List<String>: " + method);
    }

    static CompletionResult completionResult(@Nullable Object result) {
        if (result instanceof CompletionResult completion) return completion;
        if (result instanceof List<?> items) {
            final var values = new ArrayList<String>(items.size());
            for (final var item : items) {
                if (!(item instanceof String value)) {
                    throw new IllegalStateException("@McpCompletion candidates must be non-null strings");
                }
                values.add(value);
            }
            return CompletionResult.of(values);
        }
        throw new IllegalStateException("@McpCompletion must return a non-null CompletionResult or List<String>");
    }

    static ToolResult toolResult(@Nullable Object result, PayloadSerializer serializer) {
        return switch (result) {
            case null -> ToolResult.empty();
            case ToolResult toolResult -> toolResult;
            case ContentBlock block -> ToolResult.content(block);
            case Enum<?> constant -> ToolResult.text(constant.name());
            case Object scalar when isScalar(scalar) -> ToolResult.text(String.valueOf(scalar));
            case Object sequence
            when sequence instanceof Iterable<?> || sequence.getClass().isArray() ->
                ToolResult.text(serializer.serialize(sequence));
            default -> ToolResult.structured(result);
        };
    }

    static ResourceContents resourceContents(
            @Nullable Object result, String uri, @Nullable String mimeType, PayloadSerializer serializer) {
        return switch (result) {
            case null -> throw new IllegalStateException("@McpResource method for '" + uri + "' returned null");
            case ResourceContents contents -> contents;
            case String text -> TextResourceContents.of(uri, text, mimeType);
            case byte[] blob -> BlobResourceContents.of(uri, blob, mimeType);
            default -> TextResourceContents.of(uri, serializer.serialize(result), mimeType);
        };
    }

    static PromptResult promptResult(Role role, @Nullable Object result, PayloadSerializer serializer) {
        return switch (result) {
            case null -> throw new IllegalStateException("@McpPrompt method returned null");
            case PromptResult promptResult -> promptResult;
            case List<?> items -> {
                var messages = new ArrayList<PromptMessage>(items.size());
                items.forEach(item -> messages.add(message(role, item, serializer)));
                yield PromptResult.messages(messages);
            }
            default -> PromptResult.messages(List.of(message(role, result, serializer)));
        };
    }

    private static PromptMessage message(Role role, @Nullable Object item, PayloadSerializer serializer) {
        if (item == null) {
            throw new IllegalStateException("@McpPrompt message must not be null");
        }
        final var builder = PromptMessage.builder().role(role);
        return switch (item) {
            case PromptMessage message -> message;
            case String text -> builder.content(TextContent.of(text)).build();
            case ContentBlock block -> builder.content(block).build();
            default ->
                builder.content(TextContent.of(serializer.serialize(item))).build();
        };
    }

    private static boolean isScalar(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character;
    }
}
