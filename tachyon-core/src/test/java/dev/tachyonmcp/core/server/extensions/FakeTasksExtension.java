/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.extensions;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.ProgressToken;
import dev.tachyonmcp.api.server.domain.ServerCapabilities;
import dev.tachyonmcp.api.server.extensions.AdvertiseMode;
import dev.tachyonmcp.api.server.features.tasks.TaskSnapshot;
import dev.tachyonmcp.core.server.features.tasks.TaskRuntime;
import dev.tachyonmcp.core.server.features.tasks.TasksExtensionSupport;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Test double for the tasks extension: installs a {@link TaskRuntime} that records what the core
 * handlers hand it, so core tests run without the {@code tachyon-extensions-tasks} module.
 */
public final class FakeTasksExtension implements EngineExtension {

    public record Published(
            TaskSnapshot snapshot,
            @Nullable String sessionId,
            @Nullable ProgressToken token) {}

    public final List<Published> published = new ArrayList<>();
    public Set<String> readable = Set.of();

    @Override
    public String extensionId() {
        return TasksExtensionSupport.ID;
    }

    @Override
    public AdvertiseMode advertiseMode() {
        return AdvertiseMode.ALWAYS;
    }

    @Override
    public void bootstrap(ServerEngine engine) {
        engine.installTaskRuntime(new TaskRuntime() {
            @Override
            public boolean executionConfigured() {
                return true;
            }

            @Override
            public TaskSnapshot publish(
                    TaskSnapshot snapshot, @Nullable String sessionId, @Nullable ProgressToken progressToken) {
                published.add(new Published(snapshot, sessionId, progressToken));
                return snapshot;
            }

            @Override
            public Set<String> readableTaskIds(InteractionContext ctx, Set<String> taskIds) {
                return readable;
            }

            @Override
            public ServerCapabilities.@Nullable Tasks capability(boolean hasTaskAugmentedTools) {
                return new ServerCapabilities.Tasks(false, true, true);
            }
        });
    }
}
