/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpHelpersTest {

    /** RFC 9110 §5.6.2: {@code token = 1*tchar}. */
    @ParameterizedTest
    @ValueSource(strings = {"a", "ApiKey", "x-mcp-header", "A1", "!#$%&'*+-.^_`|~"})
    void aTokenIsOneOrMoreTcharacters(String text) {
        assertThat(HttpHelpers.isToken(text)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "two words",
                "tab\tbed",
                "quote\"d",
                "a:b",
                "a,b",
                "a;b",
                "a=b",
                "(a)",
                "a/b",
                "café",
                "line\nbreak"
            })
    void anythingElseIsNotAToken(String text) {
        assertThat(HttpHelpers.isToken(text)).isFalse();
    }

    @Test
    void emptyIsNotAToken() {
        assertThat(HttpHelpers.isToken("")).isFalse();
    }
}
