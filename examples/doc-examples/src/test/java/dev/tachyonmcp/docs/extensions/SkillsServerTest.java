package dev.tachyonmcp.docs.extensions;

import dev.tachyonmcp.docs.ForkedMain;
import org.junit.jupiter.api.Test;

import static dev.tachyonmcp.docs.ForkedMain.DOCUMENTED_PORT;
import static dev.tachyonmcp.docs.JsonRpc.items;
import static dev.tachyonmcp.docs.JsonRpc.result;
import static org.assertj.core.api.Assertions.assertThat;

class SkillsServerTest {

    @Test
    void mainServesTheSkillsDirectoryAndAdvertisesTheExtension() throws Exception {
        try (var ignored = ForkedMain.start("dev.tachyonmcp.docs.extensions.SkillsServer")) {
            var skills = result(DOCUMENTED_PORT, "skills/list", """
                    {"_meta":{"io.modelcontextprotocol/skills":{}}}
                    """).path("skills");

            assertThat(items(skills)).hasSize(1);
            var gitWorkflow = skills.get(0);
            assertThat(gitWorkflow.path("uri").asString()).isEqualTo("skill://git-workflow/SKILL.md");
            assertThat(gitWorkflow.path("frontmatter").path("name").asString()).isEqualTo("git-workflow");
            assertThat(items(gitWorkflow.path("resources")))
                    .extracting(r -> r.path("uri").asString())
                    .containsExactlyInAnyOrder(
                            "skill://git-workflow/SKILL.md", "skill://git-workflow/references/BRANCHING.md");

            var extensions = result(DOCUMENTED_PORT, "server/discover").path("capabilities").path("extensions");
            assertThat(extensions.path("io.modelcontextprotocol/skills").path("directoryRead").asBoolean())
                    .isTrue();
        }
    }
}
