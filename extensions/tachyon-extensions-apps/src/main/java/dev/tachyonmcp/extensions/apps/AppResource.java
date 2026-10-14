/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

import java.util.Objects;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * A UI resource (SEP-1865): one self-contained HTML5 document served at a {@code ui://} URI with
 * MIME type {@code text/html;profile=mcp-app}. Hosts fetch it with {@code resources/read} and render
 * it in a sandboxed iframe under the Content Security Policy declared in {@link #meta()}, so the
 * document must inline its scripts and styles unless {@link AppMeta#resourceDomains()} allows their
 * origin.
 *
 * <p>A tool renders its result in the view by carrying
 * {@link AppsExtension#toolMeta(String, UiToolVisibility[])} in its {@code _meta}.
 *
 * @param uri         the resource URI; must start with {@code ui://}
 * @param name        the resource name shown in host listings
 * @param title       the optional human-readable title
 * @param description the optional description of what the view does
 * @param html        supplies the HTML document on every {@code resources/read}
 * @param meta        CSP, permissions and rendering preferences
 */
public record AppResource(
        String uri,
        String name,
        @Nullable String title,
        @Nullable String description,
        Supplier<String> html,
        AppMeta meta) {

    /** Validates the URI scheme and name. */
    public AppResource {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(html, "html");
        Objects.requireNonNull(meta, "meta");
        if (!uri.startsWith(AppsExtension.URI_SCHEME)) {
            throw new IllegalArgumentException(
                    "A UI resource must use the " + AppsExtension.URI_SCHEME + " scheme: " + uri);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("A UI resource needs a name: " + uri);
        }
    }

    /**
     * Creates a builder.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds an {@link AppResource}. {@code uri}, {@code name} and {@code html} are required. */
    public static final class Builder {
        private @Nullable String uri;
        private @Nullable String name;
        private @Nullable String title;
        private @Nullable String description;
        private @Nullable Supplier<String> html;
        private AppMeta meta = AppMeta.NONE;

        private Builder() {}

        /**
         * Sets the {@code ui://} URI.
         *
         * @param uri the URI
         * @return this builder
         */
        public Builder uri(String uri) {
            this.uri = uri;
            return this;
        }

        /**
         * Sets the resource name.
         *
         * @param name the name
         * @return this builder
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * Sets the human-readable title.
         *
         * @param title the title
         * @return this builder
         */
        public Builder title(@Nullable String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the description.
         *
         * @param description the description
         * @return this builder
         */
        public Builder description(@Nullable String description) {
            this.description = description;
            return this;
        }

        /**
         * Sets a fixed HTML document.
         *
         * @param html the HTML5 document
         * @return this builder
         */
        public Builder html(String html) {
            Objects.requireNonNull(html, "html");
            this.html = () -> html;
            return this;
        }

        /**
         * Sets a supplier invoked on every read, e.g. to load the document from the classpath or
         * rebuild it from templates.
         *
         * @param html the document supplier
         * @return this builder
         */
        public Builder html(Supplier<String> html) {
            this.html = Objects.requireNonNull(html, "html");
            return this;
        }

        /**
         * Sets the {@code _meta.ui} declared on the listing entry and the read content.
         *
         * @param meta the metadata; defaults to {@link AppMeta#NONE}
         * @return this builder
         */
        public Builder meta(AppMeta meta) {
            this.meta = Objects.requireNonNull(meta, "meta");
            return this;
        }

        /**
         * Builds the resource.
         *
         * @return the resource
         * @throws IllegalArgumentException if the URI is not {@code ui://} or the name is blank
         * @throws NullPointerException     if {@code uri}, {@code name} or {@code html} is missing
         */
        public AppResource build() {
            return new AppResource(
                    Objects.requireNonNull(uri, "uri"),
                    Objects.requireNonNull(name, "name"),
                    title,
                    description,
                    Objects.requireNonNull(html, "html"),
                    meta);
        }
    }
}
