/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

/**
 * A publish was refused because it claims a task for a caller that does not own it. The owner of
 * a task never changes.
 */
public final class TaskOwnershipException extends IllegalStateException {

    /** Creates the exception. */
    public TaskOwnershipException() {
        super("Task ownership conflict");
    }
}
