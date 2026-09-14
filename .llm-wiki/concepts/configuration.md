---
title: Configuration
tags: [concept, config, builder]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/ServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/config/]
updated: 2026-09-14
commit: 8c7738c0
---

# 🎛️ Configuration

Verdict: `ServerBuilder` exposes grouped `Consumer<X.Builder>` configurers + shorthand setters; `buildConfig()` yields immutable `ServerConfig` of records. Defaults tuned for **local, stateless, loopback** server.

## 🏗️ Builder groups

`ServerBuilder` `tachyon-core/src/main/java/dev/tachyonmcp/core/server/ServerBuilder.java:14-111`

| Method | Target | Notes |
|---|---|---|
| `info` | `ServerIdentity.Builder` (api, Immutables) | name `tachyon-mcp`, version `0.1`, title, description, websiteUrl, instructions, icons `tachyon-api/.../config/ServerIdentity.java:17-74` |
| `capabilities` | `CapabilitiesConfig.Builder` | per-feature mode/listChanged/pageSize, completions, logging, tasks |
| `session` | `SessionConfig.Builder` | enabled, ttl, janitor, stores, id generator |
| `network` | `NetworkConfig.Builder` | host/port/address, endpoint, idle, body size, CORS, allowedHosts, ioEngine, heartbeat |
| `runtime` | `RuntimeConfig.Builder` (api) | shutdown grace, request timeout, clock |
| `observability` | `ObservabilityConfig.Builder` | slow-request log, listeners, payload capture |
| `json` | `JsonConfig.Builder` (api) | serde, input/output validators |
| `name/version/host/port` | shorthands | |
| `withTools/withResources/withPrompts/withCompletions` | bootstrap registrations run in `build()` | |
| `withExtensions` | | dup id ⇒ IAE |
| `threadFactory` | handler executor | |
| `annotations` | `AnnotationContext` (`withProvider(p).register(obj)`) | composes across calls; `@ExperimentalApi` `AnnotationContext.java:29-66` |
| `pipelineCustomizer` | last Netty hook | `@ExperimentalApi` |

## 📊 Defaults

| Setting | Default | Proof |
|---|---|---|
| host | `127.0.0.1` | `NetworkConfig.java:73` |
| port | `-1` unset ⇒ `start()` ISE; `0` = ephemeral | `:74`, `DefaultTachyonServer.java:395-397` |
| endpoint | `/mcp` | `:75` |
| reader idle / writer idle | 60s / 5min | `:76-77` |
| heartbeat | 15s (≤0 disables) | `:78` |
| max body | 1 MB | `McpChannelInitializer.java:50` |
| ioEngine | `AUTO` | `NetworkConfig.java:92` |
| sessions | **disabled** | `SessionConfig.java:37` |
| session TTL / janitor | 30s / 5s | `SessionConfig.java:34-35` |
| feature modes | `AUTO`, listChanged false, page 50 | `FeatureConfig.java:18`, `ResourcesConfig.java:19` |
| completions | `AUTO` | `CapabilitiesConfig.java:31-37` |
| logging capability | **false** | same |
| tasks | disabled; keepAlive 5min | `TasksConfig.java:36-39` |
| shutdown grace / request timeout | 5s / 60s | `tachyon-api/.../config/RuntimeConfig.java:26-35` |
| slow request log | `MonitoringConfig.DEFAULT` | `tachyon-api/.../config/MonitoringConfig.java` |
| payload capture | all off, 4096 bytes | `PayloadCapturePolicy.java:23-26` |
| validators / serde | networknt 2020-12 / Jackson | `DefaultServerBuilder.java` fields |

## 🧷 Validation gotchas

- Builder annotation configurers compose into one `server.annotations(...)` call, using the constructed server's codecs (`DefaultServerBuilder.java:262`). Spring defers discovered bean registration until singleton initialization; see [[integrations]].

- `SessionConfig`: any session option while disabled ⇒ ISE "Session options require sessions to be enabled" `SessionConfig.java:39-47`, `:127-137`.
- `NetworkConfig.Builder`: `address()` XOR `host()/port()` ⇒ ISE `NetworkConfig.java:119-150`.
- `CapabilitiesConfig`: tasks enabled w/o connector ⇒ ISE `:443-447`.
- page sizes must be > 0; `maxContentLength` > 0; `pollInterval` > 0.
- `allowedHosts` entries: bare authority only, no URL syntax ⇒ IAE at pipeline construction → [[security-guards]].

Kotlin mirrors: `info { }`, `capabilities { }`, `network { }`, `session { }` scopes → [[tachyon-kotlin]].

Related: [[overview]], [[sessions]], [[observability]].
