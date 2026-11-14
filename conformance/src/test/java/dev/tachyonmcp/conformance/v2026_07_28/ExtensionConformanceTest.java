/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance.v2026_07_28;

import dev.tachyonmcp.conformance.AbstractServerConformanceTest;

/**
 * MCP extension conformance (tasks, skills), which upstream runs on the 2026-07-28 wire and reaches only
 * through its bare {@code --suite all}.
 */
class ExtensionConformanceTest extends AbstractServerConformanceTest {

    ExtensionConformanceTest() {
        super(new ExtensionConformanceServer(), LATEST_MAIN_SHA, null, true);
    }
}
