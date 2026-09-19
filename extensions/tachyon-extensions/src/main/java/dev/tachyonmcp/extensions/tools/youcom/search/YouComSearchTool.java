/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tools.youcom.search;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.features.tools.AbstractToolHandler;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class YouComSearchTool extends AbstractToolHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    // language=json
    private static final String INPUT_SCHEMA_JSON = """
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "Search query (e.g., latest AI news 2026)"
            },
            "count": {
              "type": "integer",
              "description": "Number of results (1-10, default: 5)"
            },
            "freshness": {
              "type": "string",
              "enum": ["day", "week", "month", "year"],
              "description": "Filter by recency (default: none)"
            },
            "news": {
              "type": "boolean",
              "description": "Include news results (default: false)"
            },
            "livecrawl": {
              "type": "string",
              "enum": ["all", "none"],
              "description": "Enable live crawling (default: none; use 'all' for latest)"
            }
          },
          "required": ["query"]
        }
        """;

    private final YouComSearchConfig config;

    public YouComSearchTool(YouComSearchConfig config) {
        super(ToolDescriptor.builder()
                .name("you-search")
                .title("You.com Web Search")
                .description("Search the web via You.com API. Free tier: 100 queries/day, no API key needed. "
                        + "For higher limits, set YDC_API_KEY env var.")
                .inputSchema(INPUT_SCHEMA_JSON)
                .build());
        this.config = config;
    }

    boolean sendAuth() {
        return !config.isFreeTier() && config.apiKey() != null;
    }

    @Override
    public ToolResult handle(InteractionContext context, ToolRequest request) {
        var args = request.arguments();
        var httpRequest = buildRequest(args);
        if (httpRequest == null) {
            return ToolResult.error("Query must not be empty");
        }

        try {
            var response = HTTP.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            var body = response.body();

            if (response.statusCode() != 200) {
                return ToolResult.error(
                        "You.com search failed (HTTP " + response.statusCode() + "): " + extractError(body));
            }

            var json = MAPPER.readTree(body);
            return ToolResult.structured(json, formatResults(json, args.boolOr("news", false)));

        } catch (Exception e) {
            return ToolResult.error("You.com search error: " + e.getMessage());
        }
    }

    @Nullable
    HttpRequest buildRequest(Args args) {
        var query = args.stringOr("query", null);
        if (query == null || query.isBlank()) {
            return null;
        }
        var count = Math.clamp(args.intOr("count", 5), 1, 10);
        var freshness = args.stringOr("freshness", null);
        var livecrawl = args.stringOr("livecrawl", null);

        var params = Stream.of(
                        param("query", query),
                        "count=" + count,
                        param("freshness", freshness),
                        param("news", args.boolOr("news", false) ? "true" : null),
                        param("livecrawl", livecrawl),
                        config.isFreeTier() ? param("profile", "free") : null)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("&"));

        var requestBuilder = HttpRequest.newBuilder()
                .uri(URI.create(config.effectiveBaseUrl() + "?" + params))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json");

        if (sendAuth()) {
            requestBuilder.header("X-API-Key", config.apiKey());
        }

        return requestBuilder.build();
    }

    static @Nullable String param(String key, @Nullable String value) {
        if (value == null) return null;
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String extractError(String body) {
        try {
            var node = MAPPER.readTree(body);
            var msg = node.path("message").asString();
            if (!msg.isBlank()) return msg;
        } catch (Exception ignored) {
        }
        return body.length() > 200 ? body.substring(0, 200) : body;
    }

    static String formatResults(JsonNode json, boolean news) {
        var sb = new StringBuilder();
        var items = news ? json.path("news") : json.path("results");
        if (items.isArray()) {
            for (var item : items) {
                var title = item.path("title").asString();
                var url = item.path(news ? "url" : "link").asString();
                var desc = item.path("description").asString();
                sb.append("• ").append(title).append("\n  ").append(url);
                if (!desc.isBlank()) {
                    sb.append("\n  ").append(desc);
                }
                sb.append("\n\n");
            }
        }
        if (sb.isEmpty()) sb.append("No results found.");
        return sb.toString();
    }
}
