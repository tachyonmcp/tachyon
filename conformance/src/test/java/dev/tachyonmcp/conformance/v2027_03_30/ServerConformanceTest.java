/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance.v2027_03_30;

import dev.tachyonmcp.conformance.AbstractServerConformanceTest;
import dev.tachyonmcp.conformance.v2026_07_28.ConformanceServer;

/**
 * MCP conformance for the upcoming protocol version 2027-03-30, run from a pinned upstream {@code main}
 * commit. Upstream names the upcoming version {@code draft} and still maps it to 2026-07-28, so this
 * suite reuses that server until the two revisions differ.
 */
class ServerConformanceTest extends AbstractServerConformanceTest {

    ServerConformanceTest() {
        super(new ConformanceServer(), "96a6f9478b7f2e125cf0360a213383cf57533893", "draft", false);
    }
}
