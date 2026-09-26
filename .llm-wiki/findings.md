---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/]
updated: 2026-09-25
commit: cbfbcd7f
---

# 🔎 Findings

Spotted while reading code. Runtime verification noted per finding. Fixed in code ⇒ 🗑️ remove row.

- ⚠️ `UnsupportedProtocolVersionHandler` encodes the rejection with `ProtocolVersionHandler#LATEST_PROTOCOL` (not `Protocols#baseline`) + HTTP 400, even for legacy-looking clients. Intentional per SEP-2575? `UnsupportedProtocolVersionHandler#channelRead`
- ⚠️ GET-SSE has no byte budget: `Session#send` checks `isWritable` on the caller thread, then `NettySseConnection#send` queues a loop task. Busy/blocked loop ⇒ writability never flips ⇒ loop task queue grows with the producer. Same class as the fixed POST-SSE bug, lower risk. `Session#send`, `NettySseConnection#send`
- ⚠️ POST-SSE budget is per stream (`PostSseStream#reserve`); no global outbound cap or connection limit. N slow streams ⇒ N × `maxPendingSseBytes` (default 64 KiB) + one parked producer each, for `writerIdleTimeout` (5 min). A trickle reader completes writes, so writer idle never fires ⇒ held indefinitely.
- ⚠️ Perf: notifications round-trip bytes → `String` → bytes. `DefaultTachyonServer#sendSerializedNotification` builds `notificationJson` as a `String`; `SseSerializer#measure` then counts its UTF-8 size (~0.7 ns/char, charAt loop, not vectorized) and `SseSerializer#encode` transcodes it back. Carry `byte[]` end to end (like the final response, `PostSseStream#writeEvent(long, byte[], Runnable)`): length is free, encode is a copy. Touches `SseEvent`, `JsonRpcCodec`, event log (stores `String`).
- ⚠️ Several producers on one POST-SSE stream: a parked producer can be overtaken ⇒ wire ids out of order ⇒ `Last-Event-ID` resume may skip one. Pre-existing race, wider with parking. `PostSseStream#awaitCapacity`
- ⚠️ A platform `ServerBuilder#threadFactory` still gets thread-per-task (`DefaultServerBuilder#build`), so no pool starvation, but each producer parked on a slow POST-SSE client holds one OS thread until `writerIdleTimeout`. Default virtual threads unaffected.
- ⚠️ Absolute-form request-target (`POST http://host/mcp HTTP/1.1`) ⇒ 404: `EndpointValidatorHandler#channelRead` compares the raw URI. RFC 9112 §3.2.2: servers MUST accept absolute-form. Fails closed. Fix must also check the authority against `Host` and the DNS-rebinding guard, or an authority-less check becomes a bypass.
- ⚠️ Tachyon has no task access control of its own: `tasks/*` go to the connector, and `subscriptions/listen` task ids pass the connector's `get` once when the stream opens (`TaskRegistry#readableTaskIds`), not per event: a permission revoked later keeps flowing until the stream closes. Safe only with unguessable task ids (MCP tasks spec MUST without context binding); Tachyon cannot check ids it doesn't mint. [TaskMethodHandlers](../tachyon-core/src/main/java/dev/tachyonmcp/core/server/features/tasks/TaskMethodHandlers.java), `SubscriptionsListenHandler#handleAsync`.
- ⚠️ `notifications/tasks` on a `subscriptions/listen` stream carries no `_meta` `io.modelcontextprotocol/subscriptionId`; 2026-07-28 subscriptions: "All notifications delivered on the stream carry" it. Other listen notifications do (`McpResponseMapper#subscriptionListChangedParams`). `ProtocolResponseMapper#taskStatusNotificationParams`
- ⚠️ One `subscriptions/listen` request costs one connector `get` per named task id, sequentially on its handler thread; the only bound is `maxContentLength` (1 MB), so one request can fan out to thousands of backend lookups. Cap the id count if connectors are expensive. `DefaultTaskRegistry#readableTaskIds`
- 🪶 A publish racing janitor eviction can land on the evicted entry: `putIfAbsent` returned it, then `TaskEntry#evictIfExpired` removed it before `TaskEntry#publish`. The status is still sent, but the newer revision is not cached. Benign: the cache is a projection and the next `tasks/get` re-caches the connector's snapshot. Pre-existing (the old `entries.get` path had the same window). `DefaultTaskRegistry#publish`
- ⚠️ Task cache is unbounded for non-terminal tasks without `ttl`: the janitor evicts entries past `ttl` or terminal past keepAlive (`TaskEntry#isExpired`). An abandoned `working` task with `ttl = null` stays forever. `DefaultTaskRegistry#runJanitorSweep`
- 🪶 Task notifications are sent under the per-task `TaskEntry` lock (for ordering). SSE sends never park (session status offers, `DefaultTachyonServer#notifyTaskStatus`), but a custom `SessionEventStore#append` that blocks (remote store) serializes publishers of that task behind its I/O. `TaskEntry#notifyIfNewer`

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node: event log store (`SessionEventStore`) replay across nodes? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
- ⚠️ Generated skills `Skill.resources` is `List<SkillResource>` (config override): schema's `SkillResource[] | "dynamic"` has no ts2java mapping. Fine while registries only publish scanned files; dynamic skills need a union type. `extensions/tachyon-extensions-skills/protocol/skills-2026-07-28_config.json`
