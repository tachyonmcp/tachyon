/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import dev.tachyonmcp.api.server.config.Mode;

/**
 * Configuration of which MCP capabilities to enable and their behaviour.
 *
 * @param tools       tools capability configuration
 * @param resources   resources capability configuration
 * @param prompts     prompts capability configuration
 * @param completions completions enablement mode (default {@link Mode#AUTO})
 * @param logging     whether the logging capability is enabled (default {@code false})
 */
public record CapabilitiesConfig(
        FeatureConfig tools, ResourcesConfig resources, FeatureConfig prompts, Mode completions, boolean logging) {

    /**
     * Default configuration with all capabilities auto-detected and change notifications off.
     */
    public static final CapabilitiesConfig DEFAULT = new CapabilitiesConfig(
            FeatureConfig.DEFAULT, ResourcesConfig.DEFAULT, FeatureConfig.DEFAULT, Mode.AUTO, false);

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for {@link CapabilitiesConfig}.
     */
    public static final class Builder {

        private FeatureConfig.Builder toolsBuilder = FeatureConfig.builder();
        private ResourcesConfig.Builder resourcesBuilder = ResourcesConfig.builder();
        private FeatureConfig.Builder promptsBuilder = FeatureConfig.builder();
        private Mode completions = Mode.AUTO;
        private boolean logging;

        private Builder() {}

        // === Nested config setters ===

        /**
         * Configures tools from a feature config.
         *
         * @param config the feature config
         * @return this builder
         */
        public Builder tools(FeatureConfig config) {
            toolsBuilder = FeatureConfig.builder()
                    .mode(config.mode())
                    .listChanged(config.listChanged())
                    .pageSize(config.pageSize());
            return this;
        }

        /**
         * Configures resources from a resources config.
         *
         * @param config the resources config
         * @return this builder
         */
        public Builder resources(ResourcesConfig config) {
            resourcesBuilder = ResourcesConfig.builder()
                    .mode(config.mode())
                    .listChanged(config.listChanged())
                    .pageSize(config.pageSize())
                    .subscribe(config.subscribe());
            return this;
        }

        /**
         * Configures prompts from a feature config.
         *
         * @param config the feature config
         * @return this builder
         */
        public Builder prompts(FeatureConfig config) {
            promptsBuilder = FeatureConfig.builder()
                    .mode(config.mode())
                    .listChanged(config.listChanged())
                    .pageSize(config.pageSize());
            return this;
        }

        // === Flat field setters ===

        /**
         * Sets the tools mode.
         *
         * @param toolsMode the tools mode
         * @return this builder
         */
        public Builder toolsMode(Mode toolsMode) {
            toolsBuilder.mode(toolsMode);
            return this;
        }

        /**
         * Sets whether tools list change notifications are enabled.
         *
         * @param toolsListChanged whether tools list change notifications are enabled
         * @return this builder
         */
        public Builder toolsListChanged(boolean toolsListChanged) {
            toolsBuilder.listChanged(toolsListChanged);
            return this;
        }

        /**
         * Sets the tools page size.
         *
         * @param toolsPageSize the tools page size
         * @return this builder
         */
        public Builder toolsPageSize(int toolsPageSize) {
            toolsBuilder.pageSize(toolsPageSize);
            return this;
        }

        /**
         * Sets the resources mode.
         *
         * @param resourcesMode the resources mode
         * @return this builder
         */
        public Builder resourcesMode(Mode resourcesMode) {
            resourcesBuilder.mode(resourcesMode);
            return this;
        }

        /**
         * Sets whether resource subscriptions are enabled.
         *
         * @param resourcesSubscribe whether resource subscriptions are enabled
         * @return this builder
         */
        public Builder resourcesSubscribe(boolean resourcesSubscribe) {
            resourcesBuilder.subscribe(resourcesSubscribe);
            return this;
        }

        /**
         * Sets whether resources list change notifications are enabled.
         *
         * @param resourcesListChanged whether resources list change notifications are enabled
         * @return this builder
         */
        public Builder resourcesListChanged(boolean resourcesListChanged) {
            resourcesBuilder.listChanged(resourcesListChanged);
            return this;
        }

        /**
         * Sets the resources page size.
         *
         * @param resourcesPageSize the resources page size
         * @return this builder
         */
        public Builder resourcesPageSize(int resourcesPageSize) {
            resourcesBuilder.pageSize(resourcesPageSize);
            return this;
        }

        /**
         * Sets the prompts mode.
         *
         * @param promptsMode the prompts mode
         * @return this builder
         */
        public Builder promptsMode(Mode promptsMode) {
            promptsBuilder.mode(promptsMode);
            return this;
        }

        /**
         * Sets whether prompts list change notifications are enabled.
         *
         * @param promptsListChanged whether prompts list change notifications are enabled
         * @return this builder
         */
        public Builder promptsListChanged(boolean promptsListChanged) {
            promptsBuilder.listChanged(promptsListChanged);
            return this;
        }

        /**
         * Sets the prompts page size.
         *
         * @param promptsPageSize the prompts page size
         * @return this builder
         */
        public Builder promptsPageSize(int promptsPageSize) {
            promptsBuilder.pageSize(promptsPageSize);
            return this;
        }

        /**
         * Sets the completions mode.
         *
         * @param completions the completions mode
         * @return this builder
         */
        public Builder completions(Mode completions) {
            this.completions = completions;
            return this;
        }

        /**
         * Sets whether logging is enabled.
         *
         * @param logging whether logging is enabled
         * @return this builder
         */
        public Builder logging(boolean logging) {
            this.logging = logging;
            return this;
        }

        /**
         * Builds the {@link CapabilitiesConfig}.
         *
         * @return the built {@link CapabilitiesConfig}
         */
        public CapabilitiesConfig build() {
            return new CapabilitiesConfig(
                    toolsBuilder.build(), resourcesBuilder.build(), promptsBuilder.build(), completions, logging);
        }

        // === Convenience defaults ===

        /**
         * Enables completions ({@link Mode#ON}).
         *
         * @return this builder
         */
        public Builder completions() {
            return completions(Mode.ON);
        }

        /**
         * Disables completions ({@link Mode#OFF}).
         *
         * @return this builder
         */
        public Builder noCompletions() {
            return completions(Mode.OFF);
        }

        /**
         * Enables logging.
         *
         * @return this builder
         */
        public Builder logging() {
            return logging(true);
        }

        /**
         * Enables tools with default settings.
         *
         * @return this builder
         */
        public Builder tools() {
            return toolsMode(Mode.ON).toolsListChanged(false);
        }

        /**
         * Enables tools with the specified list-changed setting.
         *
         * @param listChanged whether list change notifications are enabled
         * @return this builder
         */
        public Builder tools(boolean listChanged) {
            return toolsMode(Mode.ON).toolsListChanged(listChanged);
        }

        /**
         * Disables tools ({@link Mode#OFF}).
         *
         * @return this builder
         */
        public Builder noTools() {
            return toolsMode(Mode.OFF);
        }

        /**
         * Enables resources with default settings.
         *
         * @return this builder
         */
        public Builder resources() {
            return resourcesMode(Mode.ON).resourcesSubscribe(false).resourcesListChanged(false);
        }

        /**
         * Enables resources with the specified subscribe and list-changed settings.
         *
         * @param subscribe  whether resource subscriptions are enabled
         * @param listChanged whether resources list change notifications are enabled
         * @return this builder
         */
        public Builder resources(boolean subscribe, boolean listChanged) {
            return resourcesMode(Mode.ON).resourcesSubscribe(subscribe).resourcesListChanged(listChanged);
        }

        /**
         * Disables resources ({@link Mode#OFF}).
         *
         * @return this builder
         */
        public Builder noResources() {
            return resourcesMode(Mode.OFF);
        }

        /**
         * Enables prompts with default settings.
         *
         * @return this builder
         */
        public Builder prompts() {
            return promptsMode(Mode.ON).promptsListChanged(false);
        }

        /**
         * Enables prompts with the specified list-changed setting.
         *
         * @param listChanged whether prompts list change notifications are enabled
         * @return this builder
         */
        public Builder prompts(boolean listChanged) {
            return promptsMode(Mode.ON).promptsListChanged(listChanged);
        }

        /**
         * Disables prompts ({@link Mode#OFF}).
         *
         * @return this builder
         */
        public Builder noPrompts() {
            return promptsMode(Mode.OFF);
        }
    }
}
