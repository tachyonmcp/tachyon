package dev.tachyonmcp.docs.extensions.tasks;

import dev.tachyonmcp.api.server.features.tasks.TaskConnector;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.tasks.TasksExtension;
import java.time.Duration;

final class RetentionServer {

    private RetentionServer() {}

    static TachyonServer start(TaskConnector connector) {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: tasks_retention
                .withExtension(TasksExtension.class, t -> t
                        .connector(connector)
                        .keepAlive(Duration.ofMinutes(10))
                        .pollInterval(Duration.ofSeconds(2)))
                // snips-end: tasks_retention
                .build();
        server.start();
        return server;
    }
}
