/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;
import dev.tachyonmcp.api.server.extensions.ProvidedBy;

/** Test-only extension naming a provider that serves another type. */
@ProvidedBy(StubExtension.Provider.class)
public final class MisannotatedExtension implements ConfigurableExtension<MisannotatedExtension.Builder> {

    @Override
    public String extensionId() {
        return "test/misannotated";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    public static final class Builder implements ExtensionBuilder<MisannotatedExtension> {
        @Override
        public MisannotatedExtension build() {
            return new MisannotatedExtension();
        }
    }
}
