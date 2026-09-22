---
title: "MCP Skills Extension"
weight: 10
sidebar_order: 10
toc: true
description: |-
  Ship an MCP Skills extension from your Tachyon server so agents can discover and install skills.
---

Agent Skills package a capability — instructions, scripts, reference material — as a directory with a `SKILL.md` manifest. Claude Code, Claude apps, and other MCP clients already load skills from the local filesystem; [SEP-2640][SEP-2640] standardizes how a server serves the same packages over MCP, so a client can discover and fetch them without a shared filesystem.

Tachyon's `SkillsExtension` (`tachyon-extensions-skills`) implements [SEP-2640][SEP-2640]: it scans skill directories, publishes each file as a `skill://` resource, and answers `skills/list`, `skills/get`, and `resources/directory/read`. `tachyon-extensions` still pulls it in transitively, so existing `tachyon-extensions` dependents keep working unchanged.

## Enable the extension

```java
import dev.tachyonmcp.extensions.skills.ClasspathSkillsRegistry;
import dev.tachyonmcp.extensions.skills.FilesystemSkillsRegistry;
import dev.tachyonmcp.extensions.skills.SkillsExtension;
import java.nio.file.Path;

var server = TachyonServer.builder()
        .withExtensions(SkillsExtension.builder()
                .registry(new FilesystemSkillsRegistry(Path.of("skills")))            // every subdirectory with a SKILL.md
                .registry(new ClasspathSkillsRegistry("bundled-skills"))        // same, packaged inside the jar
                .build())
        .port(8080)
        .build();
server.start();
```

