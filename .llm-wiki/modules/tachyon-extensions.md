---
title: tachyon-extensions
tags: [module, extensions]
sources: [extensions/tachyon-extensions/src/main/java/dev/tachyonmcp/extensions/, extensions/tachyon-extensions/src/main/resources/, extensions/tachyon-extensions/pom.xml]
updated: 2026-09-19
commit: 2dad742b
---

# 🧩 tachyon-extensions

Verdict: sample tool handlers built only on public SPI. Skills extension moved out to its own module — see [[tachyon-extensions-skills]].

## 🔧 Sample tools

- `EchoToolHandler` — `echo {message}` `EchoToolHandler.java`.
- `YouComSearchTool(YouComSearchConfig)` — `you-search`, JDK `HttpClient`, free tier w/o key, `X-API-Key` otherwise, structured + text results `YouComSearchTool`.

## 🪶 Skills backward compat

`extensions/tachyon-extensions/pom.xml` still depends on `dev.tachyonmcp:tachyon-extensions-skills` (non-optional), so `dev.tachyonmcp.extensions.skills.*` stays resolvable transitively for consumers who only declared `tachyon-extensions`. `extensions/tachyon-extensions/revapi.json` waives the `java.class.removed` diffs the move produces for the moved public types (`BaseSkillsRegistry`, `ClasspathSkillsRegistry`, `CompositeSkillsRegistry`, `FilesystemSkillsRegistry`, `SkillsExtension`, `SkillsRegistry`).

## 🧪 Tests

`EchoToolHandler`/`YouComSearchTool` tests under `extensions/tachyon-extensions/src/test/java/dev/tachyonmcp/extensions/tools/`. Skills tests moved with the code — see [[tachyon-extensions-skills]].

Related: [[tachyon-extensions-skills]], [[extensions]].
