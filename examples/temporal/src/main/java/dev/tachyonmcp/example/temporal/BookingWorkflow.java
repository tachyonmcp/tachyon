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

    /**
     * Accepts the answer to the outstanding approval request and ignores everything else.
     *
     * @param inputResponses the {@code tasks/update} responses, keyed by request key
     */
    @UpdateMethod
    void provideInput(Map<String, Object> inputResponses);

    /**
     * Names the approval request the booking asked at {@code revision}. SEP-2663 forbids reusing a
     * request key within a task, so every time the booking asks again it asks under a new key.
     *
     * @param revision the status revision at which the booking started waiting
     * @return the request key
     */
    static String approvalKey(long revision) {
        return "approval-" + revision;
    }
}
