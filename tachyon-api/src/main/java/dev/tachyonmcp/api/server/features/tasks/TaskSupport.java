/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.tasks;

import dev.tachyonmcp.api.annotations.ExperimentalApi;

/**
 * Declares whether a tool may run as a background task instead of returning its result
 * synchronously from {@code tools/call}. Set via {@code ToolDescriptor.taskSupport()}; a
 * {@code null} descriptor value is treated as {@link #FORBIDDEN}.
 *
 * <p>MCP 2025-11-25 lets the client opt in per call via the legacy {@code tools/call.task} field:
 * {@link #FORBIDDEN} rejects a task-augmented call, {@link #REQUIRED} rejects a plain (non
 * task-augmented) call, and {@link #OPTIONAL} accepts either. MCP 2026-07-28 (SEP-2663) has no
 * client-requested opt-in — that legacy field is ignored — so only {@link #REQUIRED} has an
 * effect there: it always creates a task (the tool can never run synchronously), gated on the
 * client having declared the {@code io.modelcontextprotocol/tasks} extension for that request.
 * {@link #OPTIONAL} and {@link #FORBIDDEN} both run synchronously under 2026-07-28.
 */
@ExperimentalApi
public enum TaskSupport {

    /** The tool never runs as a task; a task-augmented call is rejected. This is the default. */
    FORBIDDEN,

    /**
     * Under MCP 2025-11-25, the caller may run the tool synchronously or as a task. MCP
     * 2026-07-28 always runs this tool synchronously.
     */
    OPTIONAL,

    /**
     * The tool always runs as a task. Under MCP 2025-11-25 a non task-augmented call is rejected;
     * under MCP 2026-07-28 every call is dispatched as a task automatically. The tool may first
     * answer with {@code ToolResult.inputRequired(...)} to gather input (SEP-2322); the round that
     * completes the call must return the task (SEP-2663).
     */
    REQUIRED
}
