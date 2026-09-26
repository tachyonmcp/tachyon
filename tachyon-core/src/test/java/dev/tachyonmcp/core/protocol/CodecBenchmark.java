/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.protocol;

import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.codecs.CodecRegistry;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.CallToolResult;
import dev.tachyonmcp.core.protocol.mcp.v2026_07_28.models.ContentBlock;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcCodec;
import dev.tachyonmcp.core.transport.jsonrpc.JsonRpcMessage;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import tools.jackson.databind.JsonNode;

/**
 * Wire hot paths shared by every request: generated codecs (a result with a content union,
 * {@code _meta} and a structured tree), JSON-RPC request parsing, and the generic value writer.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class CodecBenchmark {

    // language=json
    private static final String RESULT = """
            {"content":[{"type":"text","text":"It is 21.5 C and sunny in Berlin."},
              {"type":"image","data":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==","mimeType":"image/png"}],
             "structuredContent":{"temperature":21.5,"unit":"C","conditions":["sunny","dry"],"station":{"id":10385,"name":"Berlin"}},
             "_meta":{"progressToken":"req-42","io.modelcontextprotocol/related-task":{"taskId":"786512e2-9e0d-44bd-8f29-789f320fe840"}},
             "resultType":"complete"}""";

    // language=json
    private static final String CONTENT_BLOCK = """
            {"type":"resource_link","uri":"file:///reports/2026-07.pdf","name":"report","mimeType":"application/pdf","size":48213}""";

    // language=json
    private static final String REQUEST = """
            {"jsonrpc":"2.0","id":42,"method":"tools/call","params":{"name":"weather",
             "arguments":{"city":"Berlin","days":3,"units":{"temperature":"C","wind":"km/h"},"hourly":true},
             "_meta":{"progressToken":"req-42"}}}""";

    private byte[] resultBytes;
    private byte[] contentBlockBytes;
    private byte[] requestBytes;
    private CallToolResult result;
    private Map<String, Object> genericValue;

    @Setup
    public void setUp() {
        resultBytes = RESULT.getBytes(StandardCharsets.UTF_8);
        contentBlockBytes = CONTENT_BLOCK.getBytes(StandardCharsets.UTF_8);
        requestBytes = REQUEST.getBytes(StandardCharsets.UTF_8);
        result = CodecRegistry.codecFor(CallToolResult.class).decodeFromBytes(resultBytes);
        JsonNode tree = result.structuredContent();
        genericValue = Map.of("structured", tree, "tags", List.of("a", "b", 3), "total", 7L);
    }

    @Benchmark
    public CallToolResult decodeResult() {
        return CodecRegistry.codecFor(CallToolResult.class).decodeFromBytes(resultBytes);
    }

    @Benchmark
    public byte[] encodeResult() {
        return CodecRegistry.codecFor(CallToolResult.class).encodeToBytes(result);
    }

    @Benchmark
    public ContentBlock decodeUnion() {
        return CodecRegistry.codecFor(ContentBlock.class).decodeFromBytes(contentBlockBytes);
    }

    @Benchmark
    public JsonRpcMessage parseRequest() {
        return JsonRpcCodec.parseRequest(Unpooled.wrappedBuffer(requestBytes));
    }

    @Benchmark
    public String writeGenericValue() {
        return JsonRpcCodec.writeValueAsString(genericValue);
    }
}
