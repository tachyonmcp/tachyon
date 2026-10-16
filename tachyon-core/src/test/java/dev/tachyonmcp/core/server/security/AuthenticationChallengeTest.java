/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * RFC 9110 §11.3: a challenge is a scheme token and auth-params; §5.6.4: a parameter value is sent as
 * a quoted string. A value comes from application code, so it is escaped or refused, never copied.
 */
class AuthenticationChallengeTest {

    @Test
    void aBareSchemeIsTheChallengeItself() {
        assertThat(AuthenticationChallenge.of("ApiKey").headerValue()).isEqualTo("ApiKey");
    }

    @Test
    void parametersAreQuotedAndSeparatedByCommas() {
        assertThat(AuthenticationChallenge.of("Bearer")
                        .withParam("error", "invalid_token")
                        .headerValue())
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(AuthenticationChallenge.of("ApiKey")
                        .withParam("realm", "mcp")
                        .withParam("scope", "read write")
                        .headerValue())
                .isEqualTo("ApiKey realm=\"mcp\", scope=\"read write\"");
    }

    @Test
    void aQuoteAndABackslashAreEscaped() {
        assertThat(AuthenticationChallenge.of("ApiKey")
                        .withParam("realm", "say \"hi\" \\ bye")
                        .headerValue())
                .isEqualTo("ApiKey realm=\"say \\\"hi\\\" \\\\ bye\"");
    }

    @Test
    void addingAParameterLeavesTheChallengeItWasAddedToUntouched() {
        final var bare = AuthenticationChallenge.of("ApiKey");

        final var withRealm = bare.withParam("realm", "mcp");

        assertThat(bare.headerValue()).isEqualTo("ApiKey");
        assertThat(withRealm.headerValue()).isEqualTo("ApiKey realm=\"mcp\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Api Key", "Api:Key", "Api\r\nKey", "Api\"Key", "Ключ", "Api,Key"})
    void aSchemeThatIsNotAnHttpTokenIsRefused(String scheme) {
        assertThatIllegalArgumentException().isThrownBy(() -> AuthenticationChallenge.of(scheme));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "re alm", "realm=", "re\r\nalm", "re\"alm"})
    void aParameterNameThatIsNotAnHttpTokenIsRefused(String name) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AuthenticationChallenge.of("ApiKey").withParam(name, "x"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "line\r\nX-Injected: yes",
                "cr\rhere",
                "lf\nhere",
                "nul\u0000here",
                "del\u007fhere",
                "esc\u001bhere",
                "café",
                "Ключ"
            })
    void aValueThatCannotBeSentInAHeaderIsRefusedAndNeverEchoed(String value) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AuthenticationChallenge.of("ApiKey").withParam("realm", value))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(value));
    }

    @Test
    void aTabIsAllowedInAValue() {
        assertThat(AuthenticationChallenge.of("ApiKey")
                        .withParam("realm", "a\tb")
                        .headerValue())
                .isEqualTo("ApiKey realm=\"a\tb\"");
    }

    @Test
    void aParameterNameIsUniquePerChallengeWhateverItsCase() {
        final var challenge = AuthenticationChallenge.of("ApiKey").withParam("realm", "mcp");

        assertThatIllegalArgumentException().isThrownBy(() -> challenge.withParam("REALM", "other"));
    }
}
