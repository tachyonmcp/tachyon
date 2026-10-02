package dev.tachyonmcp.docs.json;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.Map;

public final class CustomSerdeServer {

    private CustomSerdeServer() {}

    public static void main(String[] args) {
        var myPayloadSerde = new MarkingSerde();
        // snips-start: json_serde
        var server = TachyonServer.builder()
            .json(json -> json.serde(myPayloadSerde))
            .port(8080)
            .build();
        // snips-end: json_serde
        server.tools().register(
                tool -> tool.name("city"), (ctx, request) -> ToolResult.structured(Map.of("city", "Paris")));
        server.start();
    }
}
