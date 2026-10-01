/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.immutables.value.Value;

/** Requests user input by opening a URL (e.g. for OAuth or form fill). */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface UrlInputRequest extends InputRequest {

    /**
     * Prompt message shown to the user.
     *
     * @return the message
     */
    String message();

    /**
     * Identifier linking the response back to the elicitation context.
     *
     * @return the elicitation id
     */
    String elicitationId();

    /**
     * URL to open for user input.
     *
     * @return the url
     */
    String url();

    /**
     * Validates the value invariants.
     */
    @Value.Check
    default void check() {
        if (message().isBlank()) throw new IllegalArgumentException("message must not be blank");
        if (elicitationId().isBlank()) throw new IllegalArgumentException("elicitationId must not be blank");
        if (url().isBlank()) throw new IllegalArgumentException("url must not be blank");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultUrlInputRequest.builder();
    }

    /**
     * Creates a {@link UrlInputRequest} from the supplied values.
     *
     * @param message the message
     * @param elicitationId the elicitation id
     * @param url the url
     * @return the url input request
     */
    static UrlInputRequest of(String message, String elicitationId, String url) {
        return DefaultUrlInputRequest.of(message, elicitationId, url);
    }

    /**
     * Builder for the enclosing type.
     */
    interface Builder {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(UrlInputRequest instance);

        /**
         * Sets the message.
         *
         * @param message the message
         * @return this builder
         */
        Builder message(String message);

        /**
         * Sets the elicitation id.
         *
         * @param elicitationId the elicitation id
         * @return this builder
         */
        Builder elicitationId(String elicitationId);

        /**
         * Sets the url.
         *
         * @param url the url
         * @return this builder
         */
        Builder url(String url);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        UrlInputRequest build();
    }
}
