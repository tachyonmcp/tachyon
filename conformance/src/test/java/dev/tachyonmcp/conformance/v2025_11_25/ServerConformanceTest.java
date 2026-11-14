/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance;

/**
 * Test against the latest stable version of MCP conformance tests
 */
class DefaultServerConformanceTest extends AbstractServerConformanceTest {

    DefaultServerConformanceTest() {
        super(new DefaultConformanceServer(), "0.1.16", "conformance-baseline-0.1.yml", "default", "2025-11-25", true);
    }
}
