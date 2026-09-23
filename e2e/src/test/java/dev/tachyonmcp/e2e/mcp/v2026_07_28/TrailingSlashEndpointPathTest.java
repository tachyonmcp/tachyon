/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

/**
 * Server configured with {@code /mcp/}, requests sent to {@code /mcp}: the endpoint validator
 * normalizes the trailing slash and admits them, so every later stage must treat them as MCP
 * requests too.
 */
class TrailingSlashEndpointPathTest extends CustomEndpointPathTest {

    @Override
    protected String configuredEndpointPath() {
        return "/mcp/";
    }

    @Override
    protected String requestPath() {
        return "/mcp";
    }
}
