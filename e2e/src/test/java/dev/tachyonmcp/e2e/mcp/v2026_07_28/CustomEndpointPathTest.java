/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import dev.tachyonmcp.e2e.mcp.AbstractEndpointPathTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import java.net.URI;
import org.jspecify.annotations.Nullable;

/** Server configured with {@code /api/mcp} serves 2026-07-28 requests there and nowhere else. */
class CustomEndpointPathTest extends AbstractEndpointPathTest<Mcp20260728Client> {

    @Override
    protected SessionMode sessionMode() {
        return SessionMode.STATELESS;
    }

    @Override
    protected String configuredEndpointPath() {
        return "/api/mcp";
    }

    @Override
    protected String requestPath() {
        return "/api/mcp";
    }

    @Override
    protected String protocolVersion() {
        return Mcp20260728Client.PROTOCOL_VERSION;
    }

    @Override
    protected @Nullable String openSession(Mcp20260728Client client) {
        return null;
    }

    @Override
    protected Mcp20260728Client createTestClient(URI endpoint) {
        return new Mcp20260728Client(endpoint);
    }
}
