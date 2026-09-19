---
title: API stability
tags: [concept, api, compat]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/, tachyon-api/revapi.json, tachyon-core/revapi.json, tachyon-core/pom.xml, tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/ServerEngine.java]
updated: 2026-09-19
commit: 43ee83a1
---

# 🧱 API stability

Verdict: OSS library → every public type is contract unless marked. Three class-retention markers + package conventions + revapi check in `make ci`.

## 🏷️ Markers

| Annotation | Meaning | Proof |
|---|---|---|
| `@ExperimentalApi(since)` | public, may change | `ExperimentalApi` |
| `@InternalApi(since)` | public for cross-package wiring only; no compat | `InternalApi` |
| `@LegacyApi(since)` | kept for older protocol revision (e.g. task list/awaitResult) | `LegacyApi` |

Package-level: `package-info.java` with `@NullMarked` (+ `@InternalApi` for `transport.netty.*`, `protocol.*.transport`). Nullability JSpecify.

## 🗂️ What counts as public

| Stable-ish | Internal |
|---|---|
| `tachyon-api` (`dev.tachyonmcp.api..`) | `dev.tachyonmcp.core.server.internal` (`ServerEngine` — "Not a stability contract" `ServerEngine`) |
| `TachyonServer`, `ServerBuilder`, `core.server.config.*` records | `McpDispatcher`, `RpcMethodHandler`, registries `Default*`, `transport.*`, `protocol.*` mappers, `runtime.*` |
| `SessionStore`/`SessionEventStore`/`SessionSnapshot` (`@ExperimentalApi since 1.0.0-beta.27`) | `Session` (`@InternalApi since beta.27`) |
| Kotlin `public` DSL | Kotlin `internal` |

Experimental notable: `Tasks`, `TaskConnector`, `ToolHandler`, `TypedToolFn`, `ExtensionContext`, `ServerExtension`, `NetworkConfig`, `ObservabilityConfig`, `annotations(...)`, `pipelineCustomizer`, `sendRequest`, `comment()`.

## 🧬 Immutables

Descriptors/requests/config in api use `@Value.Immutable` + style `typeImmutable="Default*"`, package-private impls, `builder()`/`of(...)` statics (e.g. `ToolDescriptor`, `ToolRequest`, `RuntimeConfig`). Generated to `target/generated-sources/annotations` (included in revapi sourcepath `pom.xml`).

## 🔍 Revapi

`revapi:check` is bound to `verify` in the modules that declare the plugin, but skipped by default (`revapi.skip=true` in `pom.xml`) because the baseline resolves from Central. `make revapi` runs `verify -Drevapi.skip=false` on those modules, so the jar it compares is built in the same reactor pass — no separate `make package` first. Compares against baseline `oldVersion`; per-module `revapi.json` ignores (generated ts2java classes excluded, commit `3cc96c5f`). Part of `make ci`.

## 📝 Javadoc rule

Native MCP annotation contracts: [[declarative-configuration]].

AGENTS.md: public API needs Javadoc; no code comments unless explaining why. API/registry naming rules: `docs/architecture/guidance.md`.

Related: [[tachyon-api]], [[tachyon-core]].

Native parameter annotations are experimental: [McpParam](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/McpParam.java) and [Meta](../../tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/Meta.java). Binding rules: [[declarative-configuration]].
