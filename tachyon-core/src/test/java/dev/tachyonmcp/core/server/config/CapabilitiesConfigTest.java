/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.config.Mode;
import dev.tachyonmcp.core.server.features.Pagination;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CapabilitiesConfigTest {

    @Test
    void defaultPageSizeIs50() {
        var config = CapabilitiesConfig.DEFAULT;

        assertThat(config.tools().pageSize()).isEqualTo(Pagination.DEFAULT_PAGE_SIZE);
        assertThat(config.resources().pageSize()).isEqualTo(Pagination.DEFAULT_PAGE_SIZE);
        assertThat(config.prompts().pageSize()).isEqualTo(Pagination.DEFAULT_PAGE_SIZE);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositiveToolsPageSize(int size) {
        assertThatThrownBy(
                        () -> CapabilitiesConfig.builder().toolsPageSize(size).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositiveResourcesPageSize(int size) {
        assertThatThrownBy(() ->
                        CapabilitiesConfig.builder().resourcesPageSize(size).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0})
    void rejectsNonPositivePromptsPageSize(int size) {
        assertThatThrownBy(
                        () -> CapabilitiesConfig.builder().promptsPageSize(size).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void preservesPositiveToolsPageSize() {
        var config = CapabilitiesConfig.builder().toolsPageSize(10).build();

        assertThat(config.tools().pageSize()).isEqualTo(10);
    }

    @Test
    void chainedFlatSettersAccumulateOnSameSubConfig() {
        var config = CapabilitiesConfig.builder().tools().toolsPageSize(2).build();

        assertThat(config.tools().mode()).isEqualTo(Mode.ON);
        assertThat(config.tools().pageSize()).isEqualTo(2);
    }
}
