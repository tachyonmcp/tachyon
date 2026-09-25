---
title: Findings
tags: [meta, findings]
sources: [tachyon-core/src/main/java/dev/tachyonmcp/core/, tachyon-api/src/main/java/dev/tachyonmcp/api/server/features/HandlerFutures.java]
updated: 2026-09-25
commit: bb583cea
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
- ⚠️ Task access is enforced only for cached tasks with an owner (`DefaultTaskRegistry#visibleTo`). An uncached id (evicted by the janitor, or after a restart) goes to the connector, so any session can reach it. `subscriptions/listen` `taskIds` has no check, harmless while 2026-07-28 has no sessions (every task ownerless). Connectors must authorize via `InteractionContext`. `TaskMethodHandlers`
- ⚠️ Task cache is unbounded for non-terminal tasks: the janitor evicts only terminal + expired entries (`TaskEntry#isResultExpired`). Abandoned `working` tasks stay forever. `DefaultTaskRegistry#runJanitorSweep`
- ⚠️ A refused `TaskRegistry#create` answers "Task-producing tool returned a task owned by another session": if a tool derives task ids from client input (e.g. an idempotency key), a client can probe which ids other sessions use. `ToolMethodHandlers#mapResult`
- 🪶 Task notifications are sent under the per-task `TaskEntry` lock (for ordering). SSE sends never park (owner status offers, `DefaultTachyonServer#notifyTaskStatus`), but a custom `SessionEventStore#append` that blocks (remote store) serializes publishers of that task behind its I/O. `TaskEntry#notifyIfNewer`

## 🪶 Polish

- 🪶 `SessionConfig` is a sum modelled as a product: `boolean enabled` × 5 `@Nullable` options, guarded in the compact ctor **and** `Builder#build`. Stateless is a *server* property — no session config ⇒ no sessions. shape: drop `enabled`, make every component non-null, `@Nullable SessionConfig ServerConfig#session()` with `stateless()` derived from `== null`, delete `SessionConfig#sessionStoreOrDefault`/`#sessionEventStoreOrDefault` and `Builder#enabled`/`#enabled(boolean)`. Then the impossible state is unrepresentable in the value, not just the builder.

## ❓ Open questions

- **Q:** Multi-node: event log store (`SessionEventStore`) replay across nodes? - **A:** No
- 2026-07-28 + stateful server: dispatcher bypasses sessions via `supportsSessions=false`; GET stream not matched for 2026 → only `subscriptions/listen`. Confirm intended.
- ⚠️ `MISSING_REQUIRED_CLIENT_CAPABILITY` maps to -32003 on 2025-11-25 (`McpResponseMapper`), -32021 only on 2026-07-28. SEP-2133 / Python SDK use -32021. Decide whether the 2025 wire should switch.
