package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;

final class NetworkOptions {

    private NetworkOptions() {}

    static TachyonServer withExtraHost() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: config_allowed_hosts
                .network(n -> n.allowedHosts("host.docker.internal:8096"))
                // snips-end: config_allowed_hosts
                .build();
        server.start();
        return server;
    }

    static TachyonServer withEpoll() {
        var server = TachyonServer.builder()
                .port(0)
                // snips-start: config_io_engine
                .network(n -> n.ioEngine(NettyIoEngine.EPOLL)) // fails fast on macOS
                // snips-end: config_io_engine
                .build();
        server.start();
        return server;
    }
}
