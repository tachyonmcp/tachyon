/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.apps;

import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.core.protocol.codec.CodecSupport;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.extensions.apps.AppMeta.Permission;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class AppsExtensionTest {

    private static final AppResource VIEW = AppResource.builder()
            .uri("ui://a/b")
            .name("view")
            .html("<html></html>")
            .build();

    @Test
    void rejectsNonUiScheme() {
        assertThatThrownBy(() -> AppResource.builder()
                        .uri("https://example.com/view")
                        .name("view")
                        .html("<html></html>")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ui://");
        assertThatThrownBy(() -> AppsExtension.toolMeta("https://example.com/view"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ui://");
    }

    @Test
    void rejectsBlankNameAndMissingHtml() {
        assertThatThrownBy(() -> AppResource.builder()
                        .uri("ui://a/b")
                        .name(" ")
                        .html("<html></html>")
                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
        assertThatThrownBy(
                        () -> AppResource.builder().uri("ui://a/b").name("view").build())
                .isInstanceOf(NullPointerException.class)
                .hasMessage("html");
    }

    @Test
    void rejectsDuplicateUriAtConfiguration() {
        assertThatThrownBy(() -> TachyonServer.builder()
                        .withExtension(
                                AppsExtension.class, apps -> apps.app(VIEW).app(VIEW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate UI resource: ui://a/b");
    }

    @Test
    void exposesIdentityAndApps() {
        try (var server = TachyonServer.builder()
                .withExtension(AppsExtension.class, apps -> apps.app(VIEW))
                .build()) {
            var extension = server.extension(AppsExtension.class).orElseThrow();
            assertThat(extension.extensionId()).isEqualTo("io.modelcontextprotocol/ui");
            assertThat(extension.serverSettings().values().asMap()).isEmpty();
            assertThat(extension.apps()).containsExactly(VIEW);
            assertThat(server.resources().findByUri("ui://a/b")).get().satisfies(descriptor -> {
                assertThat(descriptor.mimeType()).isEqualTo(AppsExtension.MIME_TYPE);
                assertThat(descriptor.meta()).isNullOrEmpty();
            });
        }
    }

    @Test
    void toolMetaDedupesVisibilityKeepingOrder() {
        var meta =
                AppsExtension.toolMeta("ui://a/b", UiToolVisibility.APP, UiToolVisibility.MODEL, UiToolVisibility.APP);
        assertThat(meta)
                .containsOnlyKeys("ui")
                .containsEntry("ui", Map.of("resourceUri", "ui://a/b", "visibility", List.of("app", "model")));
        assertThat(AppsExtension.toolMeta("ui://a/b"))
                .containsOnlyKeys("ui")
                .containsEntry("ui", Map.of("resourceUri", "ui://a/b"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toolMetaIsImmutable() {
        var meta = AppsExtension.toolMeta("ui://a/b", UiToolVisibility.APP);
        var ui = (Map<String, Object>) meta.get("ui");
        assertThatThrownBy(() -> meta.put("other", "x")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ui.put("resourceUri", "https://evil.example"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<Object>) ui.get("visibility")).add("model"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void metaOmitsEmptyCspAndPermissions() {
        assertThat(AppMeta.NONE.toMeta()).isEmpty();

        var borderOnly = AppMeta.builder().prefersBorder(false).build().toMeta();
        assertThat(borderOnly).containsOnlyKeys("ui");
        // language=JSON
        assertThatJson(json(borderOnly.get("ui"))).isEqualTo("""
            {"prefersBorder":false}
            """);

        var connectAndCamera = AppMeta.builder()
                .connectDomains("https://api.example.com")
                .permissions(Permission.CAMERA, Permission.CAMERA)
                .build()
                .toMeta();
        // language=JSON
        assertThatJson(json(connectAndCamera.get("ui"))).isEqualTo("""
            {"csp":{"connectDomains":["https://api.example.com"]},"permissions":{"camera":{}}}
            """);
    }

    private static String json(Object ui) {
        return new String(
                CodecSupport.writeToBytes(gen -> CodecSupport.writeTree(gen, (JsonNode) ui)), StandardCharsets.UTF_8);
    }
}
