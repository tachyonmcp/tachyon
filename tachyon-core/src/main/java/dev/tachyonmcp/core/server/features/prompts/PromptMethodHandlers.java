/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.prompts;

import dev.tachyonmcp.api.json.JsonDocument;
import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.json.SchemaValidationError;
import dev.tachyonmcp.api.server.domain.ServerError;
import dev.tachyonmcp.api.server.features.prompts.PromptResult;
import dev.tachyonmcp.core.protocol.ProtocolRequestMapper;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.domain.ServerErrors;
import dev.tachyonmcp.core.server.internal.HandlerFutures;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** JSON-RPC adapters for prompt operations. */
public final class PromptMethodHandlers {

    private PromptMethodHandlers() {}

    public static void register(
            Map<String, RpcMethodHandler<?, ?>> handlers,
            DefaultPromptRegistry registry,
            JsonSchemaValidator validator) {
        handlers.put("prompts/list", new PromptsListHandler(registry));
        handlers.put("prompts/get", new PromptsGetHandler(registry, validator));
    }

    private record PromptsListHandler(DefaultPromptRegistry registry)
            implements RpcMethodHandler<ProtocolRequestMapper.PageRequest, Object> {

        @Override
        public String method() {
            return "prompts/list";
        }

        @Override
        public ProtocolRequestMapper.PageRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().page(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ProtocolRequestMapper.PageRequest page) {
            var paginated = registry.list(page.limit(), page.cursor(), descriptor -> {
                var extensionId = descriptor.extensionId();
                return extensionId == null || context.isExtensionEnabled(extensionId);
            });
            if (!paginated.cursorValid()) return ServerErrors.invalidParams("Invalid cursor");
            return context.responseMapper().listPromptsResult(paginated.items(), paginated.nextCursor());
        }
    }

    private record PromptsGetHandler(DefaultPromptRegistry registry, JsonSchemaValidator validator)
            implements RpcMethodHandler<ProtocolRequestMapper.PromptCallRequest, Object> {

        private static final Logger logger = LoggerFactory.getLogger(PromptsGetHandler.class);

        @Override
        public String method() {
            return "prompts/get";
        }

        @Override
        public ProtocolRequestMapper.PromptCallRequest decode(DispatchContext context, @Nullable Object rawParams) {
            return context.requestMapper().getPrompt(rawParams);
        }

        @Override
        public Object handle(DispatchContext context, ProtocolRequestMapper.PromptCallRequest mapped) throws Exception {
            return HandlerFutures.joinInterruptible(handleAsync(context, mapped));
        }

        @Override
        public CompletionStage<Object> handleAsync(
                DispatchContext context, ProtocolRequestMapper.PromptCallRequest mapped) {
            var entry = registry.get(mapped.name());
            if (entry == null) {
                return CompletableFuture.completedFuture(ServerErrors.invalidParams("Prompt not found"));
            }
            context.observation().info().target(entry.descriptor().name());
            var extensionId = entry.descriptor().extensionId();
            if (extensionId != null && !context.isExtensionEnabled(extensionId)) {
                return CompletableFuture.completedFuture(ServerErrors.invalidParams("Prompt not found"));
            }

            var inputSchema = entry.descriptor().inputSchema();
            if (inputSchema != null) {
                var errors = validator.validate(
                        inputSchema,
                        JsonDocument.of(mapped.request().arguments().json()));
                if (!errors.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            ServerErrors.invalidParams(SchemaValidationError.join(errors)));
                }
            }

            return HandlerFutures.invokeAndMap(
                    "Prompt '" + mapped.name() + "' returned a null CompletionStage",
                    () -> entry.fn().apply(context, mapped.request()),
                    context.engine().executor(),
                    (result, cause) -> {
                        if (cause != null) {
                            var error = ServerErrors.fromUnhandledException(cause, "Prompt handler failed");
                            if (error.kind() == ServerError.Kind.INVALID_PARAMS) {
                                logger.debug("Prompt handler rejected invalid params for '{}'", mapped.name());
                            } else {
                                logger.error("Prompt handler error for '{}'", mapped.name(), cause);
                            }
                            context.captureExceptionCause(cause);
                            return error;
                        }
                        var meta = result.meta();
                        return switch (result) {
                            case PromptResult.Messages messages ->
                                context.responseMapper()
                                        .getPromptResult(entry.descriptor().description(), messages.messages(), meta);
                            case PromptResult.InputRequired inputRequired ->
                                context.responseMapper()
                                        .inputRequiredResult(
                                                inputRequired.inputRequests(), inputRequired.requestState(), meta);
                        };
                    });
        }
    }
}
