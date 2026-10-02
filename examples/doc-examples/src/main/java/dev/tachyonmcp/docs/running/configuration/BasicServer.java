package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import java.time.Duration;

public final class BasicServer {

    private BasicServer() {}

    public static void main(String[] args) {
        // snips-start: config_basic_server
        var server = TachyonServer.builder()
            .info(i -> i.name("my-server").version("1.0"))
            .network(n -> n.port(8080).ioEngine(NettyIoEngine.AUTO))
            .session(s -> s.sessionTtl(Duration.ofMinutes(5)))
            .build();
        server.start();
        // snips-end: config_basic_server
    }
}
