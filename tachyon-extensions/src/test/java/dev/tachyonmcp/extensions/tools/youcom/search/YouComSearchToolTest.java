/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tools.youcom.search;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.runtime.InteractionContext;
import dev.tachyonmcp.api.server.domain.Args;
import dev.tachyonmcp.api.server.features.tools.ToolRequest;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.session.NoopInteractionContext;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

public class YouComSearchToolTest {

    private static final InteractionContext NOOP_CTX = NoopInteractionContext.INSTANCE;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) {
        var tool = new YouComSearchTool(YouComSearchConfig.builder()
                .apiKey(System.getenv("YDC_API_KEY"))
                .build());
        var server = TachyonServer.builder()
                .info(it -> it.name("you-search-server").version("1.0"))
                .withTools(tools -> tools.register(tool.descriptor(), tool::handle))
                .port(8080)
                .build();
        server.start();
        try (server) {
            System.out.println("Connect your MCP client to http://" + server.host() + ":" + server.port() + "/mcp\n"
                    + "Press Ctrl+C to stop.");

            var latch = new java.util.concurrent.CountDownLatch(1);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down...");
                server.close();
                latch.countDown();
            }));
            latch.await();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    @Test
    void sendAuthFalseWhenNoKey() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        assertThat(tool.sendAuth()).isFalse();
    }

    @Test
    void sendAuthTrueWhenKeyProvided() {
        var tool = new YouComSearchTool(
                YouComSearchConfig.builder().apiKey("test-key-123").build());
        assertThat(tool.sendAuth()).isTrue();
    }

    @Test
    void sendAuthFalseWhenKeyIsBlank() {
        var tool =
                new YouComSearchTool(YouComSearchConfig.builder().apiKey("   ").build());
        assertThat(tool.sendAuth()).isFalse();
    }

    @Test
    void sendAuthFalseWhenFreeTier() {
        var tool =
                new YouComSearchTool(YouComSearchConfig.builder().freeTier(true).build());
        assertThat(tool.sendAuth()).isFalse();
    }

    @Test
    void descriptorHasCorrectNameAndTitle() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        assertThat(tool.descriptor().name()).isEqualTo("you-search");
        assertThat(tool.descriptor().title()).isEqualTo("You.com Web Search");
    }

    @Test
    void descriptorInputSchemaHasRequiredQuery() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        var document = tool.descriptor().inputSchema();
        assertThat(document).isNotNull();
        var schema = MAPPER.readTree(document.json());
        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("required").get(0).asString()).isEqualTo("query");
        assertThat(schema.path("properties").has("query")).isTrue();
        assertThat(schema.path("properties").has("count")).isTrue();
        assertThat(schema.path("properties").has("freshness")).isTrue();
        assertThat(schema.path("properties").has("news")).isTrue();
        assertThat(schema.path("properties").has("livecrawl")).isTrue();
    }

    @Test
    void handleReturnsErrorForEmptyQuery() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        var args = Args.of(Map.of("query", ""));
        assertThat(tool.handle(
                        NOOP_CTX,
                        ToolRequest.builder().name("you-search").arguments(args).build()))
                .isInstanceOf(ToolResult.Error.class);
    }

    @Test
    void handleReturnsErrorWhenMissingQuery() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        var args = Args.of(Map.of());
        assertThat(tool.handle(
                        NOOP_CTX,
                        ToolRequest.builder().name("you-search").arguments(args).build()))
                .isInstanceOf(ToolResult.Error.class);
    }

    @Test
    void buildRequestReturnsNullForEmptyQuery() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        var args = Args.of(Map.of("query", ""));
        assertThat(tool.buildRequest(args)).isNull();
    }

    @Test
    void buildRequestReturnsNullForMissingQuery() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        assertThat(tool.buildRequest(Args.of(Map.of()))).isNull();
    }

    @Test
    void buildRequestUriContainsQuery() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        var args = Args.of(Map.of("query", "hello"));
        assertThat(tool.buildRequest(args).uri())
                .isEqualTo(URI.create("https://api.you.com/v1/search?query=hello&count=5"));
    }

    @Test
    void buildRequestIncludesProfileFreeWhenFreeTier() {
        var tool =
                new YouComSearchTool(YouComSearchConfig.builder().freeTier(true).build());
        var args = Args.of(Map.of("query", "test"));
        assertThat(tool.buildRequest(args).uri().toString()).contains("profile=free");
    }

    @Test
    void buildRequestOmitsProfileFreeWhenNotFreeTier() {
        var tool = new YouComSearchTool(YouComSearchConfig.builder().build());
        var args = Args.of(Map.of("query", "test"));
        assertThat(tool.buildRequest(args).uri().toString()).doesNotContain("profile");
    }

    @Test
    void buildRequestIncludesAuthHeaderWhenKeyProvided() {
        var tool = new YouComSearchTool(
                YouComSearchConfig.builder().apiKey("sk-123").build());
        var args = Args.of(Map.of("query", "x"));
        assertThat(tool.buildRequest(args).headers().firstValue("X-API-Key")).hasValue("sk-123");
    }

    @Test
    void buildRequestOmitsAuthHeaderWhenFreeTier() {
        var tool = new YouComSearchTool(
                YouComSearchConfig.builder().apiKey("sk-123").freeTier(true).build());
        var args = Args.of(Map.of("query", "x"));
        assertThat(tool.buildRequest(args).headers().firstValue("X-API-Key")).isEmpty();
    }

    @Test
    void paramReturnsEncodedKeyValue() {
        assertThat(YouComSearchTool.param("query", "hello world")).isEqualTo("query=hello+world");
    }

    @Test
    void paramReturnsNullForNullValue() {
        assertThat(YouComSearchTool.param("key", null)).isNull();
    }

    @Test
    void formatResultsFormatsWebResults() {
        // language=json
        var json = MAPPER.readTree("""
            {"results": [
              {"title": "Result A", "link": "https://a.com", "description": "Desc A"},
              {"title": "Result B", "link": "https://b.com", "description": ""}
            ]}
            """);
        var text = YouComSearchTool.formatResults(json, false);
        assertThat(text).contains("Result A").contains("https://a.com").contains("Desc A");
        assertThat(text).contains("Result B").contains("https://b.com");
    }

    @Test
    void formatResultsFormatsNewsResults() {
        // language=json
        var json = MAPPER.readTree("""
            {"news": [
              {"title": "News 1", "url": "https://news.com/1", "description": "First story"}
            ]}
            """);
        var text = YouComSearchTool.formatResults(json, true);
        assertThat(text).contains("News 1").contains("https://news.com/1").contains("First story");
    }

    @Test
    void formatResultsShowsNoResultsWhenEmpty() {
        // language=json
        var json = MAPPER.readTree("""
            {"results": []}""");
        assertThat(YouComSearchTool.formatResults(json, false)).isEqualTo("No results found.");
    }

    @Test
    void formatResultsShowsNoResultsWhenMissingField() {
        // language=json
        var json = MAPPER.readTree("""
            {}""");
        assertThat(YouComSearchTool.formatResults(json, false)).isEqualTo("No results found.");
    }

    @Test
    void extractErrorReturnsMessageFromJsonError() {
        assertThat(YouComSearchTool.extractError("""
            {"message": "Invalid API key"}""")).isEqualTo("Invalid API key");
    }

    @Test
    void extractErrorReturnsTruncatedBodyWhenNoJson() {
        var body = "x".repeat(300);
        assertThat(YouComSearchTool.extractError(body)).hasSize(200);
    }

    @Test
    void extractErrorReturnsFullBodyWhenShort() {
        assertThat(YouComSearchTool.extractError("rate limited")).isEqualTo("rate limited");
    }

    @Test
    void extractErrorReturnsBodyWhenJsonMissingMessage() {
        assertThat(YouComSearchTool.extractError("""
            {"error": "something"}""")).isEqualTo("""
            {"error": "something"}""");
    }

    @Test
    void effectiveBaseUrlDefaultsToProduction() {
        var config = YouComSearchConfig.builder().build();
        assertThat(config.effectiveBaseUrl()).isEqualTo(YouComSearchConfig.DEFAULT_BASE);
    }

    @Test
    void effectiveBaseUrlUsesCustomUrl() {
        var config = YouComSearchConfig.builder()
                .baseUrl("http://localhost:9999/search")
                .build();
        assertThat(config.effectiveBaseUrl()).isEqualTo("http://localhost:9999/search");
    }
}
