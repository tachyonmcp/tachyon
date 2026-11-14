/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance.v2026_07_28;

import dev.tachyonmcp.conformance.AbstractServerConformanceTest;

/**
 * MCP conformance for protocol version 2026-07-28.
 */
class ServerConformanceTest extends AbstractServerConformanceTest {

    ServerConformanceTest() {
        super(new ConformanceServer(), "0.2.0-alpha.12", "2026-07-28", false);
    }
}
