/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.extensions.tasks.engine;

/**
 * Where a task's push traffic goes, as the protocol binding that created the task defines it: a
 * delivery address only, never an access decision. The engine stores it and hands it back to
 * {@link TaskEvents}; only the binding that made it reads it.
 */
public interface TaskRoute {

    /** No route: the task was not created by a routed call, so bindings push only to subscribers. */
    TaskRoute NONE = new TaskRoute() {
        @Override
        public String toString() {
            return "TaskRoute.NONE";
        }
    };
}
