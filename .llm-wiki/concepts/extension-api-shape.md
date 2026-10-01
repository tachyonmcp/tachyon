---
title: Extension API shape
tags: [concept, extensions, kotlin]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/TaskConnector.java, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TachyonServerBuilder.kt, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TasksExtensions.kt, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/SkillsExtensions.kt, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/domain/TaskSnapshotFactories.kt, extensions/tachyon-extensions-skills/src/main/java/dev/tachyonmcp/extensions/skills/SkillsExtension.java]
updated: 2026-10-01
commit: cf8f85f7
---

# 🧩 Extension API shape

Java registration is shared; Kotlin adaptation differs by feature. Review gaps live in [[findings]].

| Surface | Current shape | Proof |
|---|---|---|
| Configurable extensions | Class selects typed builder; generic Kotlin receiver forwards to Java | [TachyonServerBuilder#withExtension](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TachyonServerBuilder.kt) |
| Skills | Imported builder extension; property scope with Kotlin duration, registry composition, cache hints, negotiation | [skills](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/SkillsExtensions.kt), [SkillsScope#applyTo](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/SkillsExtensions.kt) |
| Tasks | Required connector plus Kotlin property scope; Kotlin durations converted at boundary | [tasks](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TasksExtensions.kt), [TasksScope#applyTo](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TasksExtensions.kt) |
| Connector | Required get/cancel/update; optional legacy list/awaitResult; missing required hooks rejected on build | [TaskConnector.Builder#build](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/tasks/TaskConnector.java) |
| Task snapshots | Receiver builder; Kotlin time types; copy starts from previous snapshot and advances revision; Java validates result; explicit synthetic JVM facade `TaskSnapshots` | [TaskSnapshotBuilder#build](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/domain/TaskSnapshotFactories.kt) |
| Custom methods | Bootstrap registers synchronous throwing SAM | [ExtensionContext#registerHandler](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionContext.java), [ExtensionMethodHandler#handle](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ExtensionMethodHandler.java) |

Canonical behavior: [[extensions]], [[tasks]], [[tachyon-extensions-skills]], [[tachyon-kotlin]].

Repeated `tasks`/`skills` calls replace scoped settings, including defaults; generic `withExtension` configurers mutate the retained Java builder. [TasksScope#applyTo](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TasksExtensions.kt), [SkillsScope#applyTo](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/SkillsExtensions.kt), [TachyonServerBuilder#withExtension](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/TachyonServerBuilder.kt).
