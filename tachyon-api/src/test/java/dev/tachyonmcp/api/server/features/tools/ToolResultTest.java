/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tachyonmcp.api.server.domain.AudioContent;
import dev.tachyonmcp.api.server.domain.ContentBlock;
import dev.tachyonmcp.api.server.domain.ImageContent;
import dev.tachyonmcp.api.server.domain.InputRequest;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.domain.UrlInputRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolResultTest {

    @Test
    void contentWithNoArgsIsEmptySuccess() {
        var r = ToolResult.content();
        assertThat(r).isInstanceOf(ToolResult.Success.class);
        assertThat(((ToolResult.Success) r).content()).isEmpty();
    }

    @Test
    void contentFactoryCarriesImageAndAudioBlocks() {
        var bytes = new byte[] {3, 2, 1};
        var image = ImageContent.of(bytes, "image/png");
        var audio = AudioContent.of(bytes, "audio/wav");
        var result = ToolResult.content(image, audio);

        var copy = bytes.clone();
        assertThat(image).isEqualTo(ImageContent.of(copy, "image/png"));
        assertThat(audio).isEqualTo(AudioContent.of(copy, "audio/wav"));
        assertThat(((ToolResult.Success) result).content()).containsExactly(image, audio);
    }

    @Test
    void structuredWithoutTextHasNoContent() {
        var r = (ToolResult.Success) ToolResult.structured(42);
        assertThat(r.structured()).contains(42);
        assertThat(r.content()).isEmpty();
    }

    @Test
    void structuredWithTextCarriesContent() {
        var r = (ToolResult.Success) ToolResult.structured("data", "custom text");
        assertThat(r.structured()).contains("data");
        assertThat(((TextContent) r.content().getFirst()).text()).isEqualTo("custom text");
    }

    @Test
    void textFactoryProducesTextContentBlock() {
        var r = ToolResult.text("hello");
        assertThat(r).isInstanceOf(ToolResult.Success.class);
        var s = (ToolResult.Success) r;
        assertThat(s.content()).hasSize(1);
        assertThat(((TextContent) s.content().getFirst()).text()).isEqualTo("hello");
        assertThat(s.structured()).isEmpty();
    }

    @Test
    void withMetaMergesNotNests() {
        var base = ToolResult.text("x").withMeta("a", 1);
        var merged = base.withMeta("b", 2);

        assertThat(merged).isInstanceOf(ToolResult.Success.class);
        assertThat(merged.meta()).containsKey("a").containsKey("b");
    }

    @Test
    void withMetaKeyOverridesMerges() {
        var base = ToolResult.text("x").withMeta("k", 1);
        var updated = base.withMeta("k", 99);

        assertThat(updated.meta()).containsEntry("k", 99);
    }

    @Test
    void withMetaEmptyMapReturnsThis() {
        var r = ToolResult.text("x");
        assertThat(r.withMeta(Map.of())).isSameAs(r);
    }

    @Test
    void withMetaImmutability() {
        var source = new HashMap<String, Object>();
        source.put("k", 1);
        var r = ToolResult.text("x").withMeta(source);
        source.put("injected", 99);
        assertThat(r.meta()).doesNotContainKey("injected");
    }

    @Test
    void successContentIsDefensiveCopy() {
        var list = new java.util.ArrayList<ContentBlock>();
        list.add(TextContent.of("a"));
        var r = ToolResult.Success.of(null, list);
        list.add(TextContent.of("b"));
        assertThat(r.content()).hasSize(1);
    }

    @Test
    void errorIsErrorResult() {
        ToolResult err = ToolResult.error("boom");

        assertThat(err).isInstanceOf(ToolResult.Error.class);
        var content = ((ToolResult.Error) err).content();
        assertThat(content).hasSize(1);
        assertThat(((TextContent) content.getFirst()).text()).isEqualTo("boom");
    }

    @Test
    void errorFactoryAcceptsContentBlocks() {
        var image = ImageContent.of(new byte[] {1, 2, 3}, "image/png");
        ToolResult err = ToolResult.error(TextContent.of("boom"), image);

        assertThat(err).isInstanceOf(ToolResult.Error.class);
        assertThat(((ToolResult.Error) err).content()).containsExactly(TextContent.of("boom"), image);
    }

    @Test
    void errorFactoryCarriesStructuredPayloadAndKeepsItAcrossMeta() {
        var payload = Map.of("code", "NOT_FOUND", "id", 7);
        ToolResult err = ToolResult.error(payload, "job 7 not found");

        assertThat(err).isInstanceOf(ToolResult.Error.class);
        var error = (ToolResult.Error) err;
        assertThat(error.structuredValue()).isEqualTo(payload);
        assertThat(error.structured()).contains(payload);
        assertThat(error.content()).containsExactly(TextContent.of("job 7 not found"));

        var withMeta = error.withMeta(Map.of("trace", "id-1"));
        assertThat(withMeta.structuredValue()).isEqualTo(payload);
        assertThat(withMeta.meta()).containsEntry("trace", "id-1");
        assertThat(((ToolResult.Error) ToolResult.error("plain")).structured()).isEmpty();

        var textless = ToolResult.Error.builder().structuredValue(payload).build();
        assertThat(textless.structuredValue()).isEqualTo(payload);
        assertThat(textless.content()).isEmpty();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ToolResult.error(TextContent.of("x"), "text"))
                .withMessageContaining("ContentBlock");
        assertThatIllegalArgumentException().isThrownBy(() -> ToolResult.structured(TextContent.of("x")));

        var singleBlock = (ToolResult.Error) ToolResult.error(TextContent.of("boom"));
        assertThat(singleBlock.structuredValue()).isNull();
        assertThat(singleBlock.content()).containsExactly(TextContent.of("boom"));
    }

    @Test
    void emptyIsSuccessWithoutContent() {
        ToolResult empty = ToolResult.empty();

        assertThat(empty).isInstanceOf(ToolResult.Success.class);
        var success = (ToolResult.Success) empty;
        assertThat(success.structuredValue()).isNull();
        assertThat(success.content()).isEmpty();
    }

    @Test
    void failureCanCarryMeta() {
        ToolResult err = ToolResult.error("oops").withMeta("trace", "id-1");
        assertThat(err).isInstanceOf(ToolResult.Error.class);
        assertThat(err.meta()).containsEntry("trace", "id-1");
    }

    @Test
    void inputRequiredFactory() {
        var req = new LinkedHashMap<String, InputRequest>();
        req.put("field", UrlInputRequest.of("authenticate", "elic-1", "https://example.com/auth"));
        var r = ToolResult.inputRequired(req, "state-1");
        assertThat(r).isInstanceOf(ToolResult.InputRequired.class);
        var ir = (ToolResult.InputRequired) r;
        assertThat(ir.inputRequests()).containsOnlyKeys("field");
        assertThat(ir.requestState()).isEqualTo("state-1");
    }

    @Test
    void inputRequiredWithNullState() {
        var req = Map.<String, InputRequest>of();
        var r = ToolResult.inputRequired(req, null);
        assertThat(r).isInstanceOf(ToolResult.InputRequired.class);
        assertThat(((ToolResult.InputRequired) r).requestState()).isNull();
    }

    @Test
    void inputRequiredWithMetaMerges() {
        var r = ToolResult.inputRequired(Map.of(), "state-1").withMeta("trace", "id-1");
        assertThat(r).isInstanceOf(ToolResult.InputRequired.class);
        assertThat(r.meta()).containsEntry("trace", "id-1");
        assertThat(((ToolResult.InputRequired) r).requestState()).isEqualTo("state-1");
    }
}
