/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.sse;

import dev.tachyonmcp.core.runtime.SseEvent;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import org.jspecify.annotations.Nullable;

/**
 * Serializes an outbound {@link SseEvent} into its {@code text/event-stream} wire framing, writing
 * straight into a pooled buffer from {@code alloc}. Keeps the framing (a transport concern) out of
 * {@link SseEvent} so the {@code mcp.server} model stays free of Netty types, and avoids the
 * intermediate {@code String} a {@code format()} helper would allocate on the hot write path.
 */
public final class SseSerializer {

    private SseSerializer() {}

    /**
     * Encodes {@code event} as {@code id: …\nevent: …\ndata: …\n\n}, splitting multi-line data into
     * one {@code data:} line per {@code \n} (matching the SSE spec). The returned buffer is owned by
     * the caller and must be released after the write completes.
     */
    public static ByteBuf encode(ByteBufAllocator alloc, SseEvent event) {
        return encodeInto(event, PLAIN, alloc.buffer());
    }

    /** Data-line size marker: write with Netty's worst-case UTF-8 reserve. */
    private static final int PLAIN = -2;

    /** Data-line size marker: multi-line data, count each line while writing. */
    private static final int COUNT_LINES = -1;

    /**
     * Measures {@code event}'s exact wire size without encoding it, allocation-free: the high 32
     * bits hold the total length ({@link #measuredLength}), the low 32 the UTF-8 size of a
     * single-line data field (or {@link #COUNT_LINES}). Pass the result to {@link
     * #encode(ByteBufAllocator, SseEvent, long)} so the data is not counted again.
     */
    static long measure(SseEvent event) {
        final var data = event.data();
        final var framing = "id: ".length()
                + ByteBufUtil.utf8Bytes(event.id())
                + 1
                + "event: ".length()
                + ByteBufUtil.utf8Bytes(event.event())
                + 1
                + 1;
        if (data.indexOf('\n') < 0) {
            final var dataBytes = ByteBufUtil.utf8Bytes(data);
            return (long) (framing + "data: ".length() + dataBytes + 1) << 32 | dataBytes;
        }
        var length = framing;
        var start = 0;
        while (true) {
            final var nl = data.indexOf('\n', start);
            final var end = nl < 0 ? data.length() : nl;
            length += "data: ".length() + ByteBufUtil.utf8Bytes(data, start, end) + 1;
            if (nl < 0) break;
            start = nl + 1;
        }
        return (long) length << 32 | (COUNT_LINES & 0xFFFF_FFFFL);
    }

    /** The encoded length of a {@link #measure measured} event. */
    static int measuredLength(long measured) {
        return (int) (measured >>> 32);
    }

    /**
     * Encodes a {@link #measure measured} {@code event} into a buffer of exactly its size, writing
     * each field with its exact UTF-8 size as the reserve: {@link ByteBufUtil#writeUtf8(ByteBuf,
     * CharSequence)} reserves three bytes per char, which would grow the buffer well past what the
     * stream budget counts.
     */
    static ByteBuf encode(ByteBufAllocator alloc, SseEvent event, long measured) {
        return encodeInto(event, (int) measured, alloc.buffer(measuredLength(measured)));
    }

    /**
     * Writes {@code seq[start, end)}. {@code utf8Bytes} is its exact UTF-8 size, {@link
     * #COUNT_LINES} to count it now, or {@link #PLAIN} for Netty's worst-case reserve. An exact
     * reserve must never be derived: Netty's fast path writes it without bounds checks.
     */
    private static void writeUtf8(ByteBuf buf, CharSequence seq, int start, int end, int utf8Bytes) {
        if (utf8Bytes == PLAIN) {
            ByteBufUtil.writeUtf8(buf, seq, start, end);
        } else {
            final var reserve = utf8Bytes >= 0 ? utf8Bytes : ByteBufUtil.utf8Bytes(seq, start, end);
            ByteBufUtil.reserveAndWriteUtf8(buf, seq, start, end, reserve);
        }
    }

    private static ByteBuf encodeInto(SseEvent event, int dataBytes, ByteBuf buf) {
        // Short fields: recounting them is cheaper than carrying their sizes.
        final var fieldBytes = dataBytes == PLAIN ? PLAIN : COUNT_LINES;
        try {
            ByteBufUtil.writeAscii(buf, "id: ");
            writeUtf8(buf, event.id(), 0, event.id().length(), fieldBytes);
            buf.writeByte('\n');
            ByteBufUtil.writeAscii(buf, "event: ");
            writeUtf8(buf, event.event(), 0, event.event().length(), fieldBytes);
            buf.writeByte('\n');
            var data = event.data();
            var start = 0;
            while (true) {
                var nl = data.indexOf('\n', start);
                var end = nl < 0 ? data.length() : nl;
                ByteBufUtil.writeAscii(buf, "data: ");
                writeUtf8(buf, data, start, end, dataBytes);
                buf.writeByte('\n');
                if (nl < 0) break;
                start = nl + 1;
            }
            buf.writeByte('\n');
            return buf;
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
    }

    /**
     * Encodes a raw {@code body} as a {@code message} event, omitting the {@code id:} field when
     * {@code wireId} is {@code null}, with one {@code data:} field per body line. A {@code null} or
     * empty body produces one empty {@code data:} field. Encoding writes straight into a single
     * pooled buffer (no {@code String} decode), sized for a body without line breaks. The returned
     * buffer is owned by the caller and must be released after the write completes.
     */
    public static ByteBuf encode(ByteBufAllocator alloc, @Nullable String wireId, byte @Nullable [] body) {
        final var idBytes = wireId != null ? ByteBufUtil.utf8Bytes(wireId) : 0;
        final var idLength = wireId != null ? "id: ".length() + idBytes + 1 : 0;
        final var dataLength = body != null ? body.length : 0;
        final var buf = alloc.buffer(idLength + "event: message\ndata: \n\n".length() + dataLength);
        try {
            if (wireId != null) {
                ByteBufUtil.writeAscii(buf, "id: ");
                ByteBufUtil.reserveAndWriteUtf8(buf, wireId, idBytes);
                buf.writeByte('\n');
            }
            ByteBufUtil.writeAscii(buf, "event: message\n");
            if (body == null) {
                ByteBufUtil.writeAscii(buf, "data: \n\n");
                return buf;
            }
            var start = 0;
            while (true) {
                var end = start;
                while (end < body.length && body[end] != '\r' && body[end] != '\n') end++;
                ByteBufUtil.writeAscii(buf, "data: ");
                buf.writeBytes(body, start, end - start);
                buf.writeByte('\n');
                if (end == body.length) break;
                if (body[end] == '\r' && end + 1 < body.length && body[end + 1] == '\n') end++;
                start = end + 1;
            }
            buf.writeByte('\n');
            return buf;
        } catch (RuntimeException e) {
            buf.release();
            throw e;
        }
    }
}
