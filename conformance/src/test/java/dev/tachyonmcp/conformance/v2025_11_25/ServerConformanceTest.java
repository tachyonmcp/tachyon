/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance.v2025_11_25;

import dev.tachyonmcp.conformance.AbstractServerConformanceTest;

/**
 * MCP conformance for protocol version 2025-11-25.
 */
class ServerConformanceTest extends AbstractServerConformanceTest {

    ServerConformanceTest() {
        super(new ConformanceServer(), "0.1.16", "2025-11-25", true);
    }
}
