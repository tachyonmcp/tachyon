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
 * installs and bootstraps it on the engine. Every lifecycle call lands in {@link #events}.
 */
@ProvidedBy(AnnotatedExtension.Provider.class)
public final class AnnotatedExtension implements ConfigurableExtension<AnnotatedExtension.Builder> {

    public static final String ID = "test/annotated";

    public final List<String> events;

    private AnnotatedExtension(List<String> events) {
        this.events = events;
    }

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
        events.add("context");
    }

    @Override
    public void shutdown() {
        events.add("shutdown");
    }

    public static final class Builder implements ExtensionBuilder<AnnotatedExtension> {
        private List<String> events = new ArrayList<>();

        public Builder events(List<String> events) {
            this.events = events;
            return this;
        }

        @Override
        public AnnotatedExtension build() {
            return new AnnotatedExtension(events);
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
        public void install(AnnotatedExtension extension, ServerEngine engine) {
            extension.events.add("install");
        }

        @Override
        public void bootstrap(AnnotatedExtension extension, ServerEngine engine) {
            extension.events.add("binding");
        }
    }
}
