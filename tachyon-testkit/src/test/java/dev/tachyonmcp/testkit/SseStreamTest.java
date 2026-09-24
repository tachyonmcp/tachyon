/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Verifies {@link SseStream}'s wire-frame parsing against a raw loopback socket. */
class SseStreamTest {

    @Test
    void joinsMultiLineDataIntoOneFrameDispatchedOnBlankLine() throws Exception {
        // Bind and dial the same IPv4 literal: `new ServerSocket(0)` binds the IPv6 wildcard, and on
        // macOS `localhost` then reaches another fork's server holding 127.0.0.1 on that port.
        try (var server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5_000);
            var endpoint = URI.create("http://127.0.0.1:" + server.getLocalPort() + "/mcp");
            try (var stream = new SseStream(endpoint, "session-1", null, "2025-11-25")) {
                stream.start();
                try (var accepted = server.accept()) {
                    accepted.setSoTimeout(5_000);
                    consumeRequestHeaders(accepted);
                    var out = accepted.getOutputStream();
                    out.write(("""
                        HTTP/1.1 200 OK\r
                        Content-Type: text/event-stream\r
                        \r
                        id: 1
                        event: mess""").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    Thread.sleep(20); // force the "event:" line to arrive split across two socket reads
                    out.write(("""
                        age
                        data: line one
                        data: line two

                        """).getBytes(StandardCharsets.UTF_8));
                    out.flush();

                    var frame = stream.await(f -> true, Duration.ofSeconds(2));
                    assertThat(frame.id()).isEqualTo("1");
                    assertThat(frame.eventType()).isEqualTo("message");
                    assertThat(frame.data()).isEqualTo("line one\nline two");
                    assertThat(stream.received(f -> true))
                            .as("multi-line data must dispatch as exactly one frame")
                            .hasSize(1);
                }
            }
        }
    }

    private static void consumeRequestHeaders(Socket socket) throws Exception {
        var in = socket.getInputStream();
        var tail = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            tail.append((char) b);
            if (tail.length() > 4) tail.deleteCharAt(0);
            if ("\r\n\r\n".contentEquals(tail)) return;
        }
        throw new AssertionError("connection closed before request headers completed");
    }
}
