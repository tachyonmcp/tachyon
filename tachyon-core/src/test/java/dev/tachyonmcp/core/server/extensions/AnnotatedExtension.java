/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.ProvidedBy;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-only extension found through {@link ProvidedBy} alone (no service file), whose provider
 * bootstraps it on the engine.
 */
@ProvidedBy(AnnotatedExtension.Provider.class)
public final class AnnotatedExtension implements ConfigurableExtension<AnnotatedExtension.Builder> {

    public static final String ID = "test/annotated";

    public final List<String> bootstraps = new ArrayList<>();

    @Override
    public String extensionId() {
        return ID;
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public void bootstrap(ExtensionContext context) {
        bootstraps.add("context");
    }

    public static final class Builder implements ExtensionBuilder<AnnotatedExtension> {
        @Override
        public AnnotatedExtension build() {
            return new AnnotatedExtension();
        }
    }

    public static final class Provider
            implements ExtensionProvider<AnnotatedExtension, Builder>, EngineBinding<AnnotatedExtension> {
        @Override
        public Class<AnnotatedExtension> extensionType() {
            return AnnotatedExtension.class;
        }

        @Override
        public Builder newBuilder() {
            return new Builder();
        }

        @Override
        public void bootstrap(AnnotatedExtension extension, ServerEngine engine) {
            extension.bootstraps.add("binding");
        }
    }
}
