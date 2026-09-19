/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import java.util.List;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Optional metadata that clients can use to tailor how content is presented.
 *
 * <p>{@code audience} hints at the intended role (user/assistant), {@code priority} controls
 * ordering, and {@code lastModified} carries an RFC-3339 timestamp of the last modification.
 * {@code audience} is an empty list when unrestricted; {@code priority} and {@code lastModified}
 * are {@code null} when absent — omit the annotation block entirely rather than sending empty
 * values.
 */
@Value.Immutable
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface Annotations {

    /**
     * Intended audience roles, or an empty list when unrestricted.
     *
     * @return the audience list, or an empty list
     */
    List<Role> audience();

    /**
     * Ordering hint in {@code [0.0, 1.0]}, or {@code null} for default.
     *
     * @return the priority, or {@code null}
     */
    @Nullable
    Double priority();

    /**
     * RFC-3339 timestamp of the last modification, or {@code null} when unknown.
     *
     * @return the last-modified timestamp, or {@code null}
     */
    @Nullable
    String lastModified();

    /**
     * Validates that the priority is within the range from 0.0 to 1.0.
     *
     * @throws IllegalArgumentException if the priority is NaN or outside the range from 0.0 to 1.0
     */
    @Value.Check
    default void checkPriority() {
        Double priority = priority();
        if (priority != null && (Double.isNaN(priority) || priority < 0.0 || priority > 1.0)) {
            throw new IllegalArgumentException("priority must be in [0.0, 1.0], got: " + priority);
        }
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultAnnotations.builder();
    }

    /**
     * Creates annotations with the given values.
     *
     * @param audience     intended audience roles, or an empty list
     * @param priority     ordering hint, or {@code null}
     * @param lastModified RFC-3339 timestamp, or {@code null}
     * @return the new annotations
     */
    static Annotations of(List<Role> audience, @Nullable Double priority, @Nullable String lastModified) {
        return DefaultAnnotations.builder()
                .audience(audience)
                .priority(priority)
                .lastModified(lastModified)
                .build();
    }

    /** Builder for {@link Annotations}. */
    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy from
         * @return this builder
         */
        Builder from(Annotations instance);

        /**
         * Sets the intended audience.
         *
         * @param elements the audience roles
         * @return this builder
         */
        Builder audience(Iterable<? extends Role> elements);

        /**
         * Sets the intended audience.
         *
         * @param roles the audience roles
         * @return this builder
         */
        default Builder audience(Role... roles) {
            return audience(List.of(roles));
        }

        /**
         * Sets the ordering hint.
         *
         * @param priority the priority, or {@code null}
         * @return this builder
         */
        Builder priority(@Nullable Double priority);

        /**
         * Sets the last-modified timestamp.
         *
         * @param lastModified the timestamp, or {@code null}
         * @return this builder
         */
        Builder lastModified(@Nullable String lastModified);

        /**
         * Builds the annotations.
         *
         * @return the new annotations
         */
        Annotations build();
    }
}
