/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.skills;

import static dev.tachyonmcp.extensions.skills.SkillTestFixtures.createClient;
import static dev.tachyonmcp.extensions.skills.SkillTestFixtures.filesystemSkillsDir;
import static dev.tachyonmcp.extensions.skills.SkillTestFixtures.startServer;
import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.features.resources.MimeTypes;
import dev.tachyonmcp.testkit.Mcp20251125Client;
import dev.tachyonmcp.testkit.Mcp20260728Client;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end test of the SEP-2640 skills extension over MCP 2026-07-28: skills served from the
 * classpath and from the filesystem, enumerated via {@code skills/list}, fetched via
 * {@code skills/get}, read as {@code skill://} resources, and navigated via
 * {@code resources/directory/read}, with per-request extension negotiation.
 */
class SkillsExtensionE2eTest {

    private static final String PDF_SKILL = """
        ---
        name: pdf-processing
        description: Extract, fill, and assemble PDF documents
        metadata:
          version: "2.1.0"
        ---

        # PDF Processing

        Extract, fill, and assemble PDF documents.
        Pick the matching template from `templates/` for the document type.
        """;

    private final SkillsRegistry classpathSkillsRegistry = new ClasspathSkillsRegistry("skills");
    private final SkillsRegistry filesystemSkillsRegistry = new FilesystemSkillsRegistry(filesystemSkillsDir);

    private final SkillsRegistry combinedRegistry =
            new CompositeSkillsRegistry(filesystemSkillsRegistry, classpathSkillsRegistry);

    @Test
    void classpathSkillsListedWithDigests() throws Exception {
        try (var server = startServer(
                        SkillsExtension.builder().registry(combinedRegistry).build());
                var client = createClient(server.port())) {
            // language=JSON
            var list = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"skills/list"}
                """);
            // language=JSON
            assertThatJson(list.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "skills":[
                      {
                        "uri":"skill://git-workflow/SKILL.md",
                        "frontmatter":{
                          "name":"git-workflow",
                          "description":"Follow this team's Git conventions for branching and commits"
                        },
                        "resources":[
                          {"uri":"skill://git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                          {"uri":"skill://git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                        ]
                      },
                      {"frontmatter":{"description":"How to read a file","name":"read-file"},"resources":[{"digest":"sha256:c009fac2e4613f3d635e99351e59f685250188809b2d6a8650b86a3eb0b8da2d","size":85,"uri":"skill://read-file/SKILL.md"}],"uri":"skill://read-file/SKILL.md"},
                      {
                        "uri":"skill://pdf-processing/SKILL.md",
                        "frontmatter":{
                          "name":"pdf-processing",
                          "description":"Extract, fill, and assemble PDF documents",
                          "metadata":{"version":"2.1.0"}
                        },
                        "resources":[
                          {"uri":"skill://pdf-processing/SKILL.md","digest":"sha256:da96519e26e173b406339e31ccf3adb0b0bd45c5fdfbabe335bf2ded216b2635","size":243},
                          {"uri":"skill://pdf-processing/scripts/extract.py","digest":"sha256:f05fea0e15cb5f951049570d4cebb3a84b272fd3390c85e8be7586f84f0b68f8","size":40},
                          {"uri":"skill://pdf-processing/templates/invoice.md","digest":"sha256:cd1a5be9eb7a5a46feea259ca26620f73dbd3587cc5111da44fff6489993c643","size":43}
                        ]
                      }

                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """);
        }
    }

    @Test
    void skillsListHonorsConfiguredCacheTtlAndScope() throws Exception {
        try (var server = startServer(SkillsExtension.builder()
                        .registry(classpathSkillsRegistry)
                        .cacheTtlMs(60_000)
                        .cacheScope("private")
                        .build());
                var client = createClient(server.port())) {
            // language=JSON
            var list = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"skills/list"}
                """);
            final var result = assertThat(list).isSuccess().result();
            assertThat(result.path("resultType").asString()).isEqualTo("complete");
            assertThat(result.path("ttlMs").asLong()).isEqualTo(60_000L);
            assertThat(result.path("cacheScope").asString()).isEqualTo("private");
        }
    }

    @Test
    void classpathSkillGetReturnsRequestedSkill() throws Exception {
        try (var server = startServer(SkillsExtension.builder()
                        .registry(classpathSkillsRegistry)
                        .build());
                var client = createClient(server.port())) {
            // language=JSON
            var get = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"skills/get","params":{"uri":"skill://pdf-processing/SKILL.md"}}
                """);
            // language=JSON
            assertThat(get).isSuccess().hasResult("""
                {
                  "skill":{
                    "uri":"skill://pdf-processing/SKILL.md",
                    "frontmatter":{
                      "name":"pdf-processing",
                      "description":"Extract, fill, and assemble PDF documents",
                      "metadata":{"version":"2.1.0"}
                    },
                    "resources":[
                      {"uri":"skill://pdf-processing/SKILL.md","digest":"sha256:da96519e26e173b406339e31ccf3adb0b0bd45c5fdfbabe335bf2ded216b2635","size":243},
                      {"uri":"skill://pdf-processing/scripts/extract.py","digest":"sha256:f05fea0e15cb5f951049570d4cebb3a84b272fd3390c85e8be7586f84f0b68f8","size":40},
                      {"uri":"skill://pdf-processing/templates/invoice.md","digest":"sha256:cd1a5be9eb7a5a46feea259ca26620f73dbd3587cc5111da44fff6489993c643","size":43}
                    ]
                  },
                  "resultType":"complete"
                }
                """);
        }
    }

    @Test
    void classpathSkillFileReadAsTextResource() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(filesystemSkillsRegistry)
                        .build());
                var client = createClient(server.port())) {
            // language=JSON
            var read = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"skill://git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThat(read).isSuccess().hasResult("""
                {
                    "contents":[
                      {"uri":"skill://git-workflow/SKILL.md","mimeType":"text/markdown","text":"---\\nname: git-workflow\\ndescription: Follow this team's Git conventions for branching and commits\\n---\\n\\n# Git Workflow\\n\\nFollow this team's Git conventions for branching and commits.\\nUse the branching guide in `references/BRANCHING.md`.\\n"}
                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                }
                """);
        }
    }

    @Test
    void classpathSkillDirectoryListsRootChildren() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(classpathSkillsRegistry)
                        .build());
                final var client = createClient(server.port())) {
            // language=JSON
            var directory = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/directory/read","params":{"uri":"skill://pdf-processing"}}
                """);
            // language=JSON
            assertThatJson(directory.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "resources":[
                      {"uri":"skill://pdf-processing/SKILL.md","name":"SKILL.md","mimeType":"text/markdown"},
                      {"uri":"skill://pdf-processing/scripts","name":"scripts","mimeType":"inode/directory"},
                      {"uri":"skill://pdf-processing/templates","name":"templates","mimeType":"inode/directory"}
                    ],
                    "resultType":"complete"
                  }
                }
                """);
        }
    }

    @Test
    void classpathSkillDirectoryListsNestedChildren() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(classpathSkillsRegistry)
                        .build());
                final var client = createClient(server.port())) {
            // language=JSON
            var nestedDirectory = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/directory/read","params":{"uri":"skill://pdf-processing/scripts"}}
                """);
            // language=JSON
            assertThatJson(nestedDirectory.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "resources":[
                      {"uri":"skill://pdf-processing/scripts/extract.py","name":"extract.py","mimeType":"text/plain"}
                    ],
                    "resultType":"complete"
                  }
                }
                """);
        }
    }

    @Test
    void fileSystemSkillsServed() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(filesystemSkillsRegistry)
                        .build());
                var client = createClient(server.port())) {
            // language=JSON
            var response = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"skills/list"}
                """);

            // language=JSON
            assertThatJson(response.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "skills":[
                      {
                        "uri":"skill://git-workflow/SKILL.md",
                        "frontmatter":{
                          "name":"git-workflow",
                          "description":"Follow this team's Git conventions for branching and commits"
                        },
                        "resources":[
                          {"uri":"skill://git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                          {"uri":"skill://git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                        ]
                      },
                      {
                        "uri":"skill://read-file/SKILL.md",
                        "frontmatter": {
                          "description":"How to read a file",
                          "name":"read-file"
                        },
                        "resources":[
                            {
                              "digest":"sha256:c009fac2e4613f3d635e99351e59f685250188809b2d6a8650b86a3eb0b8da2d",
                              "size":85,
                              "uri":"skill://read-file/SKILL.md"
                             }
                        ]}
                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """);
        }
    }

    @Test
    void singleSkillsUnderExplicitPaths() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(new FilesystemSkillsRegistry(
                                filesystemSkillsDir.resolve("git-workflow"), "team/git-workflow"))
                        .registry(new ClasspathSkillsRegistry("skills/pdf-processing", "acme/pdf-processing"))
                        .build());
                var client = createClient(server.port())) {
            // language=JSON
            var response = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"skills/list"}
                """);

            // language=JSON
            assertThatJson(response.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "skills":[
                      {
                        "uri":"skill://team/git-workflow/SKILL.md",
                        "frontmatter":{
                          "name":"git-workflow",
                          "description":"Follow this team's Git conventions for branching and commits"
                        },
                        "resources":[
                          {"uri":"skill://team/git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                          {"uri":"skill://team/git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                        ]
                      },
                      {
                        "uri":"skill://acme/pdf-processing/SKILL.md",
                        "frontmatter":{
                          "name":"pdf-processing",
                          "description":"Extract, fill, and assemble PDF documents",
                          "metadata":{"version":"2.1.0"}
                        },
                        "resources":[
                          {"uri":"skill://acme/pdf-processing/SKILL.md","digest":"sha256:da96519e26e173b406339e31ccf3adb0b0bd45c5fdfbabe335bf2ded216b2635","size":243},
                          {"uri":"skill://acme/pdf-processing/scripts/extract.py","digest":"sha256:f05fea0e15cb5f951049570d4cebb3a84b272fd3390c85e8be7586f84f0b68f8","size":40},
                          {"uri":"skill://acme/pdf-processing/templates/invoice.md","digest":"sha256:cd1a5be9eb7a5a46feea259ca26620f73dbd3587cc5111da44fff6489993c643","size":43}
                        ]
                      }
                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """);
        }
    }

    @Test
    void sameNamedSkillsUnderDifferentNamespacesAreBothListedAndReadableOn20260728() throws Exception {
        var gitWorkflowDir = filesystemSkillsDir.resolve("git-workflow");
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(new FilesystemSkillsRegistry(gitWorkflowDir, "team/git-workflow"))
                        .registry(new FilesystemSkillsRegistry(gitWorkflowDir, "acme/git-workflow"))
                        .build());
                final var client = createClient(server.port())) {

            // skills/list: both present in registration order, each keeping its own frontmatter name
            // language=JSON
            var skillsList = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"skills/list"}
                """);
            // language=JSON
            assertThatJson(skillsList.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "skills":[
                      {
                        "uri":"skill://team/git-workflow/SKILL.md",
                        "frontmatter":{
                          "name":"git-workflow",
                          "description":"Follow this team's Git conventions for branching and commits"
                        },
                        "resources":[
                          {"uri":"skill://team/git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                          {"uri":"skill://team/git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                        ]
                      },
                      {
                        "uri":"skill://acme/git-workflow/SKILL.md",
                        "frontmatter":{
                          "name":"git-workflow",
                          "description":"Follow this team's Git conventions for branching and commits"
                        },
                        "resources":[
                          {"uri":"skill://acme/git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                          {"uri":"skill://acme/git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                        ]
                      }
                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """);

            // skills/get: each fetched independently by its own URI, no cross-contamination
            // language=JSON
            var teamGet = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"skills/get","params":{"uri":"skill://team/git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(teamGet.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "result":{
                    "skill":{
                      "uri":"skill://team/git-workflow/SKILL.md",
                      "frontmatter":{
                        "name":"git-workflow",
                        "description":"Follow this team's Git conventions for branching and commits"
                      },
                      "resources":[
                        {"uri":"skill://team/git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                        {"uri":"skill://team/git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                      ]
                    },
                    "resultType":"complete"
                  }
                }
                """);

            var acmeGet = client.sendRpc("""
                {"jsonrpc":"2.0","id":3,"method":"skills/get","params":{"uri":"skill://acme/git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(acmeGet.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":3,
                  "result":{
                    "skill":{
                      "uri":"skill://acme/git-workflow/SKILL.md",
                      "frontmatter":{
                        "name":"git-workflow",
                        "description":"Follow this team's Git conventions for branching and commits"
                      },
                      "resources":[
                        {"uri":"skill://acme/git-workflow/SKILL.md","digest":"sha256:b9de7cc1f03a390dd4ee3b2881a13eb5e39f02ec5f44ffb0ab9fb91e10c08d67","size":234},
                        {"uri":"skill://acme/git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f309d54105cc561675ce4384fa62971e00919fe9bd297a37e443746c24e","size":68}
                      ]
                    },
                    "resultType":"complete"
                  }
                }
                """);

            // base resources/list (no extension negotiation): both manifests present, same name
            var list = client.sendRpc("""
                {"jsonrpc":"2.0","id":4,"method":"resources/list","params":{}}
                """);
            // language=JSON
            assertThatJson(list.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":4,
                  "result":{
                    "resources":[
                      {
                        "uri":"skill://acme/git-workflow/references/BRANCHING.md",
                        "name":"acme/git-workflow/references/BRANCHING.md",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://acme/git-workflow/SKILL.md",
                        "name":"git-workflow",
                        "description":"Follow this team's Git conventions for branching and commits",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://team/git-workflow/SKILL.md",
                        "name":"git-workflow",
                        "description":"Follow this team's Git conventions for branching and commits",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://team/git-workflow/references/BRANCHING.md",
                        "name":"team/git-workflow/references/BRANCHING.md",
                        "mimeType":"text/markdown"
                      }
                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """);

            var skillMd = Files.readString(gitWorkflowDir.resolve("SKILL.md"));

            // base resources/read: both readable by their own URI
            var teamRead = client.sendRpc("""
                {"jsonrpc":"2.0","id":5,"method":"resources/read","params":{"uri":"skill://team/git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(teamRead.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":5,
                  "result":{
                    "contents":[{
                      "uri":"skill://team/git-workflow/SKILL.md",
                      "mimeType":"text/markdown",
                      "text":%s
                    }],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """.formatted(new ObjectMapper().writeValueAsString(skillMd)));

            var acmeRead = client.sendRpc("""
                {"jsonrpc":"2.0","id":6,"method":"resources/read","params":{"uri":"skill://acme/git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(acmeRead.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":6,
                  "result":{
                    "contents":[{
                      "uri":"skill://acme/git-workflow/SKILL.md",
                      "mimeType":"text/markdown",
                      "text":%s
                    }],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """.formatted(new ObjectMapper().writeValueAsString(skillMd)));
        }
    }

    @Test
    void sameNamedSkillsUnderDifferentNamespacesAreBothListedAndReadableOn20251125() throws Exception {
        var gitWorkflowDir = filesystemSkillsDir.resolve("git-workflow");
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(new FilesystemSkillsRegistry(gitWorkflowDir, "team/git-workflow"))
                        .registry(new FilesystemSkillsRegistry(gitWorkflowDir, "acme/git-workflow"))
                        .build());
                final var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            var list = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list","params":{}}
                """);

            // language=JSON
            assertThatJson(list.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "resources":[
                      {
                        "uri":"skill://acme/git-workflow/references/BRANCHING.md",
                        "name":"acme/git-workflow/references/BRANCHING.md",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://acme/git-workflow/SKILL.md",
                        "name":"git-workflow",
                        "description":"Follow this team's Git conventions for branching and commits",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://team/git-workflow/SKILL.md",
                        "name":"git-workflow",
                        "description":"Follow this team's Git conventions for branching and commits",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://team/git-workflow/references/BRANCHING.md",
                        "name":"team/git-workflow/references/BRANCHING.md",
                        "mimeType":"text/markdown"
                      }
                    ]
                  }
                }
                """);

            var skillMd = Files.readString(gitWorkflowDir.resolve("SKILL.md"));

            var acmeRead = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"skill://acme/git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(acmeRead.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "result":{
                    "contents":[{
                      "uri":"skill://acme/git-workflow/SKILL.md",
                      "mimeType":"text/markdown",
                      "text":%s
                    }]
                  }
                }
                """.formatted(new ObjectMapper().writeValueAsString(skillMd)));

            // language=JSON
            var teamRead = client.sendRpc("""
                {"jsonrpc":"2.0","id":3,"method":"resources/read","params":{"uri":"skill://team/git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(teamRead.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":3,
                  "result":{
                    "contents":[{
                      "uri":"skill://team/git-workflow/SKILL.md",
                      "mimeType":"text/markdown",
                      "text":%s
                    }]
                  }
                }
                """.formatted(new ObjectMapper().writeValueAsString(skillMd)));
        }
    }

    @Test
    void rootDirectoryListsNamespaces() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(new FilesystemSkillsRegistry(
                                filesystemSkillsDir.resolve("git-workflow"), "team/git-workflow"))
                        .registry(new ClasspathSkillsRegistry("skills/pdf-processing", "acme/pdf-processing"))
                        .build());
                final var client = createClient(server.port())) {
            var root = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/directory/read","params":{"uri":"skill://"}}
                """);
            // language=JSON
            assertThatJson(root.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":1,
                  "result":{
                    "resources":[
                      {"uri":"skill://acme","name":"acme","mimeType":"inode/directory"},
                      {"uri":"skill://team","name":"team","mimeType":"inode/directory"}
                    ],
                    "resultType":"complete"
                  }
                }
                """);

            var namespace = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"resources/directory/read","params":{"uri":"skill://team"}}
                """);
            // language=JSON
            assertThatJson(namespace.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "result":{
                    "resources":[
                      {"uri":"skill://team/git-workflow","name":"git-workflow","mimeType":"inode/directory"}
                    ],
                    "resultType":"complete"
                  }
                }
                """);
        }
    }

    @Test
    void skillResourcesRemainAvailableWhenExtensionMethodsAreHidden() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(new ClasspathSkillsRegistry("skills"))
                        .build());
                final var client = new Mcp20260728Client(server.port())) {

            var list = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"skills/list","params":{}}
                """);

            assertThat(list).isJsonRpcError().isMethodNotFound();

            var get = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"skills/get","params":{"uri":"skill://pdf-processing/SKILL.md"}}
                """);
            assertThat(get).isJsonRpcError().isMethodNotFound();

            var directory = client.post("""
                {"jsonrpc":"2.0","id":3,"method":"resources/directory/read","params":{"uri":"skill://pdf-processing"}}
                """);
            assertThat(directory).isJsonRpcError().isMethodNotFound();

            var read = client.sendRpc("""
                {"jsonrpc":"2.0","id":4,"method":"resources/read","params":{"uri":"skill://pdf-processing/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(read.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":4,
                  "result":{
                    "contents":[{
                      "uri":"skill://pdf-processing/SKILL.md",
                      "mimeType":"text/markdown",
                      "text":%s
                    }],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """.formatted(new ObjectMapper().writeValueAsString(PDF_SKILL)));

            var resources = client.sendRpc("""
                {"jsonrpc":"2.0","id":5,"method":"resources/list","params":{}}
                """);
            // language=JSON
            assertThatJson(resources.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":5,
                  "result":{
                    "resources":[
                      {
                        "uri":"skill://pdf-processing/SKILL.md",
                        "name":"pdf-processing",
                        "description":"Extract, fill, and assemble PDF documents",
                        "mimeType":"text/markdown"
                      },
                      {
                        "uri":"skill://pdf-processing/scripts/extract.py",
                        "name":"pdf-processing/scripts/extract.py",
                        "mimeType":"text/plain"
                      },
                      {
                        "uri":"skill://pdf-processing/templates/invoice.md",
                        "name":"pdf-processing/templates/invoice.md",
                        "mimeType":"text/markdown"
                      }
                    ],
                    "resultType":"complete",
                    "ttlMs":0,
                    "cacheScope":"public"
                  }
                }
                """);
        }
    }

    @Test
    void skillResourceRemainsReadableOnLegacyProtocolWithoutExtensionNegotiation() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(new ClasspathSkillsRegistry("skills"))
                        .build());
                final var client = new Mcp20251125Client(server.port())) {
            client.initialize();

            var read = client.sendRpc("""
                {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"skill://pdf-processing/SKILL.md"}}
                """);

            // language=JSON
            assertThatJson(read.body()).isEqualTo("""
                {
                  "jsonrpc":"2.0",
                  "id":2,
                  "result":{
                    "contents":[{
                      "uri":"skill://pdf-processing/SKILL.md",
                      "mimeType":"text/markdown",
                      "text":%s
                    }]
                  }
                }
                """.formatted(new ObjectMapper().writeValueAsString(PDF_SKILL)));
        }
    }

    @Test
    void unknownSkillsAndDirectoriesFail() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(classpathSkillsRegistry)
                        .build());
                final var client = createClient(server.port())) {
            var get = client.post("""
                {"jsonrpc":"2.0","id":1,"method":"skills/get","params":{"uri":"skill://nope/SKILL.md"}}
                """);
            assertThatJson(get.body()).isEqualTo("""
                {"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown skill: skill://nope/SKILL.md"}}
                """);

            var directory = client.post("""
                {"jsonrpc":"2.0","id":2,"method":"resources/directory/read","params":{"uri":"skill://git-workflow/SKILL.md"}}
                """);
            // language=JSON
            assertThatJson(directory.body()).isEqualTo("""
                {"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"Unknown skill directory: skill://git-workflow/SKILL.md"}}
                """);
        }
    }

    @Test
    void resourcesListMatchesFixtures() throws Exception {
        try (final var server = startServer(SkillsExtension.builder()
                        .registry(classpathSkillsRegistry)
                        .build());
                final var client = createClient(server.port())) {
            var mapper = new ObjectMapper();
            var digestsByUri = digestsByUri(client, mapper);

            var list = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/list","params":{"_meta":{"%s":{}}}}
                """);

            var resources = mapper.readTree(list.body()).path("result").path("resources");
            assertThat(resources.isArray()).isTrue();

            var relativePaths = new ArrayList<String>();
            for (var resource : resources) {
                var uri = resource.path("uri").asString();
                var relativePath = uri.substring("skill://".length());
                relativePaths.add(relativePath);

                assertMatchesFixture(
                        client,
                        mapper,
                        resource,
                        uri,
                        Path.of("src/test/resources/skills"),
                        relativePath,
                        digestsByUri.get(uri));
            }

            assertThat(relativePaths)
                    .containsExactlyInAnyOrder(
                            "pdf-processing/SKILL.md",
                            "pdf-processing/scripts/extract.py",
                            "pdf-processing/templates/invoice.md");
        }
    }

    private Map<String, String> digestsByUri(Mcp20260728Client client, ObjectMapper mapper) throws Exception {
        // language=JSON
        var skillsList = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"skills/list","params":{"_meta":{"%s":{}}}}
                """);

        var digests = new HashMap<String, String>();
        for (var skill : mapper.readTree(skillsList.body()).path("result").path("skills")) {
            for (var resource : skill.path("resources")) {
                digests.put(
                        resource.path("uri").asString(), resource.path("digest").asString());
            }
        }
        return digests;
    }

    private void assertMatchesFixture(
            Mcp20260728Client client,
            ObjectMapper mapper,
            JsonNode resource,
            String uri,
            Path basePath,
            String relativePath,
            String expectedDigest)
            throws Exception {
        var actualBytes = Files.readAllBytes(basePath.resolve(relativePath));
        var loadedBytes = readContent(client, mapper, uri);

        assertThat(loadedBytes).as("content of %s", uri).isEqualTo(actualBytes);
        assertThat(sha256(loadedBytes)).as("digest of %s", uri).isEqualTo(expectedDigest);

        assertThat(resource.path("mimeType").asString())
                .as("mimeType of %s", uri)
                .isEqualTo(MimeTypes.guess(relativePath));

        if (relativePath.endsWith("/SKILL.md")) {
            var frontmatter = FrontmatterParser.parse(actualBytes);
            assertThat(resource.path("name").asString())
                    .as("name of %s", uri)
                    .isEqualTo(String.valueOf(frontmatter.get("name")));
            assertThat(resource.path("description").asString())
                    .as("description of %s", uri)
                    .isEqualTo(String.valueOf(frontmatter.get("description")));
        } else {
            assertThat(resource.path("name").asString()).as("name of %s", uri).isEqualTo(relativePath);
            assertThat(resource.has("description"))
                    .as("no description for %s", uri)
                    .isFalse();
        }
    }

    private byte[] readContent(Mcp20260728Client client, ObjectMapper mapper, String uri) throws Exception {
        var read = client.sendRpc("""
                {"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"%s"}}
                """.formatted(uri));
        final var result = assertThat(read).isSuccess().result();
        var content = result.path("contents").get(0);
        return content.has("text")
                ? content.path("text").asString().getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(content.path("blob").asString());
    }

    private static String sha256(byte[] bytes) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        return "sha256:" + HexFormat.of().formatHex(digest.digest(bytes));
    }
}
