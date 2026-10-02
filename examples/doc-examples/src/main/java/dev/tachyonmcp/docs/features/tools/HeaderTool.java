package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.io.IOException;
import java.io.UncheckedIOException;

final class HeaderTool {

    private HeaderTool() {}

    static String schema() {
        try (var in = HeaderTool.class.getResourceAsStream("header-schema.json")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static TachyonServer start(String inputSchema) {
        var server = TachyonServer.builder()
                .port(0)
                .withTools(tools -> tools.register(
                        tool -> tool.name("search").inputSchema(inputSchema),
                        (ctx, request) -> ToolResult.text("searched")))
                .build();
        server.start();
        return server;
    }
}
