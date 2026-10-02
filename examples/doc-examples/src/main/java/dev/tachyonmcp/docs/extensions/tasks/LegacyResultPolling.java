package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import java.time.Duration;

final class LegacyResultPolling {

    private LegacyResultPolling() {}

    static TachyonServer start(TaskConnector connector) {
        var server = TachyonServer.builder()
                .port(0)
                .session(SessionConfig.Builder::enabled)
                // snips-start: tasks_legacy_result_poll
                .withExtension(TasksExtension.class, tasks -> tasks
                        .connector(connector)
                        .resultPollInterval(Duration.ofMillis(500)))
                // snips-end: tasks_legacy_result_poll
                .build();
        server.start();
        return server;
    }
}
