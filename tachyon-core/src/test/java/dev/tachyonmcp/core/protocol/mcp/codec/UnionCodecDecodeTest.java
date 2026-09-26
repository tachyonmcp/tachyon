/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol.mcp.codec;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.CodecRegistry;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.BlobResourceContents;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ContentBlock;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ImageContent;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ResourceContents;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Union codecs pick the variant from the object's own properties, independent of property order
 * and of same-named properties nested deeper in the object.
 */
class UnionCodecDecodeTest {

    @Test
    void discriminatorIsReadFromItsOwnValueNotTheNextPropertyName() {
        var json = "{\"type\":\"image\",\"data\":\"AAE=\",\"mimeType\":\"image/png\"}";

        var block = CodecRegistry.codecFor(ContentBlock.class).decodeFromBytes(bytes(json));

        assertThat(block).isInstanceOfSatisfying(ImageContent.class, image -> {
            assertThat(image.mimeType()).isEqualTo("image/png");
            assertThat(image.data()).containsExactly(0, 1);
        });
    }

    @Test
    void discriminatorAfterNestedObjectIsFound() {
        var json = "{\"_meta\":{\"type\":\"text\"},\"mimeType\":\"image/png\",\"data\":\"AAE=\",\"type\":\"image\"}";

        var block = CodecRegistry.codecFor(ContentBlock.class).decodeFromBytes(bytes(json));

        assertThat(block).isInstanceOf(ImageContent.class);
    }

    @Test
    void deductionIgnoresVariantPropertiesNestedInMeta() {
        var json = "{\"uri\":\"file:///a.bin\",\"_meta\":{\"text\":\"decoy\"},\"blob\":\"AAE=\"}";

        var contents = CodecRegistry.codecFor(ResourceContents.class).decodeFromBytes(bytes(json));

        assertThat(contents).isInstanceOfSatisfying(BlobResourceContents.class, blob -> {
            assertThat(blob.uri()).isEqualTo("file:///a.bin");
            assertThat(blob.blob()).containsExactly(0, 1);
            assertThat(blob._meta()).containsOnlyKeys("text");
        });
    }

    private static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
