/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OriginsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "localhost",
                "LocalHost",
                "localhost.",
                "127.0.0.1",
                "127.0.0.2",
                "127.255.255.254",
                "[::1]",
                "[0:0:0:0:0:0:0:1]"
            })
    void aLoopbackHostIsRecognizedByNameOrByAddress(String host) {
        assertThat(Origins.isLoopbackHost(host)).isTrue();
    }

    /** An address is parsed, never resolved: a name that merely looks like one is not loopback. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "evil.example",
                "xlocalhost",
                "localhost.evil.example",
                "127.0.0.1.evil.example",
                "127.999.0.1",
                "127.999..",
                "127.",
                "127.0.0",
                "10.0.0.1",
                "0.0.0.0",
                "[::2]",
                "[::1",
                "[fe80::1%eth0]"
            })
    void aHostThatIsNotLoopbackIsNotRecognized(String host) {
        assertThat(Origins.isLoopbackHost(host)).isFalse();
    }
}
