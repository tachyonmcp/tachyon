/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies {@link RuntimeConfig} defaults and builder null rejection.
 *
 * @author Konstantin Pavlov
 */
class RuntimeConfigTest {

    @Test
    void usesDefaults() {
        var config = RuntimeConfig.builder().build();

        assertThat(config.shutdownGracePeriod()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.retryAfter()).as("no Retry-After unless configured").isNull();
        assertThat(RuntimeConfig.DEFAULT.retryAfter()).isNull();
    }

    @Test
    void acceptsARetryAfterFromOneSecondToOneHour() {
        assertThat(RuntimeConfig.builder()
                        .retryAfter(Duration.ofSeconds(1))
                        .build()
                        .retryAfter())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(RuntimeConfig.builder()
                        .retryAfter(Duration.ofHours(1))
                        .build()
                        .retryAfter())
                .isEqualTo(Duration.ofHours(1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "PT-5S", "PT1.5S", "PT61M"})
    void rejectsARetryAfterThatIsNotWholeSecondsFromOneSecondToOneHour(String invalid) {
        final var retryAfter = Duration.parse(invalid);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> RuntimeConfig.builder().retryAfter(retryAfter).build())
                .withMessageContaining("retryAfter");
    }

    @Test
    void buildsWithCustomValues() {
        var config = RuntimeConfig.builder()
                .shutdownGracePeriod(Duration.ofSeconds(10))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        assertThat(config.shutdownGracePeriod()).isEqualTo(Duration.ofSeconds(10));
        assertThat(config.requestTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void rejectsNullShutdownGracePeriod() {
        assertThatNullPointerException()
                .isThrownBy(() -> RuntimeConfig.builder().shutdownGracePeriod(null));
    }

    @Test
    void rejectsNullRequestTimeout() {
        assertThatNullPointerException()
                .isThrownBy(() -> RuntimeConfig.builder().requestTimeout(null));
    }
}
