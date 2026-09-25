/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.testkit;

import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.ObservationScope;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/** Controllable {@link ObservationListener} fixture for MCP server tests. */
public final class TestObservationListener implements ObservationListener {

    /**
     * One recorded {@link #start} call.
     *
     * @param info the started operation
     */
    public record Started(OperationInfo info) {}

    /**
     * One recorded {@link #complete} call.
     *
     * @param info the completed operation
     * @param outcome the operation outcome
     */
    public record Completed(OperationInfo info, OperationOutcome outcome) {}

    private final List<Started> started = new CopyOnWriteArrayList<>();
    private final List<Completed> completed = new CopyOnWriteArrayList<>();
    private volatile Supplier<? extends RuntimeException> startFailure;
    private volatile Supplier<? extends RuntimeException> completeFailure;

    /** Creates a new test observation listener. */
    public TestObservationListener() {}

    /**
     * Makes every {@link #start} call throw the given exception, until {@link #reset()}.
     *
     * @param exception supplies the exception to throw
     * @return this listener
     */
    public TestObservationListener failOnStart(Supplier<? extends RuntimeException> exception) {
        this.startFailure = exception;
        return this;
    }

    /**
     * Makes every {@link #complete} call throw the given exception, until {@link #reset()}.
     *
     * @param exception supplies the exception to throw
     * @return this listener
     */
    public TestObservationListener failOnComplete(Supplier<? extends RuntimeException> exception) {
        this.completeFailure = exception;
        return this;
    }

    @Override
    public ObservationScope start(OperationInfo info) {
        started.add(new Started(info));
        var failure = startFailure;
        if (failure != null) {
            throw failure.get();
        }
        return ObservationScope.NOOP;
    }

    @Override
    public void complete(OperationInfo info, OperationOutcome outcome) {
        completed.add(new Completed(info, outcome));
        var failure = completeFailure;
        if (failure != null) {
            throw failure.get();
        }
    }

    /**
     * Returns every recorded {@link #start} call, in order.
     *
     * @return an immutable snapshot of started operations
     */
    public List<Started> started() {
        return List.copyOf(started);
    }

    /**
     * Returns every recorded {@link #complete} call, in order.
     *
     * @return an immutable snapshot of completed operations
     */
    public List<Completed> completed() {
        return List.copyOf(completed);
    }

    /**
     * Clears recorded calls and any configured failure.
     *
     * @return this listener
     */
    public TestObservationListener reset() {
        started.clear();
        completed.clear();
        startFailure = null;
        completeFailure = null;
        return this;
    }
}
