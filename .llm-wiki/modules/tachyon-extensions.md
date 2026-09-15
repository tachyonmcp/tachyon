---
title: tachyon-extensions
tags: [module, extensions, skills]
sources: [tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/, tachyon-extensions/src/main/resources/]
updated: 2026-09-15
commit: 9eec1092
---

# 🧩 tachyon-extensions

Verdict: optional add-ons built only on public SPI. Main piece = **Skills extension** exposing `SKILL.md` directories as `skill://` resources + `skills/list|get` + `resources/directory/read`. Plus sample tools.

## 🎓 SkillsExtension

`tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java`

| Aspect | Value | Proof |
|---|---|---|
| id | `io.modelcontextprotocol/skills` | [SkillsExtension#ID](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| advertise | `ALWAYS`, settings `{directoryRead: true}` | [SkillsExtension#advertiseMode](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#serverSettings](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| `_meta` envelope | **not required** | [SkillsExtension#requiresMetaEnvelope](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) (fix `582f9c52`) |
| negotiation | builder `negotiation(...)`, **default `REQUIRED`** ⇒ undeclared ⇒ missing required client capability: -32003 (2025-11-25), -32021 + HTTP 400 (2026-07-28); base `resources/list\|read` still served. `OPTIONAL` opt-in serves undeclared clients (e.g. MCP Inspector; same advertisement) | [SkillsExtension#negotiation](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension.Builder#negotiation](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java); codes in `McpResponseMapper` (v2025_11_25, v2026_07_28) |
| bootstrap | every skill file → resource (`SKILL.md` named by frontmatter `name` + `description`); text vs blob by `MimeTypes.isText` | [SkillsExtension#bootstrap](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#contents](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| methods | `skills/list` (no pagination; `ttlMs`, `cacheScope`), `skills/get {uri}`, `resources/directory/read {uri}` (children with `inode/directory`) | [SkillsExtension#listSkills](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#getSkill](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#readDirectory](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| builder | `registry(...)`, `cacheTtlMs ≥ 0`, `cacheScope public\|private` | [SkillsExtension.Builder](../../tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |

Skill model `SkillsRegistry.Skill(skillPath, frontmatter, files)`, `SkillFile(relativePath, uri, mimeType, sha256 digest, size)`; `skillUri = skill://<path>/SKILL.md` `Skill`.

## 📂 Registries

| Impl | Source | Proof |
|---|---|---|
| `FilesystemSkillsRegistry(root)` / `(dir, skillPath)` | dir of skill dirs / single | `FilesystemSkillsRegistry#FilesystemSkillsRegistry` |
| `ClasspathSkillsRegistry(resource[, skillPath])` | `file:` or `jar:` URLs | `ClasspathSkillsRegistry` |
| `CompositeSkillsRegistry` | dup skill path ⇒ IAE | `CompositeSkillsRegistry#CompositeSkillsRegistry` |
| `BaseSkillsRegistry` | dir scan lenient (skip invalid, warn), single strict | `BaseSkillsRegistry` |

Validation `SkillsScanner.buildSkill`: `SKILL.md` required, YAML frontmatter (SnakeYAML `SafeConstructor`) with non-blank `name`+`description`, last path segment == `name` `SkillsScanner#buildSkill`, `FrontmatterParser#parse`. Ignore globs from classpath `META-INF/dev/tachyonmcp/extensions/skills/.mcpignore` (gitignore-ish: anchored `/`, dir `/` suffix) `IgnoreRules`.

## 🔧 Sample tools

- `EchoToolHandler` — `echo {message}` `EchoToolHandler.java`.
- `YouComSearchTool(YouComSearchConfig)` — `you-search`, JDK `HttpClient`, free tier w/o key, `X-API-Key` otherwise, structured + text results `YouComSearchTool`.

## 🧪 Tests

`SkillsExtensionTest`, `SkillsExtensionE2eTest`, registry/parser/ignore tests; fixtures `tachyon-extensions/src/test/data/skills/`, `src/test/resources/skills/`. Example app `examples/mcp-skills`.

Related: [[extensions]].
