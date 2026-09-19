/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.List;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * An icon entry for a tool or resource, pointing to an image resource.
 *
 * <p>All fields except {@code src} are optional. Sizes use the conventional format
 * (e.g. {@code "16x16"}, {@code "32x32"}), and {@code theme} distinguishes light
 * vs. dark variants.
 */
@Value.Immutable
@Value.Style(allParameters = true, typeImmutable = "Default*", visibilityString = "PACKAGE")
public interface Icon {

    /**
     * Image URL or data URI for this icon.
     *
     * @return the icon source, never blank
     */
    @Value.Redacted
    String src();

    /**
     * MIME type of the icon image (e.g. {@code "image/png"}).
     *
     * @return the MIME type, or {@code null} when unknown
     */
    @Nullable
    String mimeType();

    /**
     * Conventional size labels (e.g. {@code ["16x16", "32x32"]}).
     *
     * @return the size labels, or an empty list when unspecified
     */
    List<String> sizes();

    /**
     * Theme variant this icon is designed for.
     *
     * @return {@code "light"}, {@code "dark"}, or {@code null} when universal
     */
    @Nullable
    String theme();

    /**
     * Validates that the icon source is not blank.
     *
     * @throws IllegalArgumentException if {@code src} is blank
     */
    @Value.Check
    default void check() {
        if (src().isBlank()) throw new IllegalArgumentException("src must not be blank");
    }

    /**
     * Creates a new builder for {@link Icon}.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultIcon.builder();
    }

    /**
     * Creates an icon with the given values.
     *
     * @param src      image URL or data URI
     * @param mimeType image MIME type, or {@code null}
     * @param sizes    conventional size labels, or an empty list
     * @param theme    theme variant, or {@code null}
     * @return the new icon
     */
    static Icon of(String src, @Nullable String mimeType, List<String> sizes, @Nullable String theme) {
        return DefaultIcon.of(src, mimeType, sizes, theme);
    }

    /** Builder for {@link Icon}. */
    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(Icon instance);

        /**
         * Sets the image URL or data URI.
         *
         * @param src the icon source
         * @return this builder
         */
        Builder src(String src);

        /**
         * Sets the MIME type of the icon image.
         *
         * @param mimeType the MIME type, or {@code null}
         * @return this builder
         */
        Builder mimeType(@Nullable String mimeType);

        /**
         * Sets the conventional size labels.
         *
         * @param elements size labels (e.g. "16x16")
         * @return this builder
         */
        Builder sizes(Iterable<String> elements);

        /**
         * Sets the conventional size labels.
         *
         * @param elements size labels (e.g. "16x16", "32x32")
         * @return this builder
         */
        default Builder sizes(String... elements) {
            return sizes(List.of(elements));
        }

        /**
         * Sets the theme variant.
         *
         * @param theme {@code "light"}, {@code "dark"}, or {@code null} for universal
         * @return this builder
         */
        Builder theme(@Nullable String theme);

        /**
         * Builds the {@link Icon}.
         *
         * @return the new icon
         */
        Icon build();
    }
}
