/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.extensions;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the {@link ExtensionProvider} that creates the builder for a {@link ConfigurableExtension}.
 * The server reads it when a caller asks for the extension by class, so no
 * {@code META-INF/services} entry is needed and the lookup does not depend on class loaders or
 * shaded service files.
 *
 * <pre>{@code
 * @ProvidedBy(TasksExtensionProvider.class)
 * public final class TasksExtension implements ConfigurableExtension<TasksExtension.Builder> { ... }
 * }</pre>
 *
 * <p>The provider needs a public no-argument constructor and must report the annotated class from
 * {@link ExtensionProvider#extensionType()}. Experimental together with the provider SPI it names.
 */
@ExperimentalApi
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProvidedBy {

    /**
     * Returns the provider class.
     *
     * @return the provider that creates this extension's builder
     */
    Class<? extends ExtensionProvider<?, ?>> value();
}
