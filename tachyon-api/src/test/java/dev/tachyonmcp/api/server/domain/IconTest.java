/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.Test;

class IconTest {

    @Test
    void binaryIconEncodesBytesAndPreservesMetadata() {
        final var icon = Icon.of(new byte[] {0, 1, -1}, "image/png", List.of("16x16"), "dark");

        assertThat(icon.src()).isEqualTo("data:image/png;base64,AAH/");
        assertThat(icon.mimeType()).isEqualTo("image/png");
        assertThat(icon.sizes()).containsExactly("16x16");
        assertThat(icon.theme()).isEqualTo("dark");
    }

    @Test
    void binaryIconRejectsEmptyDataAndBlankMimeType() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Icon.of(new byte[0], "image/png", List.of(), null))
                .withMessage("data must not be empty");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Icon.of(new byte[] {1}, " ", List.of(), null))
                .withMessage("mimeType must not be blank");
    }
}
