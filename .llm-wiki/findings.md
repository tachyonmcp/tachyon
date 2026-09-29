---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/]
updated: 2026-09-29
commit: 4b4b6f7e
---

# 🔎 Findings

Spotted while reading code. Runtime verification noted per finding. Fixed in code ⇒ 🗑️ remove row.

- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- ⚠️ POST-SSE budget per stream only; no global cap or connection limit. `PostSseStream#reserve`
- ⚠️ Perf: notifications round-trip bytes → `String` → bytes. `DefaultTachyonServer#sendSerializedNotification` builds `notificationJson` as a `String`; `SseSerializer#measure` then counts its UTF-8 size (~0.7 ns/char, charAt loop, not vectorized) and `SseSerializer#encode` transcodes it back. Carry `byte[]` end to end (like the final response, `PostSseStream#writeEvent(long, byte[], Runnable)`): length is free, encode is a copy. Touches `SseEvent`, `JsonRpcCodec`, event log (stores `String`).
- ⚠️ Several producers on one POST-SSE stream: a parked producer can be overtaken ⇒ wire ids out of order ⇒ `Last-Event-ID` resume may skip one. Pre-existing race, wider with parking. `PostSseStream#awaitCapacity`
- ⚠️ Platform `ServerBuilder#threadFactory`: parked producer holds an OS thread until `writerIdleTimeout`. `DefaultServerBuilder#build`
- ⚠️ Tachyon has no task access control of its own: `tasks/*` go to the connector, and `subscriptions/listen` task ids pass the connector's `get` once when the stream opens (`TaskEngine#readableTaskIds`), not per event: a permission revoked later keeps flowing until the stream closes. Safe only with unguessable task ids (MCP tasks spec MUST without context binding); Tachyon cannot check ids it doesn't mint. [TaskMethodHandlers](../extensions/tachyon-extensions-tasks/src/main/java/dev/tachyonmcp/extensions/tasks/TaskMethodHandlers.java), `SubscriptionsListenHandler#handleAsync`.
- ⚠️ `notifications/tasks` on a `subscriptions/listen` stream carries no `_meta` `io.modelcontextprotocol/subscriptionId`; 2026-07-28 subscriptions: "All notifications delivered on the stream carry" it. Other listen notifications do (`McpResponseMapper#subscriptionListChangedParams`). `ProtocolResponseMapper#taskStatusNotificationParams`
- ⚠️ Listen: one sequential connector `get` per task id, bounded only by `maxContentLength`. `TaskEngine#readableTaskIds`
- 🪶 A publish racing janitor eviction can land on the evicted entry: `putIfAbsent` returned it, then `TaskEntry#evictIfExpired` removed it before `TaskEntry#publish`. The status is still sent, but the newer revision is not cached. Benign: the cache is a projection and the next `tasks/get` re-caches the connector's snapshot. Pre-existing (the old `entries.get` path had the same window). `TaskEngine#publish`
- ⚠️ `working` task with `ttl = null` never evicted. `TaskEntry#isExpired`
- ⚠️ SEP-2640 `"resources": "dynamic"` unsupported. The schema carries `SkillResource[] | "dynamic"`, but `skills-2026-07-28_config.json` maps `Skill.resources` to `List<SkillResource>`, and `SkillsRegistry.Skill` has no dynamic flag, so a generated-content skill can't be served. Fix needs both: a wire model for the union (generator support or a `JsonNode` mapping) and a registry API for dynamic skills. `SkillsExtension#skillEntry`
- 🪶 Blocking custom `SessionEventStore#append` serializes a task's publishers (per-task lock). `TaskEntry#notifyIfNewer`
- 🪶 A2A placement undecided: the tasks `engine` package is protocol-neutral so it can become `tachyon-tasks` (MCP binding + future A2A binding on one engine). If A2A ships inside `tachyon-core`, the engine must move into core instead (core cannot depend on an extension). Decide before the split; engine ownership when both bindings are present is open. `EngineBoundaryTest`
- ⚠️ `close()` on an interrupted thread: `shutdownExtensions` returns on the first interrupted `join`, so later extensions never get `shutdown()` called (their worker is never started). `DefaultTachyonServer#shutdownExtensions`
- 🪶 Dead code: `DefaultTachyonServer#drainEvents` has no caller; `Session#cursor` only feeds it. Delete or wire up. `DefaultTachyonServer#drainEvents`
- 🪶 Task codecs still live in core (`McpTaskMapper` v2025/v2026, task methods on `ProtocolRequestMapper`/`ProtocolResponseMapper`, `capabilities.tasks` in `ServerInfoMapper`); the generated `…extensions.tasks.protocol.v2026_07_28` models are unused. `McpTaskMapper`

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node `SessionEventStore` replay? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
- ⚠️ Generated skills `Skill.resources` is `List<SkillResource>` (config override): schema's `SkillResource[] | "dynamic"` has no ts2java mapping. Fine while registries only publish scanned files; dynamic skills need a union type. `extensions/tachyon-extensions-skills/protocol/skills-2026-07-28_config.json`
- ⚠️ Legacy waits poll `get` per `pollInterval` each: N waiters ⇒ N/interval connector calls. `TaskEngine#pollUntilTerminal`
- ⚠️ `responseUndeliverable` node-local: `DELETE` on another node leaves waiter polling until local janitor evicts. `Session.InFlightRequest#sessionClosed`
