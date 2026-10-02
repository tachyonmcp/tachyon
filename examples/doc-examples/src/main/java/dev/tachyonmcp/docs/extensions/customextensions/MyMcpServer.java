package dev.tachyonmcp.docs.extensions.customextensions;

// snips-start: custom_ext_server
import dev.tachyonmcp.core.server.TachyonServer;

public final class MyMcpServer {
    public static void main(String[] args) {
        final var server = TachyonServer.builder()
                .name("my-server")
                .version("1.0")
                .withExtensions(new GreetingsExtension())
                .session(session -> session.enabled())
                .host("127.0.0.1")
                .port(8080)
                .build();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
        server.start();
    }
}
// snips-end: custom_ext_server
