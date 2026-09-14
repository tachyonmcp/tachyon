---
title: Index
tags: [meta]
updated: 2026-09-13
commit: 582f9c52
---

# 🗺️ Tachyon MCP — LLM Wiki index

Read this first. Rules: [[CONVENTIONS]].

## 🧭 Start

| Page | One-liner |
|---|---|
| [[overview]] | Modules, dep graph, versions, the one type users hold (`TachyonServer`) |
| [[findings]] | 🐛/🪶/⚠️ smells + open questions spotted in code |

## ⚙️ Concepts (cross-cutting)

| Page | One-liner |
|---|---|
| [[request-lifecycle]] | HTTP POST → pipeline → `McpDispatcher` → handler (VT) → JSON or SSE response |
| [[netty-pipeline]] | Exact handler order per channel, what each rejects, init→operation phase swap |
| [[protocol-versions]] | `Protocol` SPI, 2025-11-25 (sessions) vs 2026-07-28 (stateless, `_meta`, SEP-2243 headers), ts2java codegen |
| [[sessions]] | Stateless default; `Session` runtime + `SessionSnapshot` store, CAS persistence, TTL janitor |
| [[sse-streams]] | POST-SSE lazy upgrade, GET stream, event ids `<n>#<streamKey>`, per-stream replay, heartbeat |
| [[feature-registries]] | Tools/resources/prompts/completions registries, `Mode` AUTO/ON/OFF, pagination, capability resolution |
| [[tasks]] | Tasks: `TaskConnector` owns execution, registry caches `TaskSnapshot` by revision, legacy vs modern methods |
| [[extensions]] | `ServerExtension` lifecycle, per-request vs initialize negotiation, method ownership, `_meta` gate |
| [[json-layer]] | `JsonDocument`/`JsonSchema` SPI via ServiceLoader, payload serde, networknt validator, JSON-RPC codec |
| [[errors]] | `ServerError.Kind` → JSON-RPC code + HTTP status per protocol; exception mapping |
| [[concurrency]] | Platform event loops vs virtual-thread handlers, no `synchronized`, `OperationTracker` shutdown drain |
| [[configuration]] | `ServerBuilder` groups, config records, defaults table |
| [[security-guards]] | DNS-rebinding, header guard, Accept, endpoint, stateless guard, CORS, body limit |
| [[observability]] | `ObservationListener` lifecycle, outcomes, payload capture, OTel bridge |
| [[api-stability]] | `@ExperimentalApi`/`@InternalApi`/`@LegacyApi`, revapi, internal packages, Immutables |

## 📦 Modules

| Page | One-liner |
|---|---|
| [[tachyon-api]] | Public contracts: feature SAMs, descriptors, results, `InteractionContext`, JSON SPI |
| [[tachyon-core]] | Server impl: builder, engine, dispatcher, Netty transport, protocol mappers |
| [[tachyon-kotlin]] | Kotlin DSL, coroutine runtime extension, kotlinx JSON factories, kt-schema |
| [[tachyon-extensions]] | Skills extension (`skill://`), echo + You.com tools |
| [[tachyon-testkit]] | Raw-HTTP MCP test clients + AssertJ asserts |
| [[integrations]] | Annotation providers (mcp-java, LangChain4j, Spring AI), OpenTelemetry, Temporal tasks |
| [[testing]] | e2e layout by protocol version, conformance suite, unit test map, how to run |
