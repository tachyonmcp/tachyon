---
title: "SSE Reconnect & Re-delivery"
weight: 55
sidebar_order: 55
sidebar_hide: true
build:
    list: never
    render: never
toc: true
description: |-
  How Tachyon survives SSE disconnects: event-log replay on reconnect, Last-Event-ID handling, and POST-SSE re-delivery semantics.
---

How Tachyon guarantees a tool's final response reaches a client that reconnects after the tool
closes its SSE stream mid-call — and the race that used to make it flaky.

## Background: streams, event ids, and resume

Tachyon speaks MCP **Streamable HTTP**. A `POST /mcp` for a `tools/call` can *upgrade* from a
buffered JSON reply to a live SSE stream (`text/event-stream`) the moment the handler emits a
server→client message (a progress notification, a `comment`, or by explicitly starting the stream).
See [Configuration → keep-alive for long-running tools](../running/configuration.md#keep-alive-for-long-running-tools).

Every SSE event carries an id. For a POST-upgraded stream the wire id is `<n>#<key>`:

- `<n>` — a monotonic per-session sequence number (`ServerEngine.nextEventId()`).
- `<key>` — the **stream key**, drawn once per POST (`PostSseStream.streamKey`). It tags every event
  of that stream in the event log and suffixes the SSE ids, so a `Last-Event-ID` resolves back to
  *this* stream and no other.

Every emitted event is also appended to the session **event store** (`SessionEventStore`). If the
connection drops, the client reconnects with `Last-Event-ID: <n>#<key>` and the server replays the
missed events of **that stream only** — never another stream's (MCP resumability: *"the server MUST
NOT replay messages that would have been sent on a different stream"*, guarded by
`SseReplayPerStreamTest`).

Replay is a **one-shot** read of the log at reconnect time (`SseManager.missedEvents`): it scans the
log once, sends events with `id > lastSeen` and matching stream key, and stops.

The resumed stream's priming event echoes the client's `Last-Event-ID`, `#<key>` included, instead
of drawing a fresh id. A fresh id would be higher than every missed event and would drop the key,
so a client that disconnects again after the priming event, before the replay reaches it, would
resume past its backlog, or onto the GET stream (`SseResumeCursorTest`). Until the replay is
queued, live events sent to the resumed connection are held, then released minus those the replay
already covered, so a live event never moves the client's `Last-Event-ID` past an event still
waiting to be replayed (`NettySseConnection.replay`).

A slow client pauses the stream rather than losing events: while the channel is unwritable,
events are held and written again on `channelWritabilityChanged`. The stream closes only when it
stays unwritable longer than `sseStallTimeout` or the held events exceed `maxPendingSseBytes`; the
client then resumes from the last event it received (`NettySseConnectionTest`).

## The scenario: a tool that closes its stream mid-call

