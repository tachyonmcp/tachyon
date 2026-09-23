/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.tools;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.spi.JsonSchemaFactory;
import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.api.server.domain.InvalidArgumentException;
import dev.tachyonmcp.api.server.features.tools.AbstractToolHandler;
import dev.tachyonmcp.api.server.features.tools.AsyncToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.features.tools.Tools;
import dev.tachyonmcp.core.server.config.FeatureConfig;
import dev.tachyonmcp.core.server.features.AbstractRegistry;
import dev.tachyonmcp.core.server.internal.HandlerFutures;
import dev.tachyonmcp.core.server.json.JsonSchemaUtils;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AbstractRegistry for tool handlers with input/output schema validation.
 */
@InternalApi
public class DefaultToolRegistry extends AbstractRegistry<ToolDescriptor, ToolHandler> implements ToolRegistry {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolRegistry.class);

    private final JsonSchemaFactory<?> schemaFactory;
    private final FeatureConfig config;

    /**
     * Maximum description length before a warning is logged. MCP clients may truncate
     * descriptions beyond this length.
     */
    public static final int MAX_DESCRIPTION_LENGTH = 2048;

    /**
     * Creates a tool registry.
     *
     * @param schemaFactory validates registered tool schemas; must accept {@link String} sources
     * @param config        feature configuration, e.g. page size and mode
     */
    public DefaultToolRegistry(JsonSchemaFactory<?> schemaFactory, FeatureConfig config) {
        super(config.pageSize());
        this.schemaFactory = schemaFactory;
        this.config = config;
    }

    @Override
    public Tools register(ToolDescriptor descriptor, ToolFn fn) {
        return register(new AbstractToolHandler(descriptor) {
            @Override
            public ToolResult handle(InteractionContext context, ToolRequest request) throws Exception {
                HandlerFutures.assumeVirtualThread();
                return fn.apply(context, request);
            }
        });
    }

    @Override
    public Tools registerAsync(ToolDescriptor descriptor, AsyncToolFn fn) {
        return register(new AbstractToolHandler(descriptor) {
            @Override
            public CompletionStage<? extends ToolResult> handleAsync(InteractionContext context, ToolRequest request) {
                return fn.apply(context, request);
            }
        });
    }

    Tools register(ToolHandler handler) {
        var descriptor = handler.descriptor();
        if (config.mode() == Mode.OFF) {
            logger.debug("Tool '{}' not registered: tools capability is OFF", descriptor.name());
            return this;
        }
        var name = descriptor.name();
        validateName(name);
        JsonSchemaUtils.validateInputSchemaRoot(schemaFactory, name, descriptor.inputSchema());
        JsonSchemaUtils.validateHeaderAnnotations(schemaFactory, name, descriptor.inputSchema());
        JsonSchemaUtils.validateOutputSchemaRoot(schemaFactory, name, descriptor.outputSchema());
        var desc = descriptor.description();
        if (desc != null && desc.length() > MAX_DESCRIPTION_LENGTH) {
            logger.warn(
                    "Tool '{}' description exceeds {} characters ({}), may be truncated by clients",
                    name,
                    MAX_DESCRIPTION_LENGTH,
                    desc.length());
        }
        addItem(handler);
        logger.debug("Tool registered: {}", name);
        return this;
    }

    /**
     * Removes the registered tool with the specified name.
     *
     * @param name the name of the tool to remove
     * @return {@code true} if a tool was removed, {@code false} otherwise
     */
    @Override
    public boolean unregister(String name) {
        return removeItem(name);
    }

    /**
     * Finds the descriptor for a registered tool by name.
     *
     * @param name the tool name to find
     * @return the tool descriptor if registered, or an empty optional otherwise
     */
    @Override
    public Optional<ToolDescriptor> find(String name) {
        var handler = get(name);
        return handler != null ? Optional.of(handler.descriptor()) : Optional.empty();
    }

    /**
     * Retrieves all registered tool descriptors in name order.
     *
     * @return the registered tool descriptors sorted by name
     */
    @Override
    public List<ToolDescriptor> descriptors() {
        return getAll().stream()
                .map(ToolHandler::descriptor)
                .sorted(Comparator.comparing(ToolDescriptor::name))
                .toList();
    }

    static void validateName(String name) {
        Objects.requireNonNull(name, "Tool name must not be null");
        if (name.isBlank()) {
            throw new InvalidArgumentException("name", "Tool name must not be blank");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new InvalidArgumentException(
                    "name", "Tool name must not exceed " + MAX_NAME_LENGTH + " characters (SEP-986)");
        }
        if (!VALID_NAME_PATTERN.matcher(name).matches()) {
            throw new InvalidArgumentException("name", "Tool name must match [a-zA-Z0-9_\\-./]+ per SEP-986: " + name);
        }
    }

    static final int MAX_NAME_LENGTH = 64;

    private static final Pattern VALID_NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_\\-./]+");
}
