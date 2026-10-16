/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * MCP Authorization (2025-11-25 and 2026-07-28) § Token Theft: servers MUST store tokens securely.
 * The raw wire log carries every byte of a request, {@code Authorization} header included, so only
 * {@code TRACE} may dump bytes. The levels of the three loggers are set in {@code simplelogger.properties}.
 */
class WireLogTest {

    private static final String SECRET = "s3cr3t-bearer-token";
    private static final String REQUEST = "Authorization: Bearer " + SECRET + "\r\n\r\n";
    /** The first sixteen bytes of {@link #REQUEST}, which the hex dump prints as text in its first row. */
    private static final String FIRST_ROW = "Authorization: B";

    private static final String LOGGER = WireLogTest.class.getName();

    @Test
    @ResourceLock("SYSTEM_ERR")
    void debugLogsTheSizeOfARequestButNoneOfItsBytes() {
        var logged = logWhileReading(McpChannelInitializer.wireLogger(LOGGER + ".debug"));

        assertThat(logged).contains("READ: " + REQUEST.length() + "B").doesNotContain(FIRST_ROW, SECRET);
    }

    @Test
    @ResourceLock("SYSTEM_ERR")
    void traceDumpsTheBytesOfARequestSoItMustStayOffInProduction() {
        var logged = logWhileReading(McpChannelInitializer.wireLogger(LOGGER + ".trace"));

        assertThat(logged).contains("READ: " + REQUEST.length() + "B", FIRST_ROW);
    }

    @Test
    void thereIsNoWireLogUnlessDebugOrTraceIsEnabled() {
        assertThat(McpChannelInitializer.wireLogger(LOGGER + ".off")).isNull();
    }

    private static String logWhileReading(ChannelHandler wireLog) {
        var captured = new ByteArrayOutputStream();
        var original = System.err;
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            var channel = new EmbeddedChannel(wireLog);
            channel.writeInbound(Unpooled.copiedBuffer(REQUEST, StandardCharsets.UTF_8));
            channel.finishAndReleaseAll();
        } finally {
            System.setErr(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
