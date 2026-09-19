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
 * <p>The javadoc below is what the configuration processor turns into IDE completion text, so each
 * {@code @param} reads as a standalone sentence.
 *
 * @param enabled Whether to create and start the MCP server.
 * @param name    Server name reported to clients. Defaults to the Tachyon default when not set.
 * @param version Server version reported to clients. Defaults to the Tachyon default when not set.
 * @param host    Bind address for the MCP transport. Defaults to the Tachyon default when not set.
 * @param port    Port the MCP transport listens on; 0 picks an ephemeral port. Tachyon binds its own
 *                Netty transport, so this is independent of `server.port` — leaving both at 8080 in a
 *                Spring web application makes one of them fail to bind.
 */
@ExperimentalApi
@ConfigurationProperties("tachyon")
public record TachyonProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable String name,
        @Nullable String version,
        @Nullable String host,
        @DefaultValue("8080") int port) {}
