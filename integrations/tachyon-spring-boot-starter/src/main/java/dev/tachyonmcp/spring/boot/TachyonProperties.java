/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code tachyon.*} configuration properties. Anything beyond these goes through a {@link
 * TachyonServerCustomizer} bean.
 *
 * @param enabled whether to create and start the server
 * @param name    server name reported to clients, or {@code null} for the Tachyon default
 * @param version server version reported to clients, or {@code null} for the Tachyon default
 * @param host    bind host, or {@code null} for the Tachyon default
 * @param port    listen port; {@code 0} picks an ephemeral port
 */
@ExperimentalApi
@ConfigurationProperties("tachyon")
public record TachyonProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable String name,
        @Nullable String version,
        @Nullable String host,
        @DefaultValue("8080") int port) {}
