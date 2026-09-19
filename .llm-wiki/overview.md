---
title: Overview
tags: [concept, architecture]
sources: [pom.xml, tachyon-core/pom.xml, integrations/pom.xml, extensions/pom.xml, tachyon-core/src/main/java/dev/tachyonmcp/core/server/TachyonServer.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/ServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultTachyonServer.java]
updated: 2026-09-19
commit: c98d62cf
---

# 🛰️ Overview

Tachyon = MCP **server** library. Netty Streamable-HTTP transport, JSON-RPC, virtual-thread handlers. Java 21 first, Kotlin DSL on top. Speaks two MCP revisions at once: **2025-11-25** (session, `initialize`) and **2026-07-28** (stateless, per-request `_meta`). See [[protocol-versions]].

## 📦 Modules

Root `pom.xml` version `1.0.0-SNAPSHOT`. Java `21` (`pom.xml`), Kotlin `2.2.21` (`pom.xml`), Jackson **3** (`pom.xml`, package `tools.jackson.*`), Netty `4.2+` (`pom.xml`).

```mermaid
graph TD
  api[tachyon-api] --> core[tachyon-core]
  core --> kotlin[tachyon-kotlin]
  api --> ktschema[tachyon-kotlin-kt-schema]
  core --> ext[extensions/tachyon-extensions]
  core --> testkit[tachyon-testkit]
  core --> integ[integrations/*]
  core --> e2e[e2e]
  core --> conf[conformance]
  bom[tachyon-bom]
```

| Module | Role | Page |
|---|---|---|
| `tachyon-api` | Public SAMs, descriptors, results, JSON SPI. No Netty. | [[tachyon-api]] |
| `tachyon-core` | Everything runtime | [[tachyon-core]] |
| `tachyon-kotlin` | DSL + coroutines | [[tachyon-kotlin]] |
| `tachyon-kotlin-kt-schema` | Reflection JSON-schema factory for Kotlin classes | [[tachyon-kotlin]] |
| `extensions/tachyon-extensions` | Sample tools; depends on skills for compat | [[tachyon-extensions]] |
| `extensions/tachyon-extensions-skills` | Skills extension (`skill://`) | [[tachyon-extensions-skills]] |
| `tachyon-testkit` | HTTP test clients/asserts | [[tachyon-testkit]] |
| `integrations/*` | 6 modules (`pom.xml`) | [[integrations]] |
| `tachyon-bom` | `dependencyManagement` only, **no `<parent>`** so importing it never leaks the build's own third-party versions | — |
| `e2e`, `conformance`, `reports` | profile-only modules (`pom.xml` `<profiles>`) | [[testing]] |

## 🎯 The one type users hold

`TachyonServer` interface `TachyonServer` — `AutoCloseable`, exposes registries `tools()/resources()/prompts()/tasks()/completions()`, `annotations(...)`, `notifications()`, `start()`, `port()`, `close()`. Factory `TachyonServer.builder()` → `DefaultServerBuilder` (`DefaultServerBuilder#network`).

Two-phase: `build()` constructs server + runs registrations, **no socket**; `start()` binds Netty.

## 🏗️ Build → start flow

1. `DefaultServerBuilder.build()` [DefaultServerBuilder#build](../tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java):
   - default in-memory `SessionEventStore` + `SessionStore`
   - tasks enabled ⇒ auto-add `TasksExtension` if absent
   - executor = `threadFactory` given ? thread-per-task : VT executor `tachyon-vt-*` (`DefaultTachyonServer#defaultExecutor`)
   - `new DefaultTachyonServer(...)` → run `withTools/withResources/...` callbacks → annotation providers → `validateConfiguration()`; any throw ⇒ `close()` + rethrow.
2. `DefaultTachyonServer` ctor `DefaultTachyonServer`: registries, `registerDefaults()` (`DefaultTachyonServer#registerDefaults`), `bootstrapExtensions()` (`DefaultTachyonServer#bootstrapExtensions`), change listeners (`DefaultTachyonServer#setupChangeListeners`), session janitor if sessions on.
3. `start()` `DefaultTachyonServer#start`: lifecycle `ReentrantLock`, `new NettyServer(this, NettyServerConfig…)`, record bound host/port.
4. `close()` `DefaultTachyonServer` → see [[concurrency]].

## 🧩 Internal seams

| Seam | Where | Page |
|---|---|---|
| `ServerEngine` (internal SPI, extends `TachyonServer`) | `ServerEngine` | [[tachyon-core]] |
| `RpcMethodHandler<I,O>` decode→handle | `RpcMethodHandler` | [[request-lifecycle]] |
| `Protocol` SPI (ServiceLoader) | `Protocol` | [[protocol-versions]] |
| `ProtocolRequestMapper` / `ProtocolResponseMapper` | `tachyon-core/src/main/java/dev/tachyonmcp/core/protocol/` | [[protocol-versions]] |
| `ServerExtension` | `ServerExtension` | [[extensions]] |
| `ObservationListener` | `ObservationListener` | [[observability]] |
| `SessionStore` / `SessionEventStore` | `tachyon-core/src/main/java/dev/tachyonmcp/core/server/session/` | [[sessions]] |
| `TaskConnector` | `TaskConnector` | [[tasks]] |

## 🔧 Build commands

`Makefile` targets: `build` (mvn verify), `test`, `lint`, `format`, `ci` = one reactor for clean + lint + build + revapi then the JMH gate, `ci-lite` (same without report plugins), `conformance`, `examples`, `mcp-inspector`. CI runs `make ci` on JDK 21 and `make ci-lite` on the compatibility JDKs (`.github/workflows/build.yml`). Details [[testing]].

`make lint` also runs `.github/scripts/check-poms.py`: the parentless `tachyon-bom` inherits nothing, so that script asserts it lists exactly the `dev.tachyonmcp` artifacts the root pom manages and pins the same plugin versions, and that every published module declares a unique `automatic.module.name` (written to `Automatic-Module-Name`). `maven-enforcer-plugin` covers Maven/Java version and plugin-version pinning. Python 3 is required at build time for `tachyon-core`'s `ts2java.py` generation (`-Dts2java.skip=true` opts out).
