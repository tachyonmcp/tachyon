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

    @Value.Default
    default String name() {
        return "tachyon-mcp";
    }

    @Value.Default
    default String version() {
        return "0.1";
    }

    @Nullable
    String description();

    @Nullable
    String title();

    @Nullable
    String websiteUrl();

    @Nullable
    String instructions();

    List<Icon> icons();

    ServerIdentity DEFAULT = DefaultServerIdentity.builder().build();

    static Builder builder() {
        return DefaultServerIdentity.builder();
    }

    /** Builder for {@link ServerIdentity}. */
    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ServerIdentity instance);

        Builder name(String name);

        Builder version(String version);

        Builder description(@Nullable String description);

        Builder title(@Nullable String title);

        Builder websiteUrl(@Nullable String websiteUrl);

        Builder instructions(@Nullable String instructions);

        Builder icons(Iterable<? extends Icon> icons);

        default Builder icons(Icon... icons) {
            return icons(List.of(icons));
        }

        ServerIdentity build();
    }
}
