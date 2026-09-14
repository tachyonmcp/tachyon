/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.server.domain.RequestId;
import dev.tachyonmcp.api.server.features.resources.ResourceDescriptor;
import dev.tachyonmcp.core.protocol.Protocols;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.ClientCapabilities;
import dev.tachyonmcp.core.protocol.mcp.v2025_11_25.models.InitializeRequestParams;
import dev.tachyonmcp.core.server.McpDispatcher;
import dev.tachyonmcp.core.server.RpcMethodHandler;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.session.DefaultDispatchContext;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

class SkillsExtensionTest {

    private ServerEngine server;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        server = (ServerEngine) SkillTestFixtures.startServer(SkillsExtension.builder()
                .registry(new FilesystemSkillsRegistry(SkillTestFixtures.classpathSkillsDir))
                .build());
        dispatcher = new McpDispatcher(server, server.executor());
    }

    @Test
    void advertisesExtensionAndDirectoryRead() {
        var extension = (SkillsExtension) server.extensions().getFirst();
        assertThat(extension.extensionId()).isEqualTo(SkillsExtension.ID);
        assertThat(extension.serverSettings().values().boolValue("directoryRead"))
                .isTrue();
    }

    @Test
    void registersEverySkillFileAsBaseResource() {
        var descriptors = server.resources().descriptors();
        assertThat(descriptors)
                .extracting(ResourceDescriptor::uri)
                .contains("skill://pdf-processing/scripts/extract.py", "skill://pdf-processing/templates/invoice.md");
        assertThat(descriptors)
                .allSatisfy(descriptor -> assertThat(descriptor.extensionId()).isNull());
        assertThat(server.resources().findByUri("skill://pdf-processing/SKILL.md"))
                .get()
                .extracting(ResourceDescriptor::description)
                .isEqualTo("Extract, fill, and assemble PDF documents");
        assertThat(server.resources().findByUri("skill://pdf-processing/SKILL.md"))
                .get()
                .extracting(ResourceDescriptor::mimeType)
                .isEqualTo("text/markdown");
        assertThat(server.resources().findByUri("skill://pdf-processing/scripts/extract.py"))
                .get()
                .extracting(ResourceDescriptor::mimeType)
                .isEqualTo("text/plain");
        assertThat(server.resources().findByUri("skill://pdf-processing/templates/invoice.md"))
                .get()
                .extracting(ResourceDescriptor::mimeType)
                .isEqualTo("text/markdown");
    }

    @Test
    void rejectsDuplicateSkillPathAtConfiguration() {
        var gitWorkflowDir = SkillTestFixtures.filesystemSkillsDir.resolve("git-workflow");
        var builder = SkillsExtension.builder()
                .registry(new FilesystemSkillsRegistry(gitWorkflowDir, "git-workflow"))
                .registry(new FilesystemSkillsRegistry(gitWorkflowDir, "git-workflow"));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("git-workflow");
    }

    @Test
    void ownsTheSkillMethods() {
        assertThat(server.extensionForMethod("skills/list")).isEqualTo(SkillsExtension.ID);
        assertThat(server.extensionForMethod("skills/get")).isEqualTo(SkillsExtension.ID);
        assertThat(server.extensionForMethod("resources/directory/read")).isEqualTo(SkillsExtension.ID);
    }

    @Test
    void listsSkillsWhenNegotiated() throws Exception {
        var body = dispatch("skills/list", Map.of("_meta", Map.of(SkillsExtension.ID, Map.of())));

        assertThat(body)
                .contains("\"skill://pdf-processing/SKILL.md\"")
                .contains("\"sha256:f05fea0e15cb5f951049570d4cebb3a84b272fd3390c85e8be7586f84f0b68f8\"");
    }

    @Test
    void getsSkillByUri() throws Exception {
        var body = dispatch(
                "skills/get",
                Map.of("uri", "skill://pdf-processing/SKILL.md", "_meta", Map.of(SkillsExtension.ID, Map.of())));

        assertThat(body)
                .contains("\"frontmatter\"")
                .contains("\"name\":\"pdf-processing\"")
                .contains("\"resultType\":\"complete\"");
    }

    @Test
    void rejectsUnknownSkillUri() throws Exception {
        var body = dispatch(
                "skills/get", Map.of("uri", "skill://nope/SKILL.md", "_meta", Map.of(SkillsExtension.ID, Map.of())));

        assertThat(body).contains("-32602");
    }

    @Test
    void listsDirectoryChildren() throws Exception {
        var body = dispatch(
                "resources/directory/read",
                Map.of("uri", "skill://pdf-processing", "_meta", Map.of(SkillsExtension.ID, Map.of())));

        assertThat(body)
                .contains("\"skill://pdf-processing/scripts\"")
                .contains("\"inode/directory\"")
                .contains("\"skill://pdf-processing/SKILL.md\"")
                .contains("\"resultType\":\"complete\"");
    }

    @Test
    void readsSkillFileContent() throws Exception {
        var body = dispatch(
                "resources/read",
                Map.of("uri", "skill://pdf-processing/SKILL.md", "_meta", Map.of(SkillsExtension.ID, Map.of())));

        assertThat(body).contains("assemble PDF");
    }

    private String dispatch(String method, Map<String, Object> rawParams) throws Exception {
        return dispatch(method, rawParams, true);
    }

    private String dispatch(String method, Map<String, Object> rawParams, boolean negotiate) throws Exception {
        var context = DefaultDispatchContext.create(Protocols.list().getFirst(), server);
        var session = server.createSession("sess_skills");
        context.setSession(session);
        if (negotiate) {
            var handler = server.getHandler("initialize");
            var caps = ClientCapabilities.builder()
                    .extensions(Map.of(SkillsExtension.ID, JsonNodeFactory.instance.objectNode()))
                    .build();
            var params = InitializeRequestParams.builder()
                    .protocolVersion("2025-11-25")
                    .capabilities(caps)
                    .build();
            Assertions.assertNotNull(handler);
            decodeAndHandle(handler, context, params);
        }
        session.activate();
        var result = (McpDispatcher.DispatchResult.Response) dispatcher
                .dispatchRequestAsync(RequestId.of(1), method, rawParams, session.id(), null, context)
                .join();
        return result.responseBodyString();
    }

    private static <I, O> O decodeAndHandle(RpcMethodHandler<I, O> handler, DispatchContext context, Object rawParams)
            throws Exception {
        I decoded = handler.decode(context, rawParams);
        return handler.handle(context, decoded);
    }
}
