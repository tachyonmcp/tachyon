package dev.tachyonmcp.docs.annotations;

import dev.tachyonmcp.annotations.mcpjava.McpJavaAnnotationProvider;
import dev.tachyonmcp.core.server.TachyonServer;
import org.mcpjava.server.tools.Tool;
import org.mcpjava.server.tools.ToolArg;

final class ThirdPartyServer {

    private ThirdPartyServer() {}

    public static class WeatherService {
        @Tool(name = "temperature", description = "Current temperature in Celsius")
        public double temperature(@ToolArg(name = "city") String city) {
            return 18.5;
        }
    }

    public static class CalculatorService {
        @Tool(name = "add", description = "Adds two integers")
        public int add(@ToolArg(name = "left") int left, @ToolArg(name = "right") int right) {
            return left + right;
        }
    }

    static TachyonServer build() {
        // snips-start: annotations_third_party_register
        var server = TachyonServer.builder()
            .annotations(a -> a
                .withProvider(new McpJavaAnnotationProvider())
                .register(new WeatherService())
                .register(new CalculatorService()))
            .build();
        // snips-end: annotations_third_party_register
        return server;
    }
}
