/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import dev.tachyonmcp.extensions.apps.protocol.codecs.UIResourceMetaCodec;
import dev.tachyonmcp.extensions.apps.protocol.models.McpUiResourceCsp;
import dev.tachyonmcp.extensions.apps.protocol.models.UIResourceMeta;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The {@code _meta.ui} a UI resource declares (SEP-1865 {@code McpUiResourceMeta}): the Content
 * Security Policy origins the view may reach, the browser permissions it requests, an optional
 * dedicated sandbox origin, and its border preference. Hosts read it from the {@code resources/read}
 * content item first and fall back to the {@code resources/list} entry; the extension emits the same
 * value in both places.
 *
 * <p>Every list is a secure default when empty: no external connections, no external resources,
 * {@code frame-src 'none'}, {@code base-uri 'self'}. A view packaged as one self-contained HTML
 * document needs {@link #NONE}.
 *
 * @param connectDomains  origins for {@code fetch}/XHR/WebSocket ({@code connect-src})
 * @param resourceDomains origins for scripts, styles, images, fonts and media
 * @param frameDomains    origins for nested iframes ({@code frame-src})
 * @param baseUriDomains  allowed document base URIs ({@code base-uri})
 * @param permissions     browser capabilities the view requests through the iframe {@code allow}
 *                        attribute
 * @param domain          host-specific dedicated sandbox origin, or {@code null} for the host default
 * @param prefersBorder   {@code true} to request a visible border and background, {@code false} to
 *                        request none, {@code null} to let the host decide
 */
public record AppMeta(
        List<String> connectDomains,
        List<String> resourceDomains,
        List<String> frameDomains,
        List<String> baseUriDomains,
        Set<Permission> permissions,
        @Nullable String domain,
        @Nullable Boolean prefersBorder) {

    /** No external origins, no permissions, host-default origin and border. */
    public static final AppMeta NONE = builder().build();

    /** Validates and defensively copies the collections. */
    public AppMeta {
        connectDomains = List.copyOf(connectDomains);
        resourceDomains = List.copyOf(resourceDomains);
        frameDomains = List.copyOf(frameDomains);
        baseUriDomains = List.copyOf(baseUriDomains);
        permissions = Set.copyOf(permissions);
    }

    /**
     * Creates a builder with every field at its secure default.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Encodes this metadata as the {@code _meta} map of a resource descriptor or content item:
     * {@code {"ui": {"csp": {...}, "permissions": {...}, "domain": ..., "prefersBorder": ...}}}.
     * Empty lists, empty permissions and {@code null} fields are omitted, so {@link #NONE} encodes
     * to an empty map and the resource carries no {@code _meta} at all.
     *
     * @return the {@code _meta} entries, empty when nothing is declared
     */
    public Map<String, Object> toMeta() {
        var csp = connectDomains.isEmpty()
                        && resourceDomains.isEmpty()
                        && frameDomains.isEmpty()
                        && baseUriDomains.isEmpty()
                ? null
                : new McpUiResourceCsp(
                        nullIfEmpty(connectDomains),
                        nullIfEmpty(resourceDomains),
                        nullIfEmpty(frameDomains),
                        nullIfEmpty(baseUriDomains));
        if (csp == null && permissions.isEmpty() && domain == null && prefersBorder == null) {
            return Map.of();
        }
        var ui = new UIResourceMeta(csp, permissionsModel(), domain, prefersBorder);
        return Map.of("ui", CodecSupport.encodeToTree(new UIResourceMetaCodec(), ui));
    }

    private UIResourceMeta.@Nullable Permissions permissionsModel() {
        if (permissions.isEmpty()) {
            return null;
        }
        return new UIResourceMeta.Permissions(
                granted(Permission.CAMERA),
                granted(Permission.MICROPHONE),
                granted(Permission.GEOLOCATION),
                granted(Permission.CLIPBOARD_WRITE));
    }

    private @Nullable ObjectNode granted(Permission permission) {
        return permissions.contains(permission) ? JsonNodeFactory.instance.objectNode() : null;
    }

    private static @Nullable List<String> nullIfEmpty(List<String> values) {
        return values.isEmpty() ? null : values;
    }

    /** Browser capability a view may request; maps to a Permission Policy feature. */
    public enum Permission {
        /** Camera access. */
        CAMERA,
        /** Microphone access. */
        MICROPHONE,
        /** Geolocation access. */
        GEOLOCATION,
        /** Clipboard write access. */
        CLIPBOARD_WRITE
    }

    /** Builds an {@link AppMeta}. Every list starts empty, every flag unset. */
    public static final class Builder {
        private final List<String> connectDomains = new ArrayList<>();
        private final List<String> resourceDomains = new ArrayList<>();
        private final List<String> frameDomains = new ArrayList<>();
        private final List<String> baseUriDomains = new ArrayList<>();
        private final Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        private @Nullable String domain;
        private @Nullable Boolean prefersBorder;

        private Builder() {}

        /**
         * Adds origins the view may open network connections to.
         *
         * @param origins origins such as {@code https://api.example.com} or {@code wss://live.example.com}
         * @return this builder
         */
        public Builder connectDomains(String... origins) {
            connectDomains.addAll(List.of(origins));
            return this;
        }

        /**
         * Adds origins the view may load scripts, styles, images, fonts and media from. Wildcard
         * subdomains such as {@code https://*.example.com} are allowed.
         *
         * @param origins the origins
         * @return this builder
         */
        public Builder resourceDomains(String... origins) {
            resourceDomains.addAll(List.of(origins));
            return this;
        }

        /**
         * Adds origins the view may embed in nested iframes.
         *
         * @param origins the origins
         * @return this builder
         */
        public Builder frameDomains(String... origins) {
            frameDomains.addAll(List.of(origins));
            return this;
        }

        /**
         * Adds allowed document base URIs.
         *
         * @param origins the origins
         * @return this builder
         */
        public Builder baseUriDomains(String... origins) {
            baseUriDomains.addAll(List.of(origins));
            return this;
        }

        /**
         * Requests browser permissions. Hosts may refuse; views should feature-detect.
         *
         * @param requested the permissions
         * @return this builder
         */
        public Builder permissions(Permission... requested) {
            permissions.addAll(List.of(requested));
            return this;
        }

        /**
         * Sets a dedicated sandbox origin. Its format is host-specific; consult the host's
         * documentation.
         *
         * @param domain the origin, e.g. {@code a904794854a047f6.claudemcpcontent.com}
         * @return this builder
         */
        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        /**
         * Sets the border preference. Hosts' defaults vary, so declaring it is recommended.
         *
         * @param prefersBorder {@code true} for a visible border and background
         * @return this builder
         */
        public Builder prefersBorder(boolean prefersBorder) {
            this.prefersBorder = prefersBorder;
            return this;
        }

        /**
         * Builds the metadata.
         *
         * @return the metadata
         */
        public AppMeta build() {
            return new AppMeta(
                    connectDomains, resourceDomains, frameDomains, baseUriDomains, permissions, domain, prefersBorder);
        }
    }
}
