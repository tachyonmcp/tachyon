# Tasks requirements

🎯 A task's status says where the work is, not whether what it waited on is still true. The server carries input to the system that owns the work; that system decides whether the input can still be used. Protocol: **SEP-2663** (see [`../seps.md`](../seps.md)).

## Submitting input: `tasks/update`

- **TASK-01** MUST — `tasks/update` is served only on MCP 2026-07-28 with the tasks extension declared. On MCP 2025-11-25 it answers `-32601` (method not found). See SEP-2663.
- **TASK-02** MUST — `tasks/update` hands the request's `taskId` and all of its `inputResponses` to the connector's `update` once and answers the result `{"resultType":"complete"}` with no other fields. The server checks only that the caller may reach the task. It does not check that the task is waiting for input, that a response key is outstanding, or that the business state the task paused on still holds: the connector decides those. See SEP-2663 (MUST acknowledge with an empty result; `resultType` MUST be `complete`; authorization on each task request).
- **TASK-03** MUST — When the connector's `update` throws `TaskNotFoundException`, `tasks/update` answers `-32602` with the message `Failed to update task: Task not found`. See SEP-2663 (unknown `taskId`: SHOULD answer `-32602`).
- **TASK-04** MUST — When the connector's `update` throws `TaskInputRejectedException`, `tasks/update` answers `-32602` with the message `Failed to update task: ` followed by the exception's message, and no result. The task is left as it was: a later `tasks/get` returns the status it had before. The exception is for an unusable answer to an outstanding request; an answer to a request that is not outstanding is ignored, not refused (TASK-06, TASK-08). See SEP-2663 (SHOULD return errors for clearly invalid requests; SHOULD ignore responses to a request that is not outstanding); the exception itself is Tachyon API.
- **TASK-07** MUST — On MCP 2026-07-28, a client that did not declare the tasks extension gets `-32021` (missing required client capability) for `tasks/get`, `tasks/update` and `tasks/cancel`, with the extension named in `data.requiredCapabilities`. The connector is not called. See SEP-2663 (non-declaring clients MUST get this error).

## Temporal engine

- **TASK-08** MUST — `TemporalTaskExecutionEngine#submitInput` acknowledges input for a Workflow that has already finished without an error, and the task stays as it ended. A Workflow Temporal does not know, or one the caller may not reach, still answers `TaskNotFoundException`. See SEP-2663 (SHOULD ignore responses to a request that is not outstanding; unknown `taskId` SHOULD be an error).

## Temporal booking example

- **TASK-05** MUST — The booking Workflow checks slot availability after the approval arrives and before it completes, not before the pause. When the slot is no longer available, the task returns to `input_required` with a status message that says so, a pending input request and no result. That request has a key the task has not used before. Each later approval is checked again. See SEP-2663 (`input_required` MUST carry the outstanding `inputRequests`; a request key MUST NOT be reused within a task).
- **TASK-06** MUST — While it runs, the booking Workflow accepts only the answer stored under the key of its outstanding approval request, shaped `{"action":"accept","content":{"approved":true}}`; any other action or content declines the booking. A response under any other key (never issued, or already answered), and any input that arrives while the Workflow is not waiting, is acknowledged without an error and changes neither the task's revision nor the answer already accepted. A finished booking is covered by TASK-08. See SEP-2663 (SHOULD ignore responses to a request that is not outstanding).
