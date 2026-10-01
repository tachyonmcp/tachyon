/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tools.youcom.search;

import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Connection settings for the You.com search tool.
 *
 * @param apiKey optional API key; blank values are normalized to {@code null}
 * @param freeTier whether to request the free profile and omit authentication
 * @param baseUrl optional endpoint override
 */
@Value.Builder
@Value.Style(visibilityString = "PACKAGE", typeImmutable = "Default*")
public record YouComSearchConfig(
        @Nullable String apiKey,
        @Nullable Boolean freeTier,
        @Nullable String baseUrl) {

    public static final String DEFAULT_BASE = "https://api.you.com/v1/search";

    /**
     * Creates search settings, normalizing a blank API key.
     *
     * @param apiKey optional API key
     * @param freeTier whether to use the free profile
     * @param baseUrl optional endpoint override
     */
    public YouComSearchConfig {
        if (apiKey != null && apiKey.isBlank()) {
            apiKey = null;
        }
    }

    /**
     * Creates a settings builder.
     *
     * @return a new builder
     */
    public static YouComSearchConfigBuilder builder() {
        return new YouComSearchConfigBuilder();
    }

    /**
     * Resolves the configured endpoint or the default search endpoint.
     *
     * @return the search endpoint URL
     */
    public String effectiveBaseUrl() {
        return baseUrl != null ? baseUrl : DEFAULT_BASE;
    }

    /**
     * Reports whether the free profile was explicitly enabled.
     *
     * @return {@code true} only when {@code freeTier} is {@code true}
     */
    public boolean isFreeTier() {
        return Boolean.TRUE.equals(freeTier());
    }
}
