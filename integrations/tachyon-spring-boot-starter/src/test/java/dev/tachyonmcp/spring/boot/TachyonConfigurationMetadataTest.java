/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.config.RuntimeConfig;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.ObjectMapper;

/**
 * The configuration processor is the only thing that gives users completion and documentation for
 * {@code tachyon.*}, and it fails silently: drop the processor from the build and the metadata
 * simply disappears from the jar.
 */
class TachyonConfigurationMetadataTest {

    private static final Path METADATA = Path.of("target/classes/META-INF/spring-configuration-metadata.json");
    private static final Path AUTOCONFIGURE_METADATA =
            Path.of("target/classes/META-INF/spring-autoconfigure-metadata.properties");

    private static Map<String, Object> metadata() throws Exception {
        assertThat(METADATA)
                .as("spring-boot-configuration-processor must run during compilation")
                .isRegularFile();
        return new ObjectMapper().readValue(Files.readAllBytes(METADATA), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> properties() throws Exception {
        return (List<Map<String, Object>>) metadata().get("properties");
    }

    private static Map<String, Object> property(String name) throws Exception {
        return properties().stream()
                .filter(property -> name.equals(property.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no metadata for " + name));
    }

    @Test
    void describesEveryTachyonProperty() throws Exception {
        @SuppressWarnings("unchecked")
        final var groups = (List<Map<String, Object>>) metadata().get("groups");
        assertThat(groups)
                .extracting(group -> group.get("name"))
                .contains("tachyon", "tachyon.network", "tachyon.session", "tachyon.runtime");

        assertThat(properties())
                .extracting(property -> property.get("name"))
                .containsExactlyInAnyOrder(
                        "tachyon.enabled",
                        "tachyon.name",
                        "tachyon.version",
                        "tachyon.host",
                        "tachyon.port",
                        "tachyon.network.endpoint-path",
                        "tachyon.network.reader-idle-timeout",
                        "tachyon.network.writer-idle-timeout",
                        "tachyon.network.heartbeat-interval",
                        "tachyon.network.max-content-length",
                        "tachyon.network.allowed-origins",
                        "tachyon.network.allowed-headers",
                        "tachyon.network.allowed-hosts",
                        "tachyon.network.allow-private-networks",
                        "tachyon.network.io-engine",
                        "tachyon.session.enabled",
                        "tachyon.session.session-ttl",
                        "tachyon.session.janitor-interval",
                        "tachyon.runtime.shutdown-grace-period",
                        "tachyon.runtime.request-timeout");

        assertThat((String) property("tachyon.port").get("description"))
                .as("the port collides with server.port, which the description must warn about")
                .contains("server.port");
    }

    /**
     * Spring Boot's own conventions for starter property descriptions. Descriptions go into the JSON
     * verbatim — no Markdown is rendered — and IDEs show them as the only documentation a user gets.
     */
    @Test
    void followsSpringBootDescriptionConventions() throws Exception {
        assertThat(properties()).allSatisfy(property -> {
            final var name = (String) property.get("name");
            final var description = (String) property.get("description");
            final var type = (String) property.get("type");

            assertThat(description).as("%s needs completion text", name).isNotBlank();
            assertThat(description)
                    .as("%s must not open with an article", name)
                    .doesNotStartWith("The ")
                    .doesNotStartWith("A ");
            assertThat(description)
                    .as("%s: descriptions are plain text, markup leaks into IDE tooltips", name)
                    .doesNotContain("`")
                    .doesNotContain("{@")
                    .doesNotContain("<p>");
            assertThat(description).as("%s needs a full sentence", name).endsWith(".");

            if ("java.lang.Boolean".equals(type)) {
                assertThat(description)
                        .as("%s is a boolean, so its description starts with Whether or Enable", name)
                        .matches("^(Whether|Enable)\\b.*");
            }
            if ("java.time.Duration".equals(type)) {
                assertThat(description)
                        .as("%s is a Duration, so its description names the unit assumed without a suffix", name)
                        .contains("duration suffix");
            }
        });
    }

    /**
     * Every documented default is a copy of a value Tachyon owns. Without this the JSON silently
     * drifts the moment a core default changes, and the IDE starts lying to users. Documented values
     * are compared after parsing them the way Boot binds them, so the spelling stays free.
     */
    @ParameterizedTest
    @MethodSource("documentedDefaults")
    void documentedDefaultMatchesTheValueTachyonActuallyUses(String name, Object expected) throws Exception {
        final var documented = property(name).get("defaultValue");
        assertThat(documented).as("%s has no documented default", name).isNotNull();

        final var bound =
                switch (expected) {
                    case Duration ignored -> DurationStyle.detectAndParse((String) documented);
                    case DataSize ignored -> DataSize.parse((String) documented);
                    default -> documented;
                };
        assertThat(bound).as("%s", name).isEqualTo(expected);
    }

    static Stream<Arguments> documentedDefaults() {
        return Stream.of(
                Arguments.of("tachyon.host", NetworkConfig.DEFAULT_HOST),
                Arguments.of("tachyon.network.endpoint-path", NetworkConfig.DEFAULT_ENDPOINT_PATH),
                Arguments.of("tachyon.network.reader-idle-timeout", NetworkConfig.DEFAULT_READER_IDLE_TIMEOUT),
                Arguments.of("tachyon.network.writer-idle-timeout", NetworkConfig.DEFAULT_WRITER_IDLE_TIMEOUT),
                Arguments.of("tachyon.network.heartbeat-interval", NetworkConfig.DEFAULT_HEARTBEAT_INTERVAL),
                Arguments.of(
                        "tachyon.network.max-content-length",
                        DataSize.ofBytes(McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH)),
                Arguments.of(
                        "tachyon.network.io-engine", NettyIoEngine.AUTO.name().toLowerCase(Locale.ROOT)),
                Arguments.of("tachyon.session.session-ttl", SessionConfig.DEFAULT_SESSION_TTL),
                Arguments.of("tachyon.session.janitor-interval", SessionConfig.DEFAULT_JANITOR_INTERVAL),
                Arguments.of("tachyon.runtime.shutdown-grace-period", RuntimeConfig.DEFAULT.shutdownGracePeriod()),
                Arguments.of("tachyon.runtime.request-timeout", RuntimeConfig.DEFAULT.requestTimeout()));
    }

    /**
     * Without this file Boot cannot evaluate the auto-configuration's conditions before loading the
     * class, so every application pays for loading it even when Tachyon is switched off. It is
     * generated by a second annotation processor that fails just as silently as the first.
     */
    @Test
    void publishesAutoConfigurationConditionMetadata() throws Exception {
        assertThat(AUTOCONFIGURE_METADATA)
                .as("spring-boot-autoconfigure-processor must run during compilation")
                .isRegularFile();
        assertThat(Files.readString(AUTOCONFIGURE_METADATA))
                .contains("dev.tachyonmcp.spring.boot.TachyonAutoConfiguration=")
                .contains("MetricsConfiguration.ConditionalOnClass=io.micrometer.core.instrument.MeterRegistry");
    }
}
