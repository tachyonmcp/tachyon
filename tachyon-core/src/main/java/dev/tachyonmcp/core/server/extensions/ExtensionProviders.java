/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;
import dev.tachyonmcp.api.server.extensions.ProvidedBy;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;
import java.util.ArrayList;
import java.util.ServiceLoader;

/**
 * Resolves the {@link ExtensionProvider} for a requested extension type: first the provider the
 * type names with {@link ProvidedBy}, then a {@link ServiceLoader} lookup on the type's own class
 * loader. Resolution is per type and cached per class; nothing is enabled just because a provider is
 * present, and a broken or duplicate provider for another type never affects this one.
 */
@InternalApi
public final class ExtensionProviders {

    private static final String SERVICE_FILE = "META-INF/services/" + ExtensionProvider.class.getName();

    private static final ClassValue<ExtensionProvider<?, ?>> PROVIDERS = new ClassValue<>() {
        @Override
        protected ExtensionProvider<?, ?> computeValue(Class<?> type) {
            return locate(type);
        }
    };

    private ExtensionProviders() {}

    /**
     * Returns the provider for {@code type}.
     *
     * @param type the extension class the caller asked for
     * @param <E> the extension type
     * @param <B> the builder type
     * @return the provider
     * @throws IllegalStateException if no provider, or more than one, serves {@code type}
     */
    @SuppressWarnings("unchecked")
    public static <E extends ConfigurableExtension<B>, B extends ExtensionBuilder<E>>
            ExtensionProvider<E, B> providerFor(Class<E> type) {
        return (ExtensionProvider<E, B>) PROVIDERS.get(type);
    }

    /**
     * Tells whether the provider for {@code type} bootstraps it on the engine. Such an extension
     * cannot be registered as a plain instance: bootstrapping it through {@code ExtensionContext}
     * would skip its {@link EngineBinding}.
     *
     * @param type a configurable extension class
     * @return {@code true} if the provider is an {@link EngineBinding}
     * @throws IllegalStateException if no provider, or more than one, serves {@code type}
     */
    public static boolean bindsEngine(Class<?> type) {
        return PROVIDERS.get(type) instanceof EngineBinding<?>;
    }

    private static ExtensionProvider<?, ?> locate(Class<?> type) {
        var annotation = type.getAnnotation(ProvidedBy.class);
        if (annotation != null) {
            return checked(type, instantiate(type, annotation.value()));
        }
        var matches = new ArrayList<ExtensionProvider<?, ?>>();
        for (var provider : ServiceLoader.load(ExtensionProvider.class, type.getClassLoader())) {
            if (provider.extensionType() == type) {
                matches.add(provider);
            }
        }
        var distinct = matches.stream().map(Object::getClass).distinct().toList();
        if (distinct.size() > 1) {
            throw new IllegalStateException("More than one ExtensionProvider serves " + type.getName() + ": "
                    + distinct.stream().map(Class::getName).toList());
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("No ExtensionProvider for " + type.getName()
                    + ". Annotate it with @" + ProvidedBy.class.getSimpleName() + ", or register a provider via "
                    + SERVICE_FILE
                    + " (merge these files with the shade ServicesResourceTransformer when building a fat jar).");
        }
        return matches.getFirst();
    }

    private static ExtensionProvider<?, ?> instantiate(
            Class<?> type, Class<? extends ExtensionProvider<?, ?>> providerClass) {
        try {
            return providerClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Cannot create " + providerClass.getName() + " named by @ProvidedBy on " + type.getName()
                            + ": it needs a public no-argument constructor",
                    e);
        }
    }

    private static ExtensionProvider<?, ?> checked(Class<?> type, ExtensionProvider<?, ?> provider) {
        if (provider.extensionType() != type) {
            throw new IllegalStateException(provider.getClass().getName() + " named by @ProvidedBy on " + type.getName()
                    + " serves " + provider.extensionType().getName());
        }
        return provider;
    }
}
