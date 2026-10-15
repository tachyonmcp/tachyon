/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link McpClient} for MCP protocol version 2026-07-28.
 *
 * <p>This revision removed {@code initialize} and sessions: every request self-describes its
 * protocol context, so this client performs the required per-request shaping automatically —
 * {@code _meta.io.modelcontextprotocol/protocolVersion} matching the {@code MCP-Protocol-Version}
 * header, a {@code clientInfo}, a {@code clientCapabilities} object, and the {@code Mcp-Method} /
 * {@code Mcp-Name} headers the server validates (SEP-2575). {@link #initialize()} is unavailable.
 */
public final class Mcp20260728Client extends McpClient {

    /** The MCP protocol version this client speaks. */
    public static final String PROTOCOL_VERSION = "2026-07-28";

    private volatile Map<String, JsonNode> extensions = Map.of();

    /**
     * Creates a client for the given server port.
     *
     * @param port the port of the running Tachyon server
     */
    public Mcp20260728Client(int port) {
        super(port);
    }

    /**
     * Creates a client for the given server port that acts as the caller {@code bearerToken} names.
     *
     * @param port the port of the running Tachyon server
     * @param bearerToken the access token sent as {@code Authorization: Bearer} on every request
     */
    public Mcp20260728Client(int port, String bearerToken) {
        super(port, bearerToken);
    }

    /**
     * Creates a client against an arbitrary MCP endpoint (local or remote, http or https).
     *
     * @param mcpEndpoint the MCP endpoint URI, e.g. {@code https://staging.example.com/mcp}
     */
    public Mcp20260728Client(URI mcpEndpoint) {
        super(mcpEndpoint);
    }

    /**
     * Declares protocol extensions this client supports on every subsequent request, written to
     * {@code _meta."io.modelcontextprotocol/clientCapabilities".extensions} (SEP-2575).
     *
     * @param extensions extension id to client settings
     * @return {@code this}
     */
    public Mcp20260728Client withExtensions(Map<String, JsonNode> extensions) {
        this.extensions = Map.copyOf(extensions);
        return this;
    }

    /**
     * Sends {@code server/discover} and returns staged assertions for its response.
     *
     * @return discover response assertions
     * @throws Exception when the request cannot be sent
     */
    public DiscoverResponseAssert discover() throws Exception {
        return new DiscoverResponseAssert(post("""
                {"jsonrpc":"2.0","id":1,"method":"server/discover"}
                """));
    }

    @Override
    public @Nullable String initialize() {
        throw new UnsupportedOperationException(
                "2026-07-28 removed 'initialize'; send requests to the server directly");
    }

    @Override
    protected String protocolVersion() {
        return PROTOCOL_VERSION;
    }

    @Override
    protected String requestBody(String body) {
        var request = MAPPER.readTree(body);
        if (!(request instanceof ObjectNode requestObject)) {
            throw new IllegalArgumentException("MCP request must be a JSON object");
        }
        var meta = objectField(objectField(requestObject, "params"), "_meta");
        meta.put("io.modelcontextprotocol/protocolVersion", PROTOCOL_VERSION);
        var clientInfo = objectField(meta, "io.modelcontextprotocol/clientInfo");
        if (!clientInfo.hasNonNull("name")) clientInfo.put("name", "test");
        if (!clientInfo.hasNonNull("version")) clientInfo.put("version", "1.0");
        var capabilities = objectField(meta, "io.modelcontextprotocol/clientCapabilities");
        if (!extensions.isEmpty()) {
            capabilities.set("extensions", MAPPER.valueToTree(extensions));
        }
        return MAPPER.writeValueAsString(requestObject);
    }

    @Override
    protected void configureRequest(HttpRequest.Builder builder, String body) {
        var request = MAPPER.readTree(body);
        var method = request.path("method").asString(null);
        if (method != null) builder.header("Mcp-Method", method);

        var params = request.path("params");
        var name = params.path("name").asString(null);
        if (name == null) name = params.path("uri").asString(null);
        if (name != null) builder.header("Mcp-Name", name);
    }

    private static ObjectNode objectField(ObjectNode parent, String name) {
        var node = parent.get(name);
        if (node instanceof ObjectNode object) return object;
        var object = MAPPER.createObjectNode();
        parent.set(name, object);
        return object;
    }
}
