---
title: Configuration
tags: [concept, config, builder]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/server/ServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/DefaultServerBuilder.java, tachyon-core/src/main/java/dev/tachyonmcp/core/server/config/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/config/]
updated: 2026-09-15
commit: 751331f4
---

# 🎛️ Configuration

Verdict: `ServerBuilder` exposes grouped `Consumer<X.Builder>` configurers + shorthand setters; `buildConfig()` yields immutable `ServerConfig` of records. Defaults tuned for **local, stateless, loopback** server.

## 🏗️ Builder groups

`ServerBuilder` `ServerBuilder`

| Method | Target | Notes |
|---|---|---|
| `info` | `ServerIdentity.Builder` (api, Immutables) | name `tachyon-mcp`, version `0.1`, title, description, websiteUrl, instructions, icons `ServerIdentity` |
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
| `annotations` | `AnnotationContext` (`withProvider(p).register(obj)`) | composes across calls; `@ExperimentalApi` `AnnotationContext` |
| `pipelineCustomizer` | last Netty hook | `@ExperimentalApi` |

## 📊 Defaults

| Setting | Default | Proof |
|---|---|---|
| host | `127.0.0.1` | `NetworkConfig#DEFAULT_HOST` |
| port | `-1` unset ⇒ `start()` ISE; `0` = ephemeral | `NetworkConfig#UNSET_PORT`, `DefaultTachyonServer#start` |
| endpoint | `/mcp` | `DefaultTachyonServer` |
| reader idle / writer idle | 60s / 5min | `DefaultTachyonServer` |
| heartbeat | 15s (≤0 disables) | `DefaultTachyonServer` |
| max body | 1 MB | `McpChannelInitializer#DEFAULT_MAX_CONTENT_LENGTH` |
| ioEngine | `AUTO` | `NetworkConfig#DEFAULT` |
| sessions | **disabled** | `SessionConfig#STATELESS` |
| session TTL / janitor | 30s / 5s | `SessionConfig#DEFAULT_SESSION_TTL` |
| feature modes | `AUTO`, listChanged false, page 50 | `FeatureConfig#DEFAULT`, `ResourcesConfig#DEFAULT` |
| completions | `AUTO` | `CapabilitiesConfig#DEFAULT` |
| logging capability | **false** | same |
| tasks | disabled; keepAlive 5min | `TasksConfig#DEFAULT_TASK_KEEP_ALIVE` |
| shutdown grace / request timeout | 5s / 60s | `RuntimeConfig#shutdownGracePeriod` |
| slow request log | `MonitoringConfig.DEFAULT` | `tachyon-api/.../config/MonitoringConfig.java` |
| payload capture | all off, 4096 bytes | `PayloadCapturePolicy#DEFAULT_MAX_BYTES` |
| validators / serde | networknt 2020-12 / Jackson | `DefaultServerBuilder.java` fields |

## 🧷 Validation gotchas

- Builder annotation configurers (see [[declarative-configuration]]) compose into one `server.annotations(...)` call, using the constructed server's codecs (`DefaultServerBuilder#applyAnnotationRegistrations`). Spring defers discovered bean registration until singleton initialization; see [[spring-boot]].

- `SessionConfig`: any session option while disabled ⇒ ISE "Session options require sessions to be enabled" `SessionConfig#STATELESS`, `Builder#build`.
- `NetworkConfig.Builder`: `address()` XOR `host()/port()` ⇒ ISE `Builder#Builder`.
- `CapabilitiesConfig`: tasks enabled w/o connector ⇒ ISE `Builder#validateTaskConnector`.
- page sizes must be > 0; `maxContentLength` > 0; `pollInterval` > 0.
- `allowedHosts` entries: bare authority only, no URL syntax ⇒ IAE at pipeline construction → [[security-guards]].

Kotlin mirrors: `info { }`, `capabilities { }`, `network { }`, `session { }` scopes → [[tachyon-kotlin]].

Related: [[overview]], [[sessions]], [[observability]].
