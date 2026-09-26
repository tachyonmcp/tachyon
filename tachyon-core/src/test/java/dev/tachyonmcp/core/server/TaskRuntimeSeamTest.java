/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import dev.tachyonmcp.core.server.extensions.FakeTasksExtension;
import dev.tachyonmcp.core.server.features.tasks.TaskRuntime;
import org.junit.jupiter.api.Test;

class TaskRuntimeSeamTest {

    @Test
    void withoutATasksExtensionNothingIsInstalledOrAdvertised() {
        try (var server = newEngine(b -> {})) {
            assertThat(server.taskRuntime()).isSameAs(TaskRuntime.NONE);
            assertThat(server.taskRuntime().executionConfigured()).isFalse();
            assertThat(server.resolveCapabilities().tasks()).isNull();
        }
    }

    @Test
    void anInstalledRuntimeDecidesTheAdvertisedTasksCapability() {
        try (var server = newEngine(b -> b.withExtensions(new FakeTasksExtension()))) {
            assertThat(server.taskRuntime().executionConfigured()).isTrue();
            assertThat(server.resolveCapabilities().tasks()).isNotNull();
            assertThat(server.resolveCapabilities().tasks().cancel()).isTrue();
        }
    }

    @Test
    void aTaskRuntimeCanBeInstalledOnlyOnce() {
        try (var server = newEngine(b -> b.withExtensions(new FakeTasksExtension()))) {
            assertThatIllegalStateException()
                    .isThrownBy(() -> server.installTaskRuntime(TaskRuntime.NONE))
                    .withMessageContaining("already installed");
        }
    }
}
