package dev.tachyonmcp.docs.quickstart;

// snips-start: quickstart_server

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.core.server.TachyonServer;

public final class MyMcpServer {
    public static final class GreetingService {
        @McpTool(description = "Say hello to someone")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }
    }

    public static void main(String[] args) {
        final var server = TachyonServer.builder()
            .name("my-server")
            .version("1.0")
            .annotations(annotations -> annotations.register(new GreetingService()))
            .host("127.0.0.1")
            .port(8080)
            .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
// snips-end: quickstart_server