Skills extension ID is `io.modelcontextprotocol/skills`. By default any client can call its methods,
declared or not; strict [negotiation](#extension-negotiation) is opt-in. Skill files remain
available through the standard Resources API.

## Skill directory layout

A skill is a directory following [Agent Skills specification](https://agentskills.io/specification).
It contains a `SKILL.md` file with YAML frontmatter, plus any supporting files, e.g.:

```
git-workflow/
├── SKILL.md
└── references/
    └── BRANCHING.md
```

SKILL.md content might look like:
```markdown
---
name: git-workflow
description: Follow this team's Git conventions for branching and commits
---

# Git Workflow

...
```

Two rules are enforced at scan time, both throwing `IllegalArgumentException`:

- `SKILL.md` frontmatter must declare a non-blank `name` and `description`.
- The final segment of the skill's path must equal the frontmatter `name` — `git-workflow/SKILL.md` must declare `name: git-workflow`.

Any other frontmatter field (`metadata`, `license`, ...) passes through verbatim into `skills/list` and `skills/get` responses.

### Ignoring files

Both built-in skill registries filter scanned files against `META-INF/dev/tachyonmcp/extensions/skills/.mcpignore`, a gitignore-style pattern file bundled with `tachyon-extensions-skills`. It ships pre-loaded with OS junk (`.DS_Store`, `Thumbs.db`, `.Trash-*`, ...) so these never turn into skill resources.

- Blank lines and `#` comments are skipped.
- A pattern with no `/` (e.g. `*.tmp`) matches any path segment at any depth, excluding whole subdirectories.
- A pattern containing `/` — anywhere, including a leading `/` — matches the file's full relative path from the skill root instead of any segment.

To use your own patterns, place a `.mcpignore` at that same resource path earlier on the classpath (or context classloader): it's resolved with `getResourceAsStream`, so the first one found replaces the bundled file entirely rather than merging with it.

## Adding skills to the builder

`SkillsExtension.Builder` has exactly one way to add skills: `registry(SkillsRegistry)`. The
registry — not the builder — resolves where skills come from; the builder just collects them.

| Registry | Source | Skill path |
|---|---|---|
| `new FilesystemSkillsRegistry(Path)` | filesystem directory of skills | each subdirectory name |
| `new FilesystemSkillsRegistry(Path, String)` | a single filesystem skill directory | explicit path |
| `new ClasspathSkillsRegistry(String)` | classpath directory of skills (works inside a jar) | each subdirectory name |
| `new ClasspathSkillsRegistry(String, String)` | a single classpath skill directory | explicit path |
| a custom `SkillsRegistry` | anywhere | as returned by the registry |

The explicit-path constructors let you namespace skills instead of using the bare directory name:

```java
SkillsExtension.builder()
        .registry(new FilesystemSkillsRegistry(Path.of("skills/git-workflow"), "team/git-workflow"))
        .registry(new ClasspathSkillsRegistry("skills/pdf-processing", "acme/pdf-processing"))
        .build();
```

This serves `skill://team/git-workflow/SKILL.md` and `skill://acme/pdf-processing/SKILL.md`; `resources/directory/read` on `skill://` then lists `team` and `acme` as namespace directories.

Registries are merged by `CompositeSkillsRegistry`, which rejects a duplicate skill path across two registries with `IllegalArgumentException` at startup — a config bug fails fast instead of one registry silently shadowing another. A duplicate skill path is a URI collision: silently picking one risks serving the wrong skill's content, so it's never tolerated. Two skills sharing the same frontmatter `name` under different namespace prefixes is not a collision — `name` is a display label, not identity — and both remain fully served (see `docs/architecture/guidance.md` on resource identity).

### Custom registries

Implement `SkillsRegistry` and pass an instance to `registry(...)` to source skills from anywhere — a database, an S3 bucket, a remote catalog:

```java
public interface SkillsRegistry {
    List<Skill> skills();
    byte[] readFile(String fileUri); // or null if unknown
}
```

`Skill` and `SkillFile` are the same records the built-in registries produce (skill path, parsed
frontmatter, and per-file sizes and `sha256:`-prefixed digests) — see `SkillsRegistry.java` for the
exact shape.

## How files are served

Every file in every skill becomes a standard MCP resource at
`skill://<skill-path>/<relative-path>`. Clients can list and read these resources without
negotiating the Skills extension. Content type comes from `MimeTypes.guess(fileName)`
(`tachyon-core`) and decides transport: text types (`text/*`, `application/json`,
`application/yaml`, ...) are served as `TextResourceContents`; everything else as base64
`BlobResourceContents`.

Filesystem-backed files are re-read from disk on every `resources/read` — a file edited after the server started is served fresh, though its digest and size in `skills/list`/`skills/get` (computed once at scan time) won't reflect the edit until restart. Classpath-backed files are read once at scan time and cached in memory.

## MCP methods

| Method | Description |
|---|---|
| `skills/list` | List every registered skill: URI, frontmatter, and per-file digests and sizes. No pagination — see [Caveats](#caveats). |
| `skills/get` | Fetch one skill by its `skill://.../SKILL.md` URI. `-32602` if unknown. |
| `resources/directory/read` | List the immediate children of a `skill://` directory URI — a skill root, a subdirectory, or the `skill://` namespace root. `-32602` if the URI names no known directory. |

`skills/list`:

```json
{"jsonrpc":"2.0","id":1,"method":"skills/list","params":{"_meta":{"io.modelcontextprotocol/skills":{}}}}
```

```json
{
  "jsonrpc":"2.0","id":1,
  "result":{
    "skills":[
      {
        "uri":"skill://git-workflow/SKILL.md",
        "frontmatter":{"name":"git-workflow","description":"Follow this team's Git conventions for branching and commits"},
        "resources":[
          {"uri":"skill://git-workflow/SKILL.md","digest":"sha256:b9de7cc1...","size":234},
          {"uri":"skill://git-workflow/references/BRANCHING.md","digest":"sha256:c23e5f30...","size":68}
        ]
      }
    ]
  }
}
```

`resources/directory/read` walks a skill's file tree one level at a time, folding subdirectories into `inode/directory` entries:

```json
{"jsonrpc":"2.0","id":1,"method":"resources/directory/read","params":{"uri":"skill://pdf-processing","_meta":{"io.modelcontextprotocol/skills":{}}}}
```

```json
{
  "jsonrpc":"2.0","id":1,
  "result":{
    "resources":[
      {"uri":"skill://pdf-processing/SKILL.md","name":"SKILL.md","mimeType":"text/markdown"},
      {"uri":"skill://pdf-processing/scripts","name":"scripts","mimeType":"inode/directory"},
      {"uri":"skill://pdf-processing/templates","name":"templates","mimeType":"inode/directory"}
    ]
  }
}
```

A skill's files always appear in the standard `resources/list` and remain directly readable through
`resources/read`. The extension methods are additive discovery and manifest operations, not a
replacement transport.

## Extension negotiation

The Skills extension methods are `skills/list`, `skills/get`, and `resources/directory/read`. MCP
2025-11-25 clients declare support in `initialize.params.capabilities.extensions`; MCP 2026-07-28
clients declare it on each request in `_meta."io.modelcontextprotocol/clientCapabilities".extensions`.

`SkillsExtension` defaults to `ExtensionNegotiation.OPTIONAL`. The Skills extension requires only the
server's declaration: a client calls `skills/list` and `skills/get` after it sees the extension in
the server's capabilities. So the three extension methods serve every client, including clients
such as MCP Inspector that don't declare the extension, and stateless servers serving MCP
2025-11-25 clients. An undeclared call is not marked as negotiated for the request.

To reject clients that don't declare the extension, opt in to `REQUIRED`:

```java
SkillsExtension.builder()
        .registry(new ClasspathSkillsRegistry("skills"))
        .negotiation(ExtensionNegotiation.REQUIRED)
        .build();
```

Those clients then get Missing Required Client Capability from the three extension methods
(`-32021` + HTTP 400 on 2026-07-28, `-32003`, Tachyon-defined, on 2025-11-25) with
`data.requiredCapabilities.extensions."io.modelcontextprotocol/skills"`. They can still discover
skill files through `resources/list` and fetch a known `skill://` URI through `resources/read`:
[SEP-2640]'s baseline resource transport.

⚠️ With `REQUIRED`, MCP 2025-11-25 clients also need server sessions
(`.session(session -> session.enabled())`). Their declaration is made once in `initialize`, so a
stateless server can't see it on later requests and rejects them with `-32003`. See
[what clients must send](_index.md#what-clients-must-send).

The policy changes dispatch only. The extension and its `{"directoryRead": true}` setting are
advertised the same way under both policies.

`SkillsExtension` uses `AdvertiseMode.ALWAYS`, so the server advertises
`io.modelcontextprotocol/skills` even when the client has not declared it. `serverSettings()` reports
`{"directoryRead": true}` for the extension key, signaling that negotiated clients may use
`resources/directory/read`. `AdvertiseMode` controls capability advertisement; it does not control
base resource visibility.

## Caveats

- **No `skills/list` pagination.** A `cursor` param returns `-32602 Invalid params`. Skill catalogs are expected to be small and bounded; add a server-side cursor if that stops holding.
- **`@ExperimentalApi`.** The package (`dev.tachyonmcp.extensions.skills`, module `tachyon-extensions-skills`) is marked experimental — the shape may still change before [SEP-2640][SEP-2640] itself stabilizes.
           
[SEP-2640]: https://modelcontextprotocol.io/seps/2640-skills-extension "SEP-2640: Skills Extension"
