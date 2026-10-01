/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.config;

import dev.tachyonmcp.api.server.domain.Icon;
import java.util.List;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Server identity metadata sent to the client during initialization.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ServerIdentity {

    /**
     * Returns the name.
     *
     * @return the name
     */
    @Value.Default
    default String name() {
        return "tachyon-mcp";
    }

    /**
     * Returns the version.
     *
     * @return the version
     */
    @Value.Default
    default String version() {
        return "0.1";
    }

    /**
     * Returns the description.
     *
     * @return the description
     */
    @Nullable
    String description();

    /**
     * Returns the title.
     *
     * @return the title
     */
    @Nullable
    String title();

    /**
     * Returns the website url.
     *
     * @return the website url
     */
    @Nullable
    String websiteUrl();

    /**
     * Returns the instructions.
     *
     * @return the instructions
     */
    @Nullable
    String instructions();

    /**
     * Returns the icons.
     *
     * @return the icons
     */
    List<Icon> icons();

    /**
     * Default.
     */
    ServerIdentity DEFAULT = DefaultServerIdentity.builder().build();

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultServerIdentity.builder();
    }

    /** Builder for {@link ServerIdentity}. */
    interface Builder {

        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(ServerIdentity instance);

        /**
         * Sets the name.
         *
         * @param name the name
         * @return this builder
         */
        Builder name(String name);

        /**
         * Sets the version.
         *
         * @param version the version
         * @return this builder
         */
        Builder version(String version);

        /**
         * Sets the description.
         *
         * @param description the description
         * @return this builder
         */
        Builder description(@Nullable String description);

        /**
         * Sets the title.
         *
         * @param title the title
         * @return this builder
         */
        Builder title(@Nullable String title);

        /**
         * Sets the website url.
         *
         * @param websiteUrl the website url
         * @return this builder
         */
        Builder websiteUrl(@Nullable String websiteUrl);

        /**
         * Sets the instructions.
         *
         * @param instructions the instructions
         * @return this builder
         */
        Builder instructions(@Nullable String instructions);

        /**
         * Sets the icons.
         *
         * @param icons the icons
         * @return this builder
         */
        Builder icons(Iterable<? extends Icon> icons);

        /**
         * Sets the icons.
         *
         * @param icons the icons
         * @return this builder
         */
        default Builder icons(Icon... icons) {
            return icons(List.of(icons));
        }

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        ServerIdentity build();
    }
}
