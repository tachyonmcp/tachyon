# Stateless extension negotiation

🎯 Extension capabilities belong to a client interaction, never a pooled TCP connection.

- **2025-11-25:** [SEP-2133](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/seps/2133-extensions.md#negotiation) declares extensions in `initialize`. Preserve declarations across requests only through client-scoped session state.
- **2026-07-28:** [Extension negotiation](https://modelcontextprotocol.io/extensions/overview#negotiation) declares support per request in `_meta["io.modelcontextprotocol/clientCapabilities"].extensions`. Do not infer this contract for 2025-11-25.
- **Stateless HTTP:** allocate a fresh context per request. An `initialize` declaration applies only to that request. Never clear/reuse shared mutable capability state across overlapping requests.
- **Unknown support:** fall back to core behavior, or reject mandatory extensions, per SEP-2133’s graceful-degradation rule.

✅ Acceptance: same TCP connection, fresh connection, and overlapping requests yield identical capability decisions for identical requests; one client’s declaration never enables another’s extensions. Stateful 2025 negotiation still survives connection changes.
