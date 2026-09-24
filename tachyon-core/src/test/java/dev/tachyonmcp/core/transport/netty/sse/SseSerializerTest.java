/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.runtime.SseEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SseSerializerTest {

    @Test
    void encodesSingleLineEvent() {
        assertThat(encode(new SseEvent("42", "response", "{\"ok\":true}")))
                .isEqualTo("id: 42\nevent: response\ndata: {\"ok\":true}\n\n");
    }

    @Test
    void encodesMultiLineDataAsSeparateDataLines() {
        assertThat(encode(new SseEvent("1", "message", "line1\nline2")))
                .isEqualTo("id: 1\nevent: message\ndata: line1\ndata: line2\n\n");
    }

    @Test
    void encodesEmptyData() {
        assertThat(encode(new SseEvent("7", "message", ""))).isEqualTo("id: 7\nevent: message\ndata: \n\n");
    }

    @Test
    void encodesRawBodyWithLfAsSeparateDataLines() {
        assertThat(encode("8", "line1\nline2")).isEqualTo("id: 8\nevent: message\ndata: line1\ndata: line2\n\n");
    }

    @Test
    void encodesRawBodyWithCrLfAsSeparateDataLines() {
        assertThat(encode("9", "line1\r\nline2")).isEqualTo("id: 9\nevent: message\ndata: line1\ndata: line2\n\n");
    }

    @Test
    void encodesRawBodyWithoutId() {
        assertThat(encode(null, "body")).isEqualTo("event: message\ndata: body\n\n");
    }

    @Test
    void encodesNullRawBodyAsEmptyData() {
        assertThat(encode("10", null)).isEqualTo("id: 10\nevent: message\ndata: \n\n");
    }

    @Test
    void encodesEmptyRawBodyAsEmptyData() {
        assertThat(encode("11", "")).isEqualTo("id: 11\nevent: message\ndata: \n\n");
    }

    /** Budget accounting reserves the measured length before encoding, so it must match exactly. */
    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{\"ok\":true}",
                "line1\nline2",
                "trailing\n",
                "\n\n",
                "crlf\r\nline",
                "cr\ronly",
                "caf\u00e9 \u65e5\u672c",
                "emoji \ud83d\ude80",
                "lone \ud83d surrogate",
                "a\nb\nc\nd\ne"
            })
    void measuredEncodingMatchesPlainEncodingInAnExactlySizedBuffer(String text) {
        // Unpooled: capacity is exactly what was asked for, so growth would show.
        var alloc = new UnpooledByteBufAllocator(false);
        var event = new SseEvent("12#\u00e9", "m\u00e9ssage", text);
        var measured = SseSerializer.measure(event);
        var plain = SseSerializer.encode(alloc, event);
        var sized = SseSerializer.encode(alloc, event, measured);
        try {
            assertThat(SseSerializer.measuredLength(measured)).isEqualTo(plain.readableBytes());
            assertThat(sized).isEqualTo(plain);
            assertThat(sized.capacity())
                    .as("measured sizes are the UTF-8 reserves: no worst-case growth while encoding")
                    .isEqualTo(sized.readableBytes());
        } finally {
            plain.release();
            sized.release();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{\"ok\":true}", "caf\u00e9 \u65e5\u672c"})
    void rawBodyWithoutLineBreaksIsEncodedIntoAnExactlySizedBuffer(String text) {
        var buf = SseSerializer.encode(
                new UnpooledByteBufAllocator(false), "3#\u00e9", text.getBytes(StandardCharsets.UTF_8));
        try {
            assertThat(buf.toString(StandardCharsets.UTF_8))
                    .isEqualTo("id: 3#\u00e9\nevent: message\ndata: " + text + "\n\n");
            assertThat(buf.capacity()).isEqualTo(buf.readableBytes());
        } finally {
            buf.release();
        }
    }

    private static String encode(SseEvent event) {
        ByteBuf buf = SseSerializer.encode(ByteBufAllocator.DEFAULT, event);
        try {
            return buf.toString(StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }

    private static String encode(@Nullable String wireId, @Nullable String body) {
        ByteBuf buf = SseSerializer.encode(
                ByteBufAllocator.DEFAULT, wireId, body == null ? null : body.getBytes(StandardCharsets.UTF_8));
        try {
            return buf.toString(StandardCharsets.UTF_8);
        } finally {
            buf.release();
        }
    }
}
