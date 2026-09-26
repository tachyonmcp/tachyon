/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

/**
 * A {@link ServerExtension} configured through a builder chosen by its type rather than constructed
 * by the caller. The builder type is what lets {@code ServerBuilder.withExtension(Class, Consumer)}
 * infer the configurer's parameter from the extension class alone:
 *
 * <pre>{@code
 * TachyonServer.builder()
 *         .withExtension(TasksExtension.class, tasks -> tasks.connector(connector))
 *         .build();
 * }</pre>
 *
 * <p>Implementations are discovered through
 * {@link dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider}. Only extensions the caller
 * names are enabled; a provider on the classpath enables nothing by itself.
 *
 * @param <B> the builder that configures and creates this extension
 */
public interface ConfigurableExtension<B extends ExtensionBuilder<?>> extends ServerExtension {}
