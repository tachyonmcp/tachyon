/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OwnerTest {

    @Test
    void aLoggedIdIsNeverWholeAndNeverLongerThanEightCharacters() {
        assertThat(Owner.truncated("0123456789abcdef0123456789abcdef")).isEqualTo("01234567…");
        assertThat(Owner.truncated("abcd")).isEqualTo("ab…");
        assertThat(Owner.truncated("a")).isEqualTo("…");
        assertThat(Owner.truncated("")).isEqualTo("…");
    }
}
