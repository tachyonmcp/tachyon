---
title: tachyon-extensions
tags: [module, extensions, skills]
sources: [tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/, tachyon-extensions/src/main/resources/]
updated: 2026-09-13
commit: 582f9c52
---

# 🧩 tachyon-extensions

Verdict: optional add-ons built only on public SPI. Main piece = **Skills extension** exposing `SKILL.md` directories as `skill://` resources + `skills/list|get` + `resources/directory/read`. Plus sample tools.

## 🎓 SkillsExtension

`tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java`

| Aspect | Value | Proof |
|---|---|---|
| id | `io.modelcontextprotocol/skills` | `:46` |
| advertise | `ALWAYS`, settings `{directoryRead: true}` | `:88-95` |
| `_meta` envelope | **not required** | `:83-85` (fix `582f9c52`) |
| bootstrap | every skill file → resource (`SKILL.md` named by frontmatter `name` + `description`); text vs blob by `MimeTypes.isText` | `:102-132` |
| methods | `skills/list` (no pagination; `ttlMs`, `cacheScope`), `skills/get {uri}`, `resources/directory/read {uri}` (children with `inode/directory`) | `:119-175` |
| builder | `registry(...)`, `cacheTtlMs ≥ 0`, `cacheScope public|private` | `:258-314` |

Skill model `SkillsRegistry.Skill(skillPath, frontmatter, files)`, `SkillFile(relativePath, uri, mimeType, sha256 digest, size)`; `skillUri = skill://<path>/SKILL.md` `SkillsRegistry.java:39-56`.

## 📂 Registries

| Impl | Source | Proof |
|---|---|---|
| `FilesystemSkillsRegistry(root)` / `(dir, skillPath)` | dir of skill dirs / single | `FilesystemSkillsRegistry.java:18-30` |
| `ClasspathSkillsRegistry(resource[, skillPath])` | `file:` or `jar:` URLs | `ClasspathSkillsRegistry.java:51-119` |
| `CompositeSkillsRegistry` | dup skill path ⇒ IAE | `CompositeSkillsRegistry.java:23-40` |
| `BaseSkillsRegistry` | dir scan lenient (skip invalid, warn), single strict | `BaseSkillsRegistry.java:52-136` |

Validation `SkillsScanner.buildSkill`: `SKILL.md` required, YAML frontmatter (SnakeYAML `SafeConstructor`) with non-blank `name`+`description`, last path segment == `name` `SkillsScanner.java:25-47`, `FrontmatterParser.java:30-57`. Ignore globs from classpath `META-INF/dev/tachyonmcp/extensions/skills/.mcpignore` (gitignore-ish: anchored `/`, dir `/` suffix) `IgnoreRules.java:26-121`.

## 🔧 Sample tools

- `EchoToolHandler` — `echo {message}` `tools/echo/EchoToolHandler.java:11-37`.
- `YouComSearchTool(YouComSearchConfig)` — `you-search`, JDK `HttpClient`, free tier w/o key, `X-API-Key` otherwise, structured + text results `tools/youcom/search/YouComSearchTool.java:24-168`.

## 🧪 Tests

`SkillsExtensionTest`, `SkillsExtensionE2eTest`, registry/parser/ignore tests; fixtures `tachyon-extensions/src/test/data/skills/`, `src/test/resources/skills/`. Example app `examples/mcp-skills`.

Related: [[extensions]].
