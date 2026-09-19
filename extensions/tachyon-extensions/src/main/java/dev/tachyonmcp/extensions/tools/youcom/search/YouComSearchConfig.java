/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tools.youcom.search;

import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

@Value.Builder
@Value.Style(visibility = Value.Style.ImplementationVisibility.PACKAGE, typeImmutable = "Default*")
public record YouComSearchConfig(
        @Nullable String apiKey,
        @Nullable Boolean freeTier,
        @Nullable String baseUrl) {

    public static final String DEFAULT_BASE = "https://api.you.com/v1/search";

    public YouComSearchConfig {
        if (apiKey != null && apiKey.isBlank()) {
            apiKey = null;
        }
    }

    public static YouComSearchConfigBuilder builder() {
        return new YouComSearchConfigBuilder();
    }

    public String effectiveBaseUrl() {
        return baseUrl != null ? baseUrl : DEFAULT_BASE;
    }

    public boolean isFreeTier() {
        return Boolean.TRUE.equals(freeTier());
    }
}
