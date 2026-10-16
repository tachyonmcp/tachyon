/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tools;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.HasMeta;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.tasks.TaskSupport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Descriptor for a server-provided tool.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ToolDescriptor extends ServerFeature.Descriptor, HasMeta {

    /** The tool name, unique within the server. */
    String name();

    /**
     * Optional human-readable title.
     *
     * @return the title
     */
    @Nullable
    String title();

    /**
     * Optional description of this tool.
     *
     * @return the description
     */
    @Nullable
    String description();

    /**
     * Optional JSON schema describing the tool's input arguments.
     *
     * @return the input schema
     */
    @Nullable
    JsonSchema inputSchema();

    /**
     * Optional JSON schema describing the tool's output.
     *
     * @return the output schema
     */
    @Nullable
    JsonSchema outputSchema();

    /**
     * Optional declaration of this tool's support for long-running tasks.
     *
     * @return the task support
     */
    @Nullable
    @ExperimentalApi
    TaskSupport taskSupport();

    /**
     * Optional behavioural annotations (e.g. read-only, destructive) for this tool.
     *
     * @return the annotations
     */
    @Nullable
    ToolAnnotations annotations();

    /**
     * Icons for this tool, or an empty list.
     *
     * @return the icons
     */
    List<Icon> icons();

    /**
     * The OAuth scopes a caller must hold, every one of them, to call this tool; empty when the tool
     * needs none. A call by a caller without all of them is answered {@code 403 Forbidden} before the
     * tool runs (see {@link dev.tachyonmcp.api.server.security.SecurityContext#scopes()}). This is
     * server-side policy: it is neither listed in {@code tools/list} nor sent to clients in any other
     * way. Names are compared exactly, and the declaration order is kept in the challenge. Each must be
     * an RFC 6749 §3.3 scope-token (no space, quote or non-ASCII character), and the server must
     * authenticate requests; registering the tool fails otherwise.
     *
     * @return the required scopes, in declaration order and without repeats
     */
    @ExperimentalApi
    Set<String> requiredScopes();

    /**
     * Optional identifier of the extension that owns this tool.
     *
     * @return the extension id
     */
    @Nullable
    String extensionId();

    /** Optional protocol extension metadata. */
    @Nullable
    @Override
    Map<String, Object> meta();

    /**
     * Validates the tool descriptor's name.
     *
     * @throws IllegalArgumentException if the name is blank
     */
    @Value.Check
    default void check() {
        if (name().isBlank()) throw new IllegalArgumentException("name must not be blank");
    }

    /**
     * Creates a new builder for {@link ToolDescriptor}.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultToolDescriptor.builder();
    }

    /**
     * Creates a tool descriptor with just a name.
     *
     * @param name the name
     * @return the tool descriptor
     */
    static ToolDescriptor of(String name) {
        return DefaultToolDescriptor.of(name, null, null, null, null, null, null, List.of(), Set.of(), null, null);
    }

    /**
     * Creates a tool descriptor with a name and description.
     *
     * @param name the name
     * @param description the description
     * @return the tool descriptor
     */
    static ToolDescriptor of(String name, @Nullable String description) {
        return DefaultToolDescriptor.of(
                name, null, description, null, null, null, null, List.of(), Set.of(), null, null);
    }

    /** Builder for {@link ToolDescriptor}. */
    interface Builder {

        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(ToolDescriptor instance);

        /**
         * Sets the tool name, unique within the server.
         *
         * @param name the name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Sets the optional human-readable title.
         *
         * @param title the title
         * @return this builder
         */
        Builder title(@Nullable String title);

        /**
         * Sets the optional description of this tool.
         *
         * @param description the description
         * @return this builder
         */
        Builder description(@Nullable String description);

        /**
         * Sets the optional JSON schema describing the tool's input arguments.
         *
         * @param inputSchema the input schema
         * @return this builder
         */
        Builder inputSchema(@Nullable JsonSchema inputSchema);

        /**
         * Sets the optional JSON schema describing the tool's output.
         *
         * @param outputSchema the output schema
         * @return this builder
         */
        Builder outputSchema(@Nullable JsonSchema outputSchema);

        /**
         * Sets the optional JSON schema describing the tool's input arguments, parsed from a string.
         *
         * @param inputSchema the input schema
         * @return this builder
         */
        default Builder inputSchema(@Nullable String inputSchema) {
            return inputSchema(inputSchema != null ? JsonSchema.unchecked(inputSchema) : null);
        }

        /**
         * Sets the optional JSON schema describing the tool's output, parsed from a string.
         *
         * @param outputSchema the output schema
         * @return this builder
         */
        default Builder outputSchema(@Nullable String outputSchema) {
            return outputSchema(outputSchema != null ? JsonSchema.unchecked(outputSchema) : null);
        }

        /**
         * Sets the optional declaration of this tool's support for long-running tasks.
         *
         * @param taskSupport the task support
         * @return this builder
         */
        @ExperimentalApi
        Builder taskSupport(@Nullable TaskSupport taskSupport);

        /**
         * Sets the optional behavioural annotations (e.g. read-only, destructive) for this tool.
         *
         * @param annotations the annotations
         * @return this builder
         */
        Builder annotations(@Nullable ToolAnnotations annotations);

        /**
         * Sets the icons for this tool.
         *
         * @param icons the icons
         * @return this builder
         */
        Builder icons(Iterable<? extends Icon> icons);

        /**
         * Sets the icons for this tool; an empty array means no icons.
         *
         * @param icons the icons
         * @return this builder
         */
        default Builder icons(Icon... icons) {
            return icons(List.of(icons));
        }

        /**
         * Sets the scopes a caller must hold, all of them, to call this tool; see {@link
         * ToolDescriptor#requiredScopes()}.
         *
         * @param requiredScopes the required scopes
         * @return this builder
         */
        @ExperimentalApi
        Builder requiredScopes(Iterable<String> requiredScopes);

        /**
         * Sets the scopes a caller must hold, all of them, to call this tool; see {@link
         * ToolDescriptor#requiredScopes()}.
         *
         * @param requiredScopes the required scopes; none means the tool needs none
         * @return this builder
         */
        @ExperimentalApi
        default Builder requiredScopes(String... requiredScopes) {
            return requiredScopes(List.of(requiredScopes));
        }

        /**
         * Sets the optional identifier of the extension that owns this tool.
         *
         * @param extensionId the extension identifier
         * @return this builder
         */
        Builder extensionId(@Nullable String extensionId);

        /**
         * Sets optional protocol extension metadata.
         *
         * @param entries metadata entries, or {@code null} for none
         * @return this builder
         */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Builds the {@link ToolDescriptor}.
         *
         * @return the configured value
         */
        ToolDescriptor build();
    }
}
