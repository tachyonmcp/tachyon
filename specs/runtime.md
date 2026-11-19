# Server runtime requirements

## Server lifecycle

- **RT-01** MUST — After `stop()`, the same server instance can `start()` again and bind a new transport. `stop()` preserves registrations, extensions, the handler executor and sessions. An existing session that is still valid can resume on the restarted transport.
- **RT-02** MUST — `stop()` is a no-op when the server is stopped, has never started or has been closed. `close()` releases the server's resources and is terminal. A subsequent `start()` fails with `IllegalStateException`.

## Graceful shutdown

- **RT-03** MUST — `stop()` and `close()` stop accepting new connections before draining admitted requests. Responses that finish within the configured `shutdownGracePeriod` can flush. When the drain budget expires, shutdown proceeds to transport teardown and drops unsent responses.
- **RT-04** MUST — `shutdownGracePeriod` is configurable and defaults to five seconds. It bounds the request drain and each transport teardown wait individually. On `close()`, the server uses the remaining drain budget for its handler executor and requests interruption of unfinished handlers when that budget expires.
- **RT-05** MUST — When `stop()` or `close()` ends an open `subscriptions/listen` stream, the server initiates graceful completion before draining admitted requests. For a writable connection, it flushes the successful `resultType: "complete"` response before closing the stream. An open listen stream alone must not keep the request drain waiting for the full grace period.
