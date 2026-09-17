/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.e2e.mcp.v2026_07_28;

import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;

import dev.tachyonmcp.api.server.domain.PromptMessage;
import dev.tachyonmcp.api.server.domain.Role;
import dev.tachyonmcp.api.server.domain.TextContent;
import dev.tachyonmcp.api.server.features.prompts.PromptDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.e2e.mcp.AbstractStatelessMcpE2eTest;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import dev.tachyonmcp.testkit.McpTestClients;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MCP 2026-07-28 {@code x-mcp-header}/{@code Mcp-Param-*} custom headers (SEP-2243): a tool
 * parameter annotated {@code x-mcp-header: "Region"} mirrors into an {@code Mcp-Param-Region} HTTP
 * header, which the server must validate against the body value (decoding the Base64 sentinel
 * format when present) and reject on mismatch/invalid-encoding/omission with {@code -32020}
 * (HeaderMismatch).
 */
class CustomHeaderValidationTest extends AbstractStatelessMcpE2eTest<Mcp20260728Client> {

    @Override
    protected Mcp20260728Client createTestClient() {
        return createTestClient(port);
    }

    @Override
    protected Mcp20260728Client createTestClient(int port) {
        return McpTestClients.latest(port);
    }

    // language=JSON
    private static final String EXECUTE_SQL_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "region": {"type": "string", "x-mcp-header": "Region"},
                "limit": {"type": "integer", "x-mcp-header": "Limit"},
                "query": {"type": "string"}
              },
              "required": ["region", "query"]
            }
            """;

    /**
     * A prompt deliberately sharing the annotated tool's name: {@code prompts/get} carries the same
     * {@code {name, arguments}} shape as {@code tools/call}, so this is what catches a mirror lookup
     * that resolves a prompt's name against the tool registry.
     */
    @BeforeEach
    void registerFixtures() {
        startServer(b -> b.capabilities(c -> c.tools().prompts()), s -> {
            s.tools()
                    .register(
                            d -> d.name("execute_sql")
                                    .description("Executes SQL")
                                    .inputSchema(EXECUTE_SQL_SCHEMA),
                            (ctx, request) -> ToolResult.text(
                                    "region=" + request.arguments().stringOr("region", "") + " query="
                                            + request.arguments().stringOr("query", "")));
            s.prompts()
                    .register(
                            PromptDescriptor.of("execute_sql", "A prompt that shares the tool's name"),
                            List.of(PromptMessage.of(Role.USER, TextContent.of("Review this SQL"))));
        });
    }

    private HttpResponse<String> post(String body, String regionHeader) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Mcp-Method", "tools/call");
        headers.put("Mcp-Name", "execute_sql");
        if (regionHeader != null) headers.put("Mcp-Param-Region", regionHeader);
        return postMcpRequest(body, headers);
    }

    private String toolCallBody(int id, String region) {
        return """
                {
                  "jsonrpc": "2.0",
                  "id": %d,
                  "method": "tools/call",
                  "params": {
                    "name": "execute_sql",
                    "arguments": {"region": "%s", "query": "SELECT 1"},
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                      "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                      "io.modelcontextprotocol/clientCapabilities": {}
                    }
                  }
                }
                """.formatted(id, region);
    }

    /**
     * SEP-2243 scopes {@code x-mcp-header} to tools — the only primitive whose definition carries an
     * {@code inputSchema} to annotate — so a {@code prompts/get} naming the same {@code execute_sql}
     * must not inherit the tool's {@code Mcp-Param-Region} requirement. It sends {@code Mcp-Method}
     * and {@code Mcp-Name} (both required of it on this revision) and no {@code Mcp-Param-*}, and
     * that is a complete, conforming request.
     */
    @Test
    void doesNotApplyToolParamMirrorsToASameNamedPrompt() throws Exception {
        // language=JSON
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": 20,
                  "method": "prompts/get",
                  "params": {
                    "name": "execute_sql",
                    "arguments": {"region": "us-west1"},
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                      "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                      "io.modelcontextprotocol/clientCapabilities": {}
                    }
                  }
                }
                """;

        var response = postMcpRequest(body, Map.of("Mcp-Method", "prompts/get", "Mcp-Name", "execute_sql"));

        assertThatResponse(response).hasStatus(200).isSuccess().hasId(20);
    }

    @Test
    void acceptsMatchingParamHeader() throws Exception {
        var response = post(toolCallBody(1, "us-west1"), "us-west1");
        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("region=us-west1 query=SELECT 1");
    }

    @Test
    void rejectsMissingParamHeaderWhenBodyHasValue() throws Exception {
        var response = post(toolCallBody(2, "us-west1"), null);
        assertThatResponse(response)
                .hasStatus(400)
                .isJsonRpcError()
                .hasId(2)
                .hasErrorCode(-32020)
                .hasErrorMessageContaining("Mcp-Param-Region")
                .hasErrorMessageContaining("is required");
    }

    /** A header claiming a value with nothing in the body to back it is spoofable, not just extra. */
    @Test
    void rejectsParamHeaderWhenBodyOmitsTheArgument() throws Exception {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Mcp-Method", "tools/call");
        headers.put("Mcp-Name", "execute_sql");
        headers.put("Mcp-Param-Region", "us-west1");
        headers.put("Mcp-Param-Limit", "999");
        // language=JSON
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": 17,
                  "method": "tools/call",
                  "params": {
                    "name": "execute_sql",
                    "arguments": {"region": "us-west1", "query": "SELECT 1"},
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                      "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                      "io.modelcontextprotocol/clientCapabilities": {}
                    }
                  }
                }
                """;
        var response = postMcpRequest(body, headers);

        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(17).hasErrorCode(-32020);
    }

    @Test
    void rejectsMismatchedParamHeader() throws Exception {
        var response = post(toolCallBody(3, "us-west1"), "eu-west1");
        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(3).hasErrorCode(-32020);
    }

    @Test
    void acceptsBase64EncodedParamHeader() throws Exception {
        var encoded = Base64.getEncoder().encodeToString("us-west1".getBytes(StandardCharsets.UTF_8));
        var response = post(toolCallBody(4, "us-west1"), "=?base64?" + encoded + "?=");
        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("region=us-west1 query=SELECT 1");
    }

    @Test
    void rejectsInvalidBase64ParamHeader() throws Exception {
        var response = post(toolCallBody(5, "us-west1"), "=?base64?not-valid-base64!!?=");
        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(5).hasErrorCode(-32020);
    }

    @Test
    void rejectsOverlappingBase64SentinelWithoutCrashing() throws Exception {
        // "=?base64?=" is short enough that the "=?base64?" prefix and "?=" suffix overlap by one
        // character; the server must not throw while extracting the (empty) encoded segment.
        var response = post(toolCallBody(7, "us-west1"), "=?base64?=");
        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(7).hasErrorCode(-32020);
    }

    @Test
    void rejectsBase64ParamHeaderWithMissingPadding() throws Exception {
        // "Hello" -> "SGVsbG8=" — strip the trailing '=' pad character. Base64.getDecoder() tolerates
        // this (decodes fine without it), but SEP-2243 requires the server to reject malformed padding.
        var encoded = Base64.getEncoder()
                .encodeToString("Hello".getBytes(StandardCharsets.UTF_8))
                .replace("=", "");
        var response = post(toolCallBody(6, "Hello"), "=?base64?" + encoded + "?=");
        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(6).hasErrorCode(-32020);
    }

    @Test
    void rejectsDuplicateParamHeaders() throws Exception {
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("Mcp-Method", List.of("tools/call"));
        headers.put("Mcp-Name", List.of("execute_sql"));
        headers.put("Mcp-Param-Region", List.of("us-west1", "eu-west1"));

        var response = postMcpRequest(toolCallBody(8, "us-west1"), headers, true);

        assertThatResponse(response).isRejectedWith(400, "Duplicate MCP header");
    }

    @Test
    void rejectsDuplicateIdenticalParamHeaders() throws Exception {
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("Mcp-Method", List.of("tools/call"));
        headers.put("Mcp-Name", List.of("execute_sql"));
        headers.put("Mcp-Param-Region", List.of("us-west1", "us-west1"));

        var response = postMcpRequest(toolCallBody(9, "us-west1"), headers, true);

        assertThatResponse(response).isRejectedWith(400, "Duplicate MCP header");
    }

    /** Pins the trust boundary: an injected {@code Mcp-Param-Admin} must not influence execution. */
    @Test
    void ignoresUnknownParamHeaderForDispatch() throws Exception {
        var headers = new LinkedHashMap<String, List<String>>();
        headers.put("Mcp-Method", List.of("tools/call"));
        headers.put("Mcp-Name", List.of("execute_sql"));
        headers.put("Mcp-Param-Region", List.of("us-west1"));
        headers.put("Mcp-Param-Admin", List.of("true"));

        var response = postMcpRequest(toolCallBody(10, "us-west1"), headers, true);

        assertThatResponse(response).hasStatus(200).isSuccess().hasTextContent("region=us-west1 query=SELECT 1");
    }

    /** Netty passes {@code 0x80}-{@code 0xFF} through as ISO-8859-1, so nothing else catches it. */
    @Test
    void rejectsParamHeaderWithNonAsciiCharacters() throws Exception {
        var response = post(toolCallBody(11, "us-west1"), "us-westé");

        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(11).hasErrorCode(-32020);
    }

    @Test
    void acceptsParamHeaderWithInternalSpaces() throws Exception {
        var response = post(toolCallBody(12, "us west 1"), "us west 1");

        assertThatResponse(response)
                .as("space is a permitted field-value character; only leading/trailing needs Base64")
                .hasStatus(200)
                .isSuccess()
                .hasTextContent("region=us west 1 query=SELECT 1");
    }

    private HttpResponse<String> postWithLimit(int id, String bodyLimit, String limitHeader) throws Exception {
        var headers = new LinkedHashMap<String, String>();
        headers.put("Mcp-Method", "tools/call");
        headers.put("Mcp-Name", "execute_sql");
        headers.put("Mcp-Param-Region", "us-west1");
        headers.put("Mcp-Param-Limit", limitHeader);
        // language=JSON
        var body = """
                {
                  "jsonrpc": "2.0",
                  "id": %d,
                  "method": "tools/call",
                  "params": {
                    "name": "execute_sql",
                    "arguments": {"region": "us-west1", "limit": %s, "query": "SELECT 1"},
                    "_meta": {
                      "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                      "io.modelcontextprotocol/clientInfo": {"name": "t", "version": "1"},
                      "io.modelcontextprotocol/clientCapabilities": {}
                    }
                  }
                }
                """.formatted(id, bodyLimit);
        return postMcpRequest(body, headers);
    }

    /** JSON Schema accepts 42.0 as an integer, so this must not slip past the header comparison. */
    @Test
    void rejectsSpoofedHeaderAgainstFractionalIntegerBody() throws Exception {
        var response = postWithLimit(13, "42.0", "1");

        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(13).hasErrorCode(-32020);
    }

    /** SEP-2243: integers compare numerically, so 42.0 and 42 are the same value. */
    @Test
    void acceptsNumericallyEqualIntegerHeader() throws Exception {
        var response = postWithLimit(14, "42.0", "42");

        assertThatResponse(response).hasStatus(200).isSuccess();
    }

    @Test
    void rejectsNonNumericHeaderAgainstNumericBody() throws Exception {
        var response = postWithLimit(15, "42", "not-a-number");

        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(15).hasErrorCode(-32020);
    }

    /** A body value that overflows to {@code Infinity} must reject cleanly, not throw. */
    @Test
    void rejectsNonFiniteNumericBody() throws Exception {
        var response = postWithLimit(16, "1e400", "1");

        assertThatResponse(response).hasStatus(400).isJsonRpcError().hasId(16).hasErrorCode(-32020);
    }
}
