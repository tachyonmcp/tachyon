package dev.tachyonmcp.docs.extensions.skills;

import dev.tachyonmcp.docs.ForkedMain;
import dev.tachyonmcp.testkit.McpTestClients;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.post;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static dev.tachyonmcp.testkit.McpHttpResponseAssert.assertThatResponse;
import static org.assertj.core.api.Assertions.assertThat;

class SkillsExtensionTest {

    private static final String SKILLS_META = """
            {"_meta":{"io.modelcontextprotocol/skills":{}}}
            """;

    private static List<String> skillUris(JsonNode skillsListResult) {
        return items(skillsListResult.path("skills")).stream()
                .map(skill -> skill.path("uri").asString())
                .toList();
    }

    @Test
    void mainServesFilesystemAndClasspathSkills() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.extensions.skills.SkillsServer")) {
            var list = result(DOCUMENTED_PORT, "skills/list", SKILLS_META);

            assertThat(skillUris(list))
                    .containsExactlyInAnyOrder("skill://git-workflow/SKILL.md", "skill://code-review/SKILL.md");
            assertThat(items(list.path("skills")))
                    .extracting(skill -> skill.path("frontmatter").path("description").asString())
                    .contains("Follow this team's Git conventions for branching and commits");
        }
    }

    @Test
    void explicitPathsNamespaceTheSkillsAndTheRootListsTheNamespaces() throws Exception {
        try (var server = NamespacedSkills.start()) {
            assertThat(skillUris(result(server, "skills/list", SKILLS_META)))
                    .containsExactlyInAnyOrder("skill://team/git-workflow/SKILL.md", "skill://acme/pdf-processing/SKILL.md");

            var root = result(server, "resources/directory/read", """
                    {"uri":"skill://","_meta":{"io.modelcontextprotocol/skills":{}}}
                    """).path("resources");
            assertThat(items(root))
                    .extracting(r -> r.path("name").asString() + ":" + r.path("mimeType").asString())
                    .containsExactlyInAnyOrder("team:inode/directory", "acme:inode/directory");

            var contents = result(server, "resources/read", """
                    {"uri":"skill://team/git-workflow/SKILL.md"}
                    """).path("contents").get(0);
            assertThat(contents.path("text").asString()).contains("name: git-workflow");
        }
    }

    @Test
    void directoryReadFoldsSubdirectoriesIntoDirectoryEntries() throws Exception {
        try (var server = RequiredNegotiation.start()) {
            var declared = Map.<String, JsonNode>of(
                    "io.modelcontextprotocol/skills", new ObjectMapper().createObjectNode());
            try (var client = McpTestClients.latest(server.port()).withExtensions(declared)) {
                var response = client.post("""
                        {"jsonrpc":"2.0","id":1,"method":"resources/directory/read",
                         "params":{"uri":"skill://pdf-processing","_meta":{"io.modelcontextprotocol/skills":{}}}}
                        """);

                var resources = assertThatResponse(response).hasStatus(200).isSuccess().result().path("resources");
                assertThat(items(resources))
                        .extracting(r -> r.path("name").asString() + ":" + r.path("mimeType").asString())
                        .containsExactlyInAnyOrder(
                                "SKILL.md:text/markdown", "scripts:inode/directory", "templates:inode/directory");
            }
        }
    }

    @Test
    void requiredNegotiationRejectsUndeclaredClientsButKeepsBaseResourcesReadable() throws Exception {
        try (var server = RequiredNegotiation.start()) {
            var rejected = post(server, "skills/list", SKILLS_META);

            assertThatResponse(rejected).hasStatus(400).isJsonRpcError().hasErrorCode(-32021);
            var data = new ObjectMapper().readTree(rejected.body()).path("error").path("data");
            assertThat(data.path("requiredCapabilities")
                            .path("extensions")
                            .has("io.modelcontextprotocol/skills"))
                    .isTrue();

            assertThat(items(result(server, "resources/list").path("resources")))
                    .extracting(r -> r.path("uri").asString())
                    .contains("skill://pdf-processing/SKILL.md");
            assertThat(result(server, "resources/read", """
                    {"uri":"skill://pdf-processing/SKILL.md"}
                    """).path("contents").get(0).path("text").asString()).contains("name: pdf-processing");

            var declared = Map.<String, JsonNode>of(
                    "io.modelcontextprotocol/skills", new ObjectMapper().createObjectNode());
            try (var client = McpTestClients.latest(server.port()).withExtensions(declared)) {
                var accepted = client.post("""
                        {"jsonrpc":"2.0","id":1,"method":"skills/list","params":%s}
                        """.formatted(SKILLS_META));

                var list = assertThatResponse(accepted).hasStatus(200).isSuccess().result();
                assertThat(skillUris(list)).containsExactly("skill://pdf-processing/SKILL.md");
            }
        }
    }
}
