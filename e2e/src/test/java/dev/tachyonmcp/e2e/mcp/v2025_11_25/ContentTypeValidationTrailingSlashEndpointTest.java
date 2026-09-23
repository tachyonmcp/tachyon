/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2025_11_25;

/**
 * Server configured with {@code /mcp/}, requests sent to {@code /mcp}: the endpoint validator
 * normalizes the trailing slash and serves them, so the Content-Type rule must apply too.
 */
class ContentTypeValidationTrailingSlashEndpointTest extends ContentTypeValidationTest {

    @Override
    protected String configuredEndpointPath() {
        return "/mcp/";
    }
}
