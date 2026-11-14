/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.conformance.v2027_03_30;

import dev.tachyonmcp.conformance.AbstractServerConformanceTest;
import dev.tachyonmcp.conformance.v2026_07_28.ConformanceServer;
import org.junit.jupiter.api.Disabled;

/**
 * MCP conformance for the upcoming protocol version 2027-03-30, run from a pinned upstream {@code main}
 * commit, reusing the 2026-07-28 server until the two revisions differ.
 */
@Disabled("Upstream conformance does not know 2027-03-30 yet")
class ServerConformanceTest extends AbstractServerConformanceTest {

    ServerConformanceTest() {
        super(new ConformanceServer(), LATEST_MAIN_SHA, "2027-03-30", false);
    }
}
