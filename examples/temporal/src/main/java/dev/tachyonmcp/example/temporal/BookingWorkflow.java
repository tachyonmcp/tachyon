/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.UpdateMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.Map;

/** Durable appointment-booking workflow used by the example. */
@WorkflowInterface
public interface BookingWorkflow {

    /** Executes the booking workflow. */
    @WorkflowMethod
    void book(Map<String, Object> arguments);

    /** Returns the complete externally observable task status. */
    @QueryMethod
    TemporalTaskStatus taskStatus();

    /** Accepts MCP task input while the booking waits for it, and ignores it otherwise. */
    @UpdateMethod
    void provideInput(Map<String, Object> input);
}