Some tools deliberately close their SSE stream *before* producing a result, to force the client to
reconnect and resume (the MCP conformance suite's `test_reconnection` tool does exactly this):

<!-- snips: ../../examples/doc-examples/src/main/java/dev/tachyonmcp/docs/advanced/ssereconnectredelivery/SelfClosingTool.java#sse_self_closing_tool -->
```java
var stream = OutboundSseStreamMessageRouter.currentOutboundSseStream();
stream.start(); // upgrades POST → SSE, sends the priming event  (id 4#3)
stream.close(); // closes the channel; the client observes a disconnect
// ...tool keeps working, then returns its result
```

The result is finalized only *after* the handler returns, in
`McpOperationHandler.finalizePostSseResponse`:

1. draw an id (say `5`),
2. **append** the response to the event log as `5#3`,
3. write it to the POST stream — but the stream is already closed, so the write is **dropped**,
4. close.

So the client can only obtain the response by reconnecting and replaying it from the log.

## The bug: a reconnect-vs-append race

After the mid-call close, two things happen concurrently on different threads:

- **A — append** (server-local): handler returns → dispatch completes → `finalizePostSseResponse`
  appends `5#3` to the log.
- **B — reconnect + replay** (network round-trip): the client sees the close → sends
  `GET` with `Last-Event-ID: 4#3` → `SseManager.missedEvents` reads the log.

Nothing orders A before B.

```mermaid
sequenceDiagram
    participant Client
    participant Server
    participant Handler as Tool handler
    participant Log as Event log

    Client->>Server: POST tools/call
    Server->>Handler: invoke
    Handler->>Client: start() → priming event 4#3
    Handler->>Client: close() → stream ends
    Note over Handler,Log: A — handler returns, response 5#3 appended
    Note over Client,Server: B — client reconnects, replays from 4#3
    alt A before B (usual)
        Log-->>Client: replay finds 5#3 ✅
    else B before A (flake)
        Log-->>Client: replay finds nothing; 5#3 appended too late ❌
    end
```

- **A wins (usual):** `5#3` is in the log before the replay → client gets the response. ✅
- **B wins (flake):** the replay runs first and finds nothing; `5#3` is appended a moment later, but
  the one-shot replay already ran and the dropped POST write goes nowhere. **Nothing retries**, so
  the client waits out its window and never receives the response. ❌

On localhost the reconnect round-trip is fast enough to occasionally beat the local append, so the
same code passes or fails depending on thread scheduling — a classic flake. It surfaced as an
intermittent `server-sse-disconnect-resume` **WARNING** (a `SHOULD`-level check) in the default
conformance suite.

### Why production tools don't hit this

A normal tool never closes its own stream. The server's own `finalizePostSseResponse` always
**appends the response before it closes** the stream, so a client only ever reconnects *after* the
response is already in the log. The race exists only when user code closes the stream before the
result exists.

## The fix: live re-delivery on the resumed stream

When the final response write is dropped because the stream is already closed, deliver it **live**
to the client's current connection — but only if that connection explicitly resumed *this* stream.

Two pieces:

1. **Remember what the reconnect is resuming.** `SseManager.openStream` parses the `#<key>` out of
   `Last-Event-ID` and records it on the session (`Session.resumingStreamKey`). A general GET
   listening stream (numeric `Last-Event-ID`, no key) records `null`.

2. **Fall back on a dropped write.** `PostSseStream.writeEvent(id, body, onDropped)` invokes
   `onDropped` (on the event loop) when the write is discarded because the stream is closed or its
   channel is dead. `finalizePostSseResponse` supplies a callback
   (`McpOperationHandler.redeliverOnReconnect`) that, if the session's current connection is resuming
   this stream key, re-sends the response on it:

   ```java
   if (streamKey.equals(session.resumingStreamKey())) {
       session.send(new SseEvent(wireId, "message", resultJson));
   }
   ```

Now both orderings succeed:

- **B wins:** the replay missed `5#3`, but by the time the append+drop callback runs the client has
  reconnected and resumed key `3` → the response is delivered live.
- **A wins:** `5#3` was appended first; the replay finds it (and if the drop callback also fires,
  see the note below).

### Invariants and trade-offs

- **No cross-stream delivery.** The fallback fires only when `session.resumingStreamKey()` equals the
  POST stream's key, so a response is never pushed onto a *different* stream (e.g. a general GET
  listening stream). This preserves the MCP resumability rule that `SseReplayPerStreamTest` asserts.
- **Happy path untouched.** The fallback runs *only* when the POST write is dropped. For every normal
  tool call the write succeeds, `onDropped` never fires, and behaviour is exactly as before.
- **Rare double-delivery is tolerated.** In a narrow window both the replay and the fallback can
  deliver the same event (same SSE id). This is harmless: a client dedupes the JSON-RPC response by
  request id. Marked with a `ponytail:` comment in `redeliverOnReconnect` noting per-connection id
  de-dup as the upgrade path if it ever matters.

## Testing

[SsePostReconnectRedeliveryTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/SsePostReconnectRedeliveryTest.java) makes the race **deterministic**: the tool closes its stream and
then `Thread.sleep(300)` before returning, so the response is appended only *after* the client has
reconnected and run its one-shot replay. Without the fallback the reconnecting client never receives
the response; with it, the response is delivered live on the resumed stream.

[SseReplayPerStreamTest](../../e2e/src/test/java/dev/tachyonmcp/e2e/mcp/v2025_11_25/SseReplayPerStreamTest.java) guards the companion invariant: a resumed stream must not receive another
stream's messages.

## Touch points

| Concern | Location |
|---|---|
| Stream key allocation + priming | `PostSseStream` (`streamKey`, `doStart`) |
| Dropped-write callback | `PostSseStream.writeEvent(long, ByteBuf, Runnable)` / `doWriteEvent` |
| Response finalize + fallback | `McpOperationHandler.finalizePostSseResponse` / `redeliverOnReconnect` |
| Record resumed stream key | `SseManager.openStream`; `Session.resumingStreamKey` |
| Cursor echo in priming event | `SseManager.openStream`, `SseManager.ResumeCursor` |
| One-shot replay | `SseManager.missedEvents` |
| Replay gate, slow-client hold, stall timer | `NettySseConnection` (`replay`, `onWritabilityChanged`) |
| Double-reconnect regression test | `e2e/.../SseResumeCursorTest` |
| Regression test | `e2e/.../SsePostReconnectRedeliveryTest` |
| Cross-stream invariant test | `e2e/.../SseReplayPerStreamTest` |
