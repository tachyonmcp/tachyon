/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The topics a {@code subscriptions/listen} stream asked for, each with its decoded value, in
 * registration order. Built by {@link SubscriptionRegistry#decode}; a topic the listener did not ask
 * for is absent.
 */
@InternalApi
public final class SubscriptionFilter {

    private final Map<SubscriptionTopic<?>, Object> values;

    SubscriptionFilter(Map<SubscriptionTopic<?>, Object> values) {
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** Returns the value requested for {@code topic}, or {@code null} when the listener did not ask for it. */
    @SuppressWarnings("unchecked")
    public <F> @Nullable F get(SubscriptionTopic<F> topic) {
        return (F) values.get(topic);
    }

    /**
     * Returns this filter narrowed by each topic's authorizer, dropping the topics it refuses. May
     * block: call on the handler executor, never on an event loop.
     */
    public SubscriptionFilter authorize(DispatchContext context) {
        var honored = new LinkedHashMap<SubscriptionTopic<?>, Object>();
        values.forEach((topic, requested) -> {
            var value = authorize(topic, context, requested);
            if (value != null) {
                honored.put(topic, value);
            }
        });
        return new SubscriptionFilter(honored);
    }

    /** Returns the filter echoed in the ack: each topic's {@code filterKey} to its acknowledged value. */
    public Map<String, Object> acknowledged() {
        var ack = new LinkedHashMap<String, Object>();
        values.forEach((topic, value) -> ack.put(topic.filterKey(), acknowledged(topic, value)));
        return ack;
    }

    @SuppressWarnings("unchecked")
    private static <F> @Nullable F authorize(SubscriptionTopic<F> topic, DispatchContext context, Object requested) {
        return topic.authorizer().authorize(context, (F) requested);
    }

    @SuppressWarnings("unchecked")
    private static <F> Object acknowledged(SubscriptionTopic<F> topic, Object value) {
        return topic.acknowledged().apply((F) value);
    }
}
