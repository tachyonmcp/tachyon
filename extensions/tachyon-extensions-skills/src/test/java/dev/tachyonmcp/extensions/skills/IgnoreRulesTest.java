/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IgnoreRulesTest {

    @Test
    void anchoredDirectoryPatternIgnoresDescendantPaths() {
        var rules = IgnoreRules.parse("/build/");

        assertThat(IgnoreRules.matches("build", rules)).isTrue();
        assertThat(IgnoreRules.matches("build/output.js", rules)).isTrue();
        assertThat(IgnoreRules.matches("build/nested/deep.js", rules)).isTrue();
        assertThat(IgnoreRules.matches("src/build/output.js", rules)).isFalse();
        assertThat(IgnoreRules.matches("buildx/output.js", rules)).isFalse();
    }

    @Test
    void malformedPatternIsSkippedWithoutFailingOtherRules() {
        var rules = IgnoreRules.parse("""
                [unterminated
                *.log
                """);

        assertThat(rules).hasSize(1);
        assertThat(IgnoreRules.matches("app.log", rules)).isTrue();
        assertThat(IgnoreRules.matches("[unterminated", rules)).isFalse();
    }
}
