/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.json.JsonSchemaValidator;
import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.tools.AsyncToolFn;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies a {@link ToolResult} completed from a foreign thread is re-anchored to the server
 * executor before output validation runs.
 */
@Execution(ExecutionMode.SAME_THREAD)
class ForeignThreadContinuationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void foreignThreadCompletionReanchoredToServerExecutor() throws Exception {
        var capturedThread = new CompletableFuture<String>();

        var outputSchema = MAPPER.createObjectNode()
                .put("type", "object")
                .set("properties", MAPPER.createObjectNode())
                .put("additionalProperties", true);

        var descriptor = ToolDescriptor.builder()
                .name("foreign-thread-tool")
                .description("Returns future completed from a foreign thread")
                .outputSchema(outputSchema.toString())
                .build();

        var fn = (AsyncToolFn) (ctx, request) -> {
            var future = new CompletableFuture<ToolResult>();
            var completer = new Thread(
                    () -> future.complete(ToolResult.structured(Map.of("result", "from-foreign"), "from-foreign")),
                    "foreign-completer");
            completer.start();
            return future;
        };

        var recordingValidator = (JsonSchemaValidator) (schema, arguments) -> {
            capturedThread.complete(Thread.currentThread().getName() + " virtual:"
                    + Thread.currentThread().isVirtual());
            return java.util.List.of();
        };

        try (ServerEngine server = newEngine(
                b -> b.json(j -> j.outputSchemaValidator(recordingValidator)),
                s -> s.tools().registerAsync(descriptor, fn))) {
            var session = server.createSession("sess-foreign");
            session.activate();
            var dispatcher = new McpDispatcher(server, server.executor());
            var params = Map.of("name", "foreign-thread-tool", "arguments", Map.of());
            dispatcher
                    .dispatchRequestAsync(
                            RequestId.of(1), "tools/call", params, "sess-foreign", SecurityContext.anonymous())
                    .get(10, TimeUnit.SECONDS);
        }

        String threadInfo = capturedThread.get(10, TimeUnit.SECONDS);
        assertThat(threadInfo)
                .as("output validation must run on server executor virtual thread, not foreign completer")
                .endsWith("virtual:true");
        assertThat(threadInfo)
                .as("thread name must start with server executor prefix 'tachyon-'")
                .startsWith("tachyon-");
    }
}
