/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ProvidedBy;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * MCP Apps extension (<a href="https://modelcontextprotocol.io/extensions/apps/overview">SEP-1865</a>):
 * serves interactive HTML views as {@code ui://} resources with MIME type
 * {@code text/html;profile=mcp-app} and advertises {@code io.modelcontextprotocol/ui}. A tool opts
 * into a view by carrying {@link #toolMeta(String, UiToolVisibility...)} in its {@code _meta}; a host that
 * supports MCP Apps renders the tool result in that view, any other host shows the tool's
 * {@code content} as usual. Tools MUST therefore still return meaningful {@code content}.
 *
 * <p>The extension adds no JSON-RPC methods; views are plain resources, readable by every client.
 * The referenced resource MUST exist on the server: register it here, or the host's
 * {@code resources/read} fails.
 *
 * <pre>{@code
 * TachyonServer.builder()
 *         .withExtension(AppsExtension.class, apps -> apps
 *                 .app(AppResource.builder()
 *                         .uri("ui://weather/dashboard")
 *                         .name("weather_dashboard")
 *                         .html(() -> readClasspath("apps/dashboard.html"))
 *                         .meta(AppMeta.builder().prefersBorder(true).build())
 *                         .build()))
 *         .build();
 * server.tools().register(
 *         tool -> tool.name("get_weather").meta(AppsExtension.toolMeta("ui://weather/dashboard")),
 *         (context, request) -> ...);
 * }</pre>
 */
@ProvidedBy(AppsExtensionProvider.class)
public final class AppsExtension implements ConfigurableExtension<AppsExtension.Builder> {

    /** Extension identifier per SEP-1865. */
    public static final String ID = "io.modelcontextprotocol/ui";

    /** The only MIME type SEP-1865 allows for a UI resource. */
    public static final String MIME_TYPE = "text/html;profile=mcp-app";

    /** URI scheme reserved for UI resources. */
    public static final String URI_SCHEME = "ui://";

    private final List<AppResource> apps;

    private AppsExtension(List<AppResource> apps) {
        this.apps = List.copyOf(apps);
    }

    /**
     * Builds the {@code _meta} a tool declares to render its result in a view:
     * {@code {"ui": {"resourceUri": uri, "visibility": [...]}}}. With no {@code visibility} the key is
     * omitted and hosts apply the spec default, {@code ["model", "app"]}. {@link UiToolVisibility#APP} alone
     * hides the tool from the model and lets only the view call it.
     *
     * @param resourceUri the {@code ui://} URI of a registered {@link AppResource}
     * @param visibility  who may call the tool; empty for the spec default
     * @return the unmodifiable {@code _meta} entries for {@code ToolDescriptor.Builder#meta}
     * @throws IllegalArgumentException if {@code resourceUri} is not a {@code ui://} URI
     */
    public static Map<String, Object> toolMeta(String resourceUri, UiToolVisibility... visibility) {
        if (!resourceUri.startsWith(URI_SCHEME)) {
            throw new IllegalArgumentException("A UI resource must use the " + URI_SCHEME + " scheme: " + resourceUri);
        }
        var ui = new LinkedHashMap<String, Object>();
        ui.put("resourceUri", resourceUri);
        if (visibility.length > 0) {
            ui.put(
                    "visibility",
                    new LinkedHashSet<>(List.of(visibility))
                            .stream().map(UiToolVisibility::getValue).toList());
        }
        return Map.of("ui", Collections.unmodifiableMap(ui));
    }

    @Override
    public String extensionId() {
        return ID;
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    /**
     * Returns the registered views.
     *
     * @return the UI resources, in registration order
     */
    public List<AppResource> apps() {
        return apps;
    }

    /** Registers every view as a base MCP resource with {@code _meta.ui} on listing and content. */
    @Override
    public void bootstrap(ExtensionContext server) {
        for (var app : apps) {
            var meta = app.meta().toMeta();
            var descriptor = ResourceDescriptor.builder()
                    .name(app.name())
                    .uri(app.uri())
                    .title(app.title())
                    .description(app.description())
                    .mimeType(MIME_TYPE)
                    .meta(meta.isEmpty() ? null : meta)
                    .build();
            server.resources()
                    .register(
                            descriptor,
                            (context, request) -> TextResourceContents.of(
                                    app.uri(), app.html().get(), MIME_TYPE, meta.isEmpty() ? null : meta));
        }
    }

    /** Configures an {@link AppsExtension}. */
    public static final class Builder implements ExtensionBuilder<AppsExtension> {

        private final List<AppResource> apps = new ArrayList<>();

        /** Created by {@link AppsExtensionProvider} for {@code withExtension(AppsExtension.class, ...)}. */
        Builder() {}

        /**
         * Adds a view.
         *
         * @param app the UI resource
         * @return this builder
         * @throws IllegalArgumentException if a view with the same URI was already added
         */
        public Builder app(AppResource app) {
            for (var existing : apps) {
                if (existing.uri().equals(app.uri())) {
                    throw new IllegalArgumentException("Duplicate UI resource: " + app.uri());
                }
            }
            apps.add(app);
            return this;
        }

        /**
         * Adds a view.
         *
         * @param appResourceConfigurer UI resource configurer
         * @return this builder
         * @throws IllegalArgumentException if a view with the same URI was already added
         */
        public Builder app(Consumer<AppResource.Builder> appResourceConfigurer) {
            final var builder = AppResource.builder();
            appResourceConfigurer.accept(builder);
            return app(builder.build());
        }

        /**
         * Builds the extension.
         *
         * @return the configured extension
         */
        @Override
        public AppsExtension build() {
            return new AppsExtension(apps);
        }
    }
}
