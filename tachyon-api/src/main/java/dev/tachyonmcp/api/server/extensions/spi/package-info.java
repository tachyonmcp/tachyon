/*
 * Copyright (c) 2026 Konstantin Pavlov and contributors.
 */

/**
 * SPI for extensions configured by type, loadable via {@link java.util.ServiceLoader}.
 *
 * <p>Implement {@link ExtensionProvider} for a
 * {@link dev.tachyonmcp.api.server.extensions.ConfigurableExtension} and register it in
 * {@code META-INF/services/dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider}.
 */
@NullMarked
package dev.tachyonmcp.api.server.extensions.spi;

import org.jspecify.annotations.NullMarked;
