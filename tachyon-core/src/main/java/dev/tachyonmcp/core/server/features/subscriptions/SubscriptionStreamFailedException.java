/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import dev.tachyonmcp.api.annotations.InternalApi;

/**
 * Marks a {@code subscriptions/listen} SSE stream's terminal future as ending in a genuine
 * transport failure rather than an ordinary client disconnect (which resolves the same future via
 * plain cancellation) — lets the dispatcher tell the two apart and report {@code
 * OperationOutcome.StreamFailed} only for the former.
 */
@InternalApi
public final class SubscriptionStreamFailedException extends RuntimeException {

    public SubscriptionStreamFailedException(Throwable cause) {
        super(cause.getMessage(), cause);
    }
}
