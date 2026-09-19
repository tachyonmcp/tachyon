/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * The configuration processor is the only thing that gives users completion and documentation for
 * {@code tachyon.*}, and it fails silently: drop the processor from the build and the metadata
 * simply disappears from the jar.
 */
class TachyonConfigurationMetadataTest {

    private static final Path METADATA = Path.of("target/classes/META-INF/spring-configuration-metadata.json");

    @Test
    void describesEveryTachyonProperty() throws Exception {
        assertThat(METADATA)
                .as("spring-boot-configuration-processor must run during compilation")
                .isRegularFile();

        final Map<String, Object> metadata = new ObjectMapper().readValue(Files.readAllBytes(METADATA), Map.class);

        @SuppressWarnings("unchecked")
        final var groups = (List<Map<String, Object>>) metadata.get("groups");
        assertThat(groups).extracting(group -> group.get("name")).contains("tachyon");

        @SuppressWarnings("unchecked")
        final var properties = (List<Map<String, Object>>) metadata.get("properties");
        assertThat(properties)
                .extracting(property -> property.get("name"))
                .containsExactlyInAnyOrder(
                        "tachyon.enabled", "tachyon.name", "tachyon.version", "tachyon.host", "tachyon.port");

        final var port = properties.stream()
                .filter(property -> "tachyon.port".equals(property.get("name")))
                .findFirst()
                .orElseThrow();
        assertThat(port.get("defaultValue")).isEqualTo(8080);
        assertThat((String) port.get("description"))
                .as("the port collides with server.port, which the description must warn about")
                .contains("server.port");

        assertThat(properties)
                .allSatisfy(property -> assertThat((String) property.get("description"))
                        .as("%s needs completion text", property.get("name"))
                        .isNotBlank());
    }
}
