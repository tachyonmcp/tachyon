/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.extensions.ConfigurableExtension;
import dev.tachyonmcp.api.server.extensions.ExtensionBuilder;

/** Test-only configurable extension with no provider registered. */
public final class UnregisteredExtension implements ConfigurableExtension<UnregisteredExtension.Builder> {

    @Override
    public String extensionId() {
        return "test/unregistered";
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    public static final class Builder implements ExtensionBuilder<UnregisteredExtension> {
        @Override
        public UnregisteredExtension build() {
            return new UnregisteredExtension();
        }
    }
}
