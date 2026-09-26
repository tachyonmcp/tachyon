/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;
import dev.tachyonmcp.api.server.extensions.ExtensionContext;
import dev.tachyonmcp.api.server.extensions.spi.ExtensionProvider;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.ArrayList;
import java.util.List;

/** Test-only configurable extension: records how it was configured and bootstrapped. */
public final class StubExtension implements ConfigurableExtension<StubExtension.Builder>, EngineExtension {

    public static final String ID = "test/stub";

    private final List<String> options;
    public final List<String> bootstraps = new ArrayList<>();

    private StubExtension(List<String> options) {
        this.options = options;
    }

    public List<String> options() {
        return options;
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
    public void bootstrap(ServerEngine engine) {
        bootstraps.add("engine");
    }

    @Override
    public void bootstrap(ExtensionContext context) {
        bootstraps.add("context");
    }

    public static final class Builder implements ExtensionBuilder<StubExtension> {
        private final List<String> options = new ArrayList<>();

        public Builder option(String option) {
            options.add(option);
            return this;
        }

        @Override
        public StubExtension build() {
            return new StubExtension(List.copyOf(options));
        }
    }

    public static final class Provider implements ExtensionProvider<StubExtension, Builder> {
        @Override
        public Class<StubExtension> extensionType() {
            return StubExtension.class;
        }

        @Override
        public Builder newBuilder() {
            return new Builder();
        }
    }
}
