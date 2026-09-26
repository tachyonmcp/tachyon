/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.v2025_11_25.codecs;

import static dev.tachyonmcp.core.test.TestUtils.properties;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.server.domain.AudioContent;
import dev.tachyonmcp.api.server.domain.BlobResourceContents;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.EmbeddedResource;
import dev.tachyonmcp.api.server.domain.Icon;
import dev.tachyonmcp.api.server.domain.ImageContent;
import dev.tachyonmcp.api.server.domain.ResourceLink;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.TextResourceContents;
import dev.tachyonmcp.api.server.domain.ToolAnnotations;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

class McpToolMapperTest {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    private static final String META_VALUE = "v1";
    private static final JsonNode PROTOCOL_META_VALUE = JSON.stringNode(META_VALUE);
    private static final byte[] DATA_BYTES = "data".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    @Test
    void toProtocolTextContentCarriesCorrectType() {
        var domain = TextContent.of("hello");
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(protocol.type()).isEqualTo("text");
        assertThat(protocol.text()).isEqualTo("hello");
    }

    @Test
    void toProtocolImageContentCarriesCorrectType() {
        var domain = ImageContent.of(DATA_BYTES, "image/png");
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ImageContent)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(protocol.type()).isEqualTo("image");
        assertThat(protocol.data()).isEqualTo(DATA_BYTES);
        assertThat(protocol.mimeType()).isEqualTo("image/png");
    }

    @Test
    void toProtocolAudioContentCarriesCorrectType() {
        var domain = AudioContent.of(DATA_BYTES, "audio/wav");
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.AudioContent)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(protocol.type()).isEqualTo("audio");
        assertThat(protocol.data()).isEqualTo(DATA_BYTES);
        assertThat(protocol.mimeType()).isEqualTo("audio/wav");
    }

    @Test
    void toProtocolResourceLinkCarriesCorrectType() {
        var domain = ResourceLink.of("test://resource", "My Resource");
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceLink)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(protocol.type()).isEqualTo("resource_link");
        assertThat(protocol.uri()).isEqualTo("test://resource");
        assertThat(protocol.name()).isEqualTo("My Resource");
    }

    @Test
    void toProtocolEmbeddedResourceCarriesCorrectType() {
        var contents = TextResourceContents.of("test://embedded", "content", "text/plain");
        var domain = EmbeddedResource.of(contents);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmbeddedResource)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(protocol.type()).isEqualTo("resource");
    }

    @Test
    void metaSurvivesTextContentRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var domain = TextContent.of("hello", meta, null);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (TextContent) McpToolMapper.toDomainContentBlock(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void metaSurvivesImageContentRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var domain = ImageContent.of(DATA_BYTES, "image/png", null, meta);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ImageContent)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (ImageContent) McpToolMapper.toDomainContentBlock(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void metaSurvivesAudioContentRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var domain = AudioContent.of(DATA_BYTES, "audio/wav", null, meta);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.AudioContent)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (AudioContent) McpToolMapper.toDomainContentBlock(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void metaSurvivesResourceLinkRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var domain = ResourceLink.builder("test://resource", "My Resource")
                .meta(meta)
                .build();
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceLink)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (ResourceLink) McpToolMapper.toDomainContentBlock(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void metaSurvivesEmbeddedResourceRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var contents = TextResourceContents.of("test://embedded", "content", "text/plain");
        var domain = EmbeddedResource.of(contents, null, meta);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmbeddedResource)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (EmbeddedResource) McpToolMapper.toDomainContentBlock(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void metaSurvivesTextResourceContentsRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var domain = TextResourceContents.of("test://uri", "content", "text/plain", meta);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextResourceContents)
                McpToolMapper.toProtocolResourceContents(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (TextResourceContents) McpToolMapper.toDomainResourceContents(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void metaSurvivesBlobResourceContentsRoundTrip() {
        var meta = Map.<String, Object>of("key", META_VALUE);
        var domain = BlobResourceContents.of("test://blob", DATA_BYTES, "application/octet-stream", meta);
        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.BlobResourceContents)
                McpToolMapper.toProtocolResourceContents(domain);
        assertThat(properties(protocol._meta())).containsEntry("key", PROTOCOL_META_VALUE);

        var back = (BlobResourceContents) McpToolMapper.toDomainResourceContents(protocol);
        assertThat(back.meta()).containsEntry("key", META_VALUE);
    }

    @Test
    void toolAnnotationsTitlePassesThrough() {
        var domain = ToolAnnotations.of("My Title", true, false, true, false);
        var protocol = McpToolMapper.toProtocolToolAnnotations(domain);
        assertThat(protocol.title()).isEqualTo("My Title");
        assertThat(protocol.readOnlyHint()).isTrue();
        assertThat(protocol.destructiveHint()).isFalse();
        assertThat(protocol.idempotentHint()).isTrue();
        assertThat(protocol.openWorldHint()).isFalse();
    }

    @Test
    void toProtocolToolAnnotationsReturnsNullForNullInput() {
        assertThat(McpToolMapper.toProtocolToolAnnotations(null)).isNull();
    }

    @Test
    void resourceLinkIconsAndSizeSurviveRoundTrip() {
        var domainIcon = Icon.of("https://example.com/icon.png", "image/png", List.of("16x16", "32x32"), "light");
        var domain = ResourceLink.builder("test://resource", "My Resource")
                .title("My Title")
                .icons(List.of(domainIcon))
                .description("A description")
                .mimeType("text/plain")
                .size(2048L)
                .build();

        var protocol = (dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceLink)
                McpToolMapper.toProtocolContentBlock(domain);
        assertThat(protocol.icons()).hasSize(1);
        assertThat(protocol.icons().getFirst().src()).isEqualTo("https://example.com/icon.png");
        assertThat(protocol.icons().getFirst().mimeType()).isEqualTo("image/png");
        assertThat(protocol.icons().getFirst().sizes()).containsExactly("16x16", "32x32");
        assertThat(protocol.icons().getFirst().theme()).isEqualTo("light");
        assertThat(protocol.title()).isEqualTo("My Title");
        assertThat(protocol.size()).isEqualTo(2048L);

        var back = (ResourceLink) McpToolMapper.toDomainContentBlock(protocol);
        assertThat(back.icons()).hasSize(1);
        assertThat(back.icons().getFirst().src()).isEqualTo("https://example.com/icon.png");
        assertThat(back.icons().getFirst().sizes()).containsExactly("16x16", "32x32");
        assertThat(back.size()).isEqualTo(2048L);
        assertThat(back.title()).isEqualTo("My Title");
    }

    @Test
    void toDomainIconsConvertsCorrectly() {
        var protocolIcon = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Icon(
                "https://example.com/icon.png", "image/png", List.of("16x16"), "dark");
        var domainIcons = McpToolMapper.toDomainIcons(List.of(protocolIcon));
        assertThat(domainIcons).hasSize(1);
        assertThat(domainIcons.getFirst().src()).isEqualTo("https://example.com/icon.png");
        assertThat(domainIcons.getFirst().sizes()).containsExactly("16x16");
    }

    @Test
    void toProtocolIconsConvertsCorrectly() {
        var domainIcon = Icon.of("https://example.com/icon.png", "image/png", List.of("32x32"), "light");
        var protocolIcons = McpToolMapper.toProtocolIcons(List.of(domainIcon));
        assertThat(protocolIcons).hasSize(1);
        assertThat(protocolIcons.getFirst().src()).isEqualTo("https://example.com/icon.png");
        assertThat(protocolIcons.getFirst().sizes()).containsExactly("32x32");
    }

    @Test
    void toDomainIconsReturnsEmptyForNullInput() {
        assertThat(McpToolMapper.toDomainIcons(null)).isEmpty();
    }

    @Test
    void toProtocolIconsReturnsNullForEmptyInput() {
        assertThat(McpToolMapper.toProtocolIcons(List.of())).isNull();
    }

    @Test
    void toProtocolIconsOmitsSizesWhenEmpty() {
        var domainIcon = Icon.of("https://example.com/icon.png", "image/png", List.of(), null);
        var protocolIcons = McpToolMapper.toProtocolIcons(List.of(domainIcon));
        assertThat(protocolIcons.getFirst().sizes()).isNull();
    }

    @Test
    void toDomainAnnotationsConvertsAbsentAudienceToEmptyList() {
        var protocol = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.Annotations(null, 0.5, null);
        var domain = McpToolMapper.toDomainAnnotations(protocol);
        assertThat(domain.audience()).isEmpty();
        assertThat(domain.priority()).isEqualTo(0.5);
    }

    @Test
    void toDomainContentBlockConvertsTextContent() {
        var protocol = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextContent("text", "hello", null, null);
        var domain = McpToolMapper.toDomainContentBlock(protocol);
        assertThat(domain).isInstanceOf(TextContent.class);
        assertThat(((TextContent) domain).text()).isEqualTo("hello");
        assertThat(domain.type()).isEqualTo(ContentBlock.Type.TEXT);
    }

    @Test
    void toDomainContentBlockConvertsImageContent() {
        var protocol = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ImageContent(
                "image", DATA_BYTES, "image/png", null, null);
        var domain = McpToolMapper.toDomainContentBlock(protocol);
        assertThat(domain).isInstanceOf(ImageContent.class);
        assertThat(((ImageContent) domain).data()).isEqualTo(DATA_BYTES);
        assertThat(domain.type()).isEqualTo(ContentBlock.Type.IMAGE);
    }

    @Test
    void toDomainContentBlockConvertsAudioContent() {
        var protocol = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.AudioContent(
                "audio", DATA_BYTES, "audio/wav", null, null);
        var domain = McpToolMapper.toDomainContentBlock(protocol);
        assertThat(domain).isInstanceOf(AudioContent.class);
        assertThat(((AudioContent) domain).data()).isEqualTo(DATA_BYTES);
        assertThat(domain.type()).isEqualTo(ContentBlock.Type.AUDIO);
    }

    @Test
    void toDomainContentBlockConvertsResourceLink() {
        var protocol = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ResourceLink(
                "resource_link", "My Resource", null, null, "test://resource", null, null, null, null, null);
        var domain = McpToolMapper.toDomainContentBlock(protocol);
        assertThat(domain).isInstanceOf(ResourceLink.class);
        assertThat(((ResourceLink) domain).uri()).isEqualTo("test://resource");
        assertThat(domain.type()).isEqualTo(ContentBlock.Type.RESOURCE_LINK);
    }

    @Test
    void toDomainContentBlockConvertsEmbeddedResource() {
        var protocolContents = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.TextResourceContents(
                "content", "test://embedded", "text/plain", null);
        var protocol = new dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.EmbeddedResource(
                "resource", protocolContents, null, null);
        var domain = McpToolMapper.toDomainContentBlock(protocol);
        assertThat(domain).isInstanceOf(EmbeddedResource.class);
        assertThat(domain.type()).isEqualTo(ContentBlock.Type.RESOURCE);
    }

    @Test
    void toDomainContentBlockReturnsNullForNullInput() {
        assertThat(McpToolMapper.toDomainContentBlock(null)).isNull();
    }

    @Test
    void toDomainResultPreservesToolResult() {
        var original = ToolResult.content(TextContent.of("hello"));
        var result = McpToolMapper.toDomainResult(original);
        assertThat(result).isSameAs(original);
    }

    @Test
    void toDomainResultConvertsObject() {
        var result = (ToolResult.Success) McpToolMapper.toDomainResult("test");
        var content = result.content();
        assertThat(content).hasSize(1);
        assertThat(((TextContent) content.getFirst()).text()).isEqualTo("test");
    }

    @Test
    void typeDerivesCorrectlyForAllVariants() {
        assertThat(TextContent.of("hello").type()).isEqualTo(ContentBlock.Type.TEXT);
        assertThat(ImageContent.of(DATA_BYTES, "image/png").type()).isEqualTo(ContentBlock.Type.IMAGE);
        assertThat(AudioContent.of(DATA_BYTES, "audio/wav").type()).isEqualTo(ContentBlock.Type.AUDIO);
        assertThat(ResourceLink.of("test://uri", "name").type()).isEqualTo(ContentBlock.Type.RESOURCE_LINK);
        assertThat(EmbeddedResource.of(TextResourceContents.of("u", "c", null)).type())
                .isEqualTo(ContentBlock.Type.RESOURCE);
    }
}
