# Tasks requirements

🎯 A task's status says where the work is, not whether what it waited on is still true. The server carries input to the system that owns the work; that system decides whether the input can still be used. Protocol: **SEP-2663** (see [`../seps.md`](../seps.md)).

## Submitting input: `tasks/update`

- **TASK-01** MUST — `tasks/update` is served only on MCP 2026-07-28 with the tasks extension declared. On MCP 2025-11-25 it answers `-32601` (method not found). See SEP-2663.
- **TASK-02** MUST — `tasks/update` hands the request's `taskId` and `inputResponses` to the connector's `update` once and answers an empty result. The server checks only that the caller may reach the task. It does not check that the task is waiting for input or that the business state it paused on still holds. See SEP-2663 (empty acknowledgement; authorization on each task request).
- **TASK-03** MUST — When the connector's `update` throws `TaskNotFoundException`, `tasks/update` answers `-32602` with the message `Failed to update task: Task not found`. See SEP-2663 (unknown `taskId`: SHOULD answer `-32602`).
- **TASK-04** MUST — When the connector's `update` throws `TaskInputRejectedException`, `tasks/update` answers `-32602` with the message `Failed to update task: ` followed by the exception's message, and no result. The task is left as it was: a later `tasks/get` returns the status it had before. See SEP-2663 (SHOULD return errors for clearly invalid requests); the exception itself is Tachyon API.
- **TASK-07** MUST — On MCP 2026-07-28, a client that did not declare the tasks extension gets `-32021` (missing required client capability) for `tasks/get`, `tasks/update` and `tasks/cancel`, with the extension named in `data.requiredCapabilities`. The connector is not called. See SEP-2663 (non-declaring clients MUST get this error).

## Temporal booking example

- **TASK-05** MUST — The booking Workflow checks slot availability after the approval arrives and before it completes, not before the pause. When the slot is no longer available, the task returns to `input_required` with a status message that says so, a pending input request and no result. Each later approval is checked again.
- **TASK-06** MUST — The booking Workflow ignores input that arrives while it is not waiting for input: the update is acknowledged without an error and changes neither the task's revision nor the input already accepted. See SEP-2663 (SHOULD ignore responses to a request that is not outstanding).
