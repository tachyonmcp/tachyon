/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

/**
 * Test against the latest edge version of MCP conformance tests
 */
class EdgeServerConformanceTest extends AbstractServerConformanceTest {

    EdgeServerConformanceTest() {
        super(
                new EdgeConformanceServer(),
                "0.2.0-alpha.11",
                "conformance-baseline-0.2.yml",
                "edge",
                "2026-07-28",
                false);
    }
}
