/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.example.temporal;

import dev.tachyonmcp.api.server.features.tasks.TaskState;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Example workflow implementation. */
public final class BookingWorkflowImpl implements BookingWorkflow {

    private static final Logger logger = Workflow.getLogger(BookingWorkflowImpl.class);

    private final SlotAvailability slots = Workflow.newActivityStub(
            SlotAvailability.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .build());

    private Instant createdAt;
    private TaskState state = TaskState.SUBMITTED;
    private String message = "Submitted";
    private Map<String, Object> result = Map.of();
    private Map<?, ?> input;
    private long revision;
    private Instant observedAt = Instant.EPOCH;

    @Override
    public void book(Map<String, Object> arguments) {
        createdAt = now();
        observedAt = createdAt;
        logger.info("Booking workflow started: fields={}", arguments.keySet());
        transitionTo(TaskState.WORKING, "Booking appointment");
        var prompt = "Approval required";
        while (true) {
            input = null;
            transitionTo(TaskState.INPUT_REQUIRED, prompt);
            logger.info("Booking workflow waiting for approval");
            Workflow.await(() -> input != null);
            var approved = "accept".equals(input.get("action"))
                    && input.get("content") instanceof Map<?, ?> content
                    && Boolean.TRUE.equals(content.get("approved"));
            if (!approved) {
                result = Map.of("booking", arguments, "confirmed", false);
                transitionTo(TaskState.REJECTED, "Booking rejected");
                logger.info("Booking workflow rejected");
                return;
            }
            // The approval may arrive hours after the pause began: the task was healthy all along,
            // the slot may not be. Ask the calendar again right before booking.
            if (slots.isAvailable(arguments)) {
                result = Map.of("booking", arguments, "confirmed", true);
                transitionTo(TaskState.COMPLETED, "Appointment booked");
                logger.info("Booking workflow completed");
                return;
            }
            logger.info("Booking workflow found the slot taken, asking again");
            prompt = "Slot no longer available; approve to try again or decline";
        }
    }

    @Override
    public TemporalTaskStatus taskStatus() {
        return new TemporalTaskStatus(state, message, createdAt, observedAt, result, revision);
    }

    @Override
    public void provideInput(Map<String, Object> inputResponses) {
        // SEP-2663: a server SHOULD ignore a response to a request that is not outstanding.
        if (state != TaskState.INPUT_REQUIRED
                || !(inputResponses.get(BookingWorkflow.approvalKey(revision)) instanceof Map<?, ?> answer)) {
            logger.info("Booking workflow ignored input it is not waiting for: keys={}", inputResponses.keySet());
            return;
        }
        logger.info("Booking workflow received the answer to its approval request");
        this.input = answer;
        transitionTo(TaskState.WORKING, "Input accepted");
    }

    private void transitionTo(TaskState nextState, String nextMessage) {
        state = nextState;
        message = nextMessage;
        revision++;
        observedAt = now();
    }

    private static Instant now() {
        return Instant.ofEpochMilli(Workflow.currentTimeMillis());
    }
}
