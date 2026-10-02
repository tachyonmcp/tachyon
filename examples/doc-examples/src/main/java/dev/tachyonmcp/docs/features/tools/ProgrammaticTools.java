package dev.tachyonmcp.docs.features.tools;

import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class ProgrammaticTools {

    record Weather(String summary) {}

    private ProgrammaticTools() {}

    static CompletionStage<Weather> fetchWeather(String city) {
        return CompletableFuture.supplyAsync(() -> new Weather("Sunny in " + city));
    }

    static TachyonServer hello() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: tools_with_tools_hello
                .withTools(tools -> tools.register(
                        tool -> tool.name("hello").description("Say hello"),
                        (ctx, request) -> ToolResult.text("Hello!")))
                // snips-end: tools_with_tools_hello
                .build();
        server.start();
        return server;
    }

    static TachyonServer helloWithSchema() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: tools_with_tools_schema
                .withTools(tools -> tools.register(
                        b -> b.name("hello")
                            .description("Say hello")
                            .inputSchema("""
                            {"type":"object","properties":{"name":{"type":"string"}}}
                            """),
                        (ctx, request) -> ToolResult.text(
                            "Hello, " + request.arguments().stringOr("name", "world") + "!")))
                // snips-end: tools_with_tools_schema
                .build();
        server.start();
        return server;
    }

    static TachyonServer asyncWeather() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: tools_with_tools_async
                .withTools(tools -> tools.registerAsync(
                        tool -> tool.name("get_weather_async"),
                        (ctx, request) -> fetchWeather(request.arguments().stringValue("city"))
                                .thenApply(w -> ToolResult.text(w.summary()))))
                // snips-end: tools_with_tools_async
                .build();
        server.start();
        return server;
    }
}
