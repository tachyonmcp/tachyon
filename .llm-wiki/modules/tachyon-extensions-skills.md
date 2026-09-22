---
title: tachyon-extensions-skills
tags: [module, extensions, skills]
sources: [extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/, extensions/tachyon-extensions-skills/src/main/resources/]
updated: 2026-09-21
commit: a5bf0b18
---

# 🎓 tachyon-extensions-skills

Verdict: standalone module (split out of `tachyon-extensions`) exposing `SKILL.md` directories as `skill://` resources + `skills/list|get` + `resources/directory/read`. `tachyon-extensions` keeps a transitive dependency on this module so `dev.tachyonmcp.extensions.skills.*` stays on the classpath of existing `tachyon-extensions` consumers.

## 🎓 SkillsExtension

`extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java`

| Aspect | Value | Proof |
|---|---|---|
| id | `io.modelcontextprotocol/skills` | [SkillsExtension#ID](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| advertise | `ALWAYS`, settings `{directoryRead: true}` | [SkillsExtension#advertiseMode](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#serverSettings](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| negotiation | builder `negotiation(...)`, **default `REQUIRED`** ⇒ undeclared ⇒ missing required client capability: -32003 (2025-11-25), -32021 + HTTP 400 (2026-07-28); base `resources/list\|read` still served. `OPTIONAL` opt-in serves undeclared clients (e.g. MCP Inspector; same advertisement) | [SkillsExtension#negotiation](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension.Builder#negotiation](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java); codes in `McpResponseMapper` (v2025_11_25, v2026_07_28) |
| bootstrap | every skill file → resource (`SKILL.md` named by frontmatter `name` + `description`); text vs blob by `MimeTypes.isText` | [SkillsExtension#bootstrap](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#contents](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| methods | `skills/list` (no pagination; `ttlMs`, `cacheScope`), `skills/get {uri}`, `resources/directory/read {uri}` (children with `inode/directory`) | [SkillsExtension#listSkills](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#getSkill](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java), [SkillsExtension#readDirectory](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |
| builder | `registry(...)`, `cacheTtlMs ≥ 0`, `cacheScope public\|private` | [SkillsExtension.Builder](../../extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java) |

Skill model `SkillsRegistry.Skill(skillPath, frontmatter, files)`, `SkillFile(relativePath, uri, mimeType, sha256 digest, size)`; `skillUri = skill://<path>/SKILL.md` `Skill`.

## 📂 Registries

| Impl | Source | Proof |
|---|---|---|
| `FilesystemSkillsRegistry(root)` / `(dir, skillPath)` | dir of skill dirs / single | `FilesystemSkillsRegistry#FilesystemSkillsRegistry` |
| `ClasspathSkillsRegistry(resource[, skillPath])` | `file:` or `jar:` URLs | `ClasspathSkillsRegistry` |
| `CompositeSkillsRegistry` | dup skill path ⇒ IAE | `CompositeSkillsRegistry#CompositeSkillsRegistry` |
| `BaseSkillsRegistry` | dir scan lenient (skip invalid, warn), single strict | `BaseSkillsRegistry` |

Validation `SkillsScanner.buildSkill`: `SKILL.md` required, YAML frontmatter (SnakeYAML `SafeConstructor`) with non-blank `name`+`description`, last path segment == `name` `SkillsScanner#buildSkill`, `FrontmatterParser#parse`. Ignore globs from classpath `META-INF/dev/tachyonmcp/extensions/skills/.mcpignore` (gitignore-ish: anchored `/`, dir `/` suffix) `IgnoreRules`.

## 🧪 Tests

`SkillsExtensionTest`, `SkillsExtensionE2eTest`, registry/parser/ignore tests; fixtures `extensions/tachyon-extensions-skills/src/test/data/skills/`, `src/test/resources/skills/`. Example app `examples/mcp-skills`.

## 🪶 Backward compat

Split out of `tachyon-extensions` (`[[tachyon-extensions]]`); no revapi baseline yet for this artifact (skipped, same as `integrations/*` modules until first release). `extensions/tachyon-extensions/revapi.json` waives the resulting `java.class.removed` diffs for the moved public types.

Related: [[tachyon-extensions]], [[extensions]].
