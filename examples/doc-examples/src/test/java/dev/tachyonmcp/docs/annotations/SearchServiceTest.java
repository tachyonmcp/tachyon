package dev.tachyonmcp.docs.annotations;

import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.named;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.docs.JsonRpc.text;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestServers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SearchServiceTest {

    private static TachyonServer server;

    @BeforeAll
    static void startServer() {
        server = McpTestServers.start(b -> b.annotations(a -> a.register(new SearchService())), s -> {});
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @Test
    void explicitNameAndDescriptionShapeTheSchemaWhileMetaStaysOutOfIt() throws Exception {
        var schema = named(result(server, "tools/list"), "tools", "search").path("inputSchema");

        assertThat(schema.path("properties").propertyNames()).containsExactly("query");
        assertThat(schema.path("properties").path("query").path("description").asString())
                .isEqualTo("Search text");
        assertThat(items(schema.path("required"))).extracting(n -> n.asString()).containsExactly("query");
    }

    @Test
    void requestMetaIsDecodedIntoTheMetaParameter() throws Exception {
        var call = result(server, "tools/call", """
                {"name":"search","arguments":{"query":"cats"},"_meta":{"tenant":"acme"}}
                """);

        assertThat(text(call)).isEqualTo("acme:cats");
    }
}
