package dev.tachyonmcp.docs.running.configuration;

import dev.tachyonmcp.core.server.TachyonServer;
import java.time.Duration;

final class SessionAlternatives {

    private SessionAlternatives() {}

    static TachyonServer allThree() {
        return TachyonServer.builder()
                .port(0)
                // snips-start: config_session_alternatives
                .session(s -> s.sessionTtl(Duration.ofMinutes(5)))   // sessions on, custom TTL
                .session(s -> s.enabled())                           // sessions on, all defaults
                .stateless()                                         // explicitly stateless (the default)
                // snips-end: config_session_alternatives
                .build();
    }
}
