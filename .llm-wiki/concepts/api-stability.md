---
title: API stability
tags: [concept, api, compat]
sources: [tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/, tachyon-api/revapi.json, tachyon-core/revapi.json, tachyon-core/pom.xml, tachyon-core/src/main/java/dev/tachyonmcp/core/server/internal/ServerEngine.java]
updated: 2026-09-15
commit: 751331f4
---

# 🧱 API stability

Verdict: OSS library → every public type is contract unless marked. Three class-retention markers + package conventions + revapi check in `make ci`.

## 🏷️ Markers

| Annotation | Meaning | Proof |
|---|---|---|
| `@ExperimentalApi(since)` | public, may change | `tachyon-api/src/main/java/dev/tachyonmcp/api/annotations/ExperimentalApi.java:34` |
| `@InternalApi(since)` | public for cross-package wiring only; no compat | `InternalApi.java:18` |
| `@LegacyApi(since)` | kept for older protocol revision (e.g. task list/awaitResult) | `LegacyApi.java:28` |

Package-level: `package-info.java` with `@NullMarked` (+ `@InternalApi` for `transport.netty.*`, `protocol.*.transport`). Nullability JSpecify.

## 🗂️ What counts as public

| Stable-ish | Internal |
|---|---|
| `tachyon-api` (`dev.tachyonmcp.api..`) | `dev.tachyonmcp.core.server.internal` (`ServerEngine` — "Not a stability contract" `ServerEngine.java:27-37`) |
| `TachyonServer`, `ServerBuilder`, `core.server.config.*` records | `McpDispatcher`, `RpcMethodHandler`, registries `Default*`, `transport.*`, `protocol.*` mappers, `runtime.*` |
| `SessionStore`/`SessionEventStore`/`SessionSnapshot` (`@ExperimentalApi since 1.0.0-beta.27`) | `Session` (`@InternalApi since beta.27`) |
| Kotlin `public` DSL | Kotlin `internal` |

Experimental notable: `Tasks`, `TaskConnector`, `ToolHandler`, `TypedToolFn`, `ExtensionContext`, `ServerExtension`, `NetworkConfig`, `ObservabilityConfig`, `annotations(...)`, `pipelineCustomizer`, `sendRequest`, `comment()`.

## 🧬 Immutables

Descriptors/requests/config in api use `@Value.Immutable` + style `typeImmutable="Default*"`, package-private impls, `builder()`/`of(...)` statics (e.g. `ToolDescriptor.java:19-24`, `ToolRequest.java:16-21`, `RuntimeConfig.java:13-18`). Generated to `target/generated-sources/annotations` (included in revapi sourcepath `tachyon-core/pom.xml:244`).

## 🔍 Revapi

`make revapi` compares against baseline `oldVersion`; per-module `revapi.json` ignores (generated ts2java classes excluded, commit `3cc96c5f`). Part of `make ci`.

## 📝 Javadoc rule

Native MCP annotation contracts: [[declarative-configuration]].

AGENTS.md: public API needs Javadoc; no code comments unless explaining why. API/registry naming rules: `docs/architecture/guidance.md`.

Related: [[tachyon-api]], [[tachyon-core]].
