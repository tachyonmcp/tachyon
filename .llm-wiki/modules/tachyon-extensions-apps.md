---
title: tachyon-extensions-apps
tags: [module, extensions, apps]
sources: [extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/, extensions/tachyon-extensions-apps/pom.xml, extensions/tachyon-extensions-apps/protocol/, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/AppsExtensions.kt, tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/AppsScope.kt]
updated: 2026-10-14
commit: d8dc6214
---

# 🖼️ tachyon-extensions-apps

Verdict: server half of SEP-1865 MCP Apps. Declarative only: `ui://` resources (`text/html;profile=mcp-app`) + tool `_meta.ui` + extension id `io.modelcontextprotocol/ui`. **No JSON-RPC methods**, no negotiation knob, no revapi (no released baseline).

## 🖼️ AppsExtension

`extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java`

| Aspect | Value | Proof |
|---|---|---|
| registration | `ConfigurableExtension` + `@ProvidedBy(AppsExtensionProvider)` ⇒ `withExtension(AppsExtension.class, b -> b.app(...))`; `Builder` ctor pkg-private | [AppsExtensionProvider](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtensionProvider.java), [AppsExtension.Builder](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) |
| id / mime / scheme | `ID`, `MIME_TYPE`, `URI_SCHEME` constants | [AppsExtension#ID](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) |
| advertise | `ALWAYS`, empty settings (spec defines `mimeTypes` only for the client) | [AppsExtension#advertiseMode](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) |
| negotiation | SPI default `OPTIONAL`; nothing to gate, views are base resources | [ServerExtension#negotiation](../../tachyon-api/src/main/java/dev/tachyonmcp/api/server/extensions/ServerExtension.java) |
| bootstrap | each `AppResource` → `ResourceDescriptor` (name/uri/title/description, mime fixed, `_meta` = `AppMeta#toMeta` or none) + `TextResourceContents` with the **same** `_meta` (spec: host prefers content item, falls back to listing) | [AppsExtension#bootstrap](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) |
| tool link | `toolMeta(uri, UiToolVisibility...)` ⇒ unmodifiable `{"ui": {"resourceUri", "visibility"?}}` maps built by hand (generated `McpUiToolMeta` not used here); `visibility` omitted when none (spec default `["model","app"]`); dedupes, keeps order; non-`ui://` ⇒ IAE. 🗑️ deprecated flat `ui/resourceUri` never emitted | [AppsExtension#toolMeta](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) |
| builder | `app(AppResource)`; duplicate uri ⇒ IAE **inside the configurer** (thrown from `withExtension`, not `build()`) | [AppsExtension.Builder#app](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) |

## 📦 Model

| Type | Shape | Proof |
|---|---|---|
| `AppResource` | record `(uri, name, title?, description?, Supplier<String> html, AppMeta meta)`; compact ctor: `ui://` + non-blank name else IAE; builder `html(String)` / `html(Supplier)`, `meta` default `NONE` | [AppResource](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppResource.java) |
| `AppMeta` | record of 4 CSP lists + `Set<Permission>` + `domain?` + `prefersBorder?`; `toMeta()` ⇒ `{"ui": <tree>}`: builds ts2java `UIResourceMeta` (from `protocol/apps-draft.ts`), encodes via `UIResourceMetaCodec` + `CodecSupport#encodeToTree`; empties ⇒ `null` ⇒ omitted, all-empty ⇒ `Map.of()` ⇒ resource has no `_meta` | [AppMeta#toMeta](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppMeta.java) |
| `AppMeta.Permission` | `CAMERA/MICROPHONE/GEOLOCATION/CLIPBOARD_WRITE` → generated `UIResourceMeta.Permissions` slots (`{"camera": {}}`…); wire names from the model | [AppMeta.Permission](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppMeta.java) |
| codegen | `protocol/apps-draft.ts` = ext-apps `specification/draft/apps.mdx` (`UIResource`, `McpUiResourceCsp`, `UIResourceMeta`, `McpUiToolMeta`) + `McpUiToolVisibility` alias from ext-apps `src/spec.types.ts` (typeMappings ⇒ handwritten `UiToolVisibility` enum; spec's inline `Array<"model" \| "app">` would give `List<String>`). Config adds `UIResource.title?` (spec omits it; core `Resource` has it) and `basePackage` = core `v2026_07_28`. Generated `UIResource` unused: descriptor goes through core `ResourceDescriptor` | [apps-draft.ts](../../extensions/tachyon-extensions-apps/protocol/apps-draft.ts) |

## 🐘 Kotlin

`apps { app(uri, name, title?, description?, meta = NONE) { html } }` ([apps](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/AppsExtensions.kt), [AppsScope#app](../../tachyon-kotlin/src/main/kotlin/dev/tachyonmcp/kotlin/server/config/AppsScope.kt)) wraps `withExtension(AppsExtension::class.java)`; tool link stays Java static [AppsExtension#toolMeta](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) via `tool(meta = …)`. Optional dep: `tachyon-extensions-apps` `<optional>` in [tachyon-kotlin/pom.xml](../../tachyon-kotlin/pom.xml).

## ⚠️ Not done (by design)

- Spec SHOULD "check client caps before registering UI tools": tools are static at bootstrap; hosts without Apps ignore `_meta.ui`.
- No startup check that tool `resourceUri` resolves to a registered view: [AppsExtension#toolMeta](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) only validates the `ui://` scheme, [AppsExtension#bootstrap](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) registers views independently.
- `blob` content, per-read `_meta` override: [AppsExtension#bootstrap](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppsExtension.java) serves `TextResourceContents` only, `_meta` fixed per [AppResource](../../extensions/tachyon-extensions-apps/src/main/java/dev/tachyonmcp/extensions/apps/AppResource.java).

## 🧪 Tests

[AppsExtensionE2eTest#advertisesExtensionOnDiscover](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionE2eTest.java) + [#advertisesExtensionOnLegacyInitialize](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionE2eTest.java) (2026-07-28 + 2025-11-25 advertisement), [#listsUiResourcesWithMetaAndMimeType](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionE2eTest.java) / [#readsUiResourceAsHtmlWithMeta](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionE2eTest.java) (list/read `_meta.ui`), [#toolsCarryUiMetaWithoutDeprecatedFlatKey](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionE2eTest.java) (tools/list meta), [#undeclaredClientStillReadsUiResource](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionE2eTest.java) (undeclared client reads); [AppsExtensionTest](../../extensions/tachyon-extensions-apps/src/test/java/dev/tachyonmcp/extensions/apps/AppsExtensionTest.java) (validation: `#rejectsNonUiScheme`, `toMeta`: `#metaOmitsEmptyCspAndPermissions`, `toolMeta`: `#toolMetaDedupesVisibilityKeepingOrder`); [AppsDslTest](../../tachyon-kotlin/src/test/kotlin/dev/tachyonmcp/kotlin/server/AppsDslTest.kt) (Kotlin). Example `examples/mcp-apps` (snapshot-only module: `examples/pom.xml` `snapshot-examples` profile, artifact unreleased).

Related: [[extensions]], [[tachyon-extensions-skills]].
