/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.subscriptions;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.json.JsonObject;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * One key of a {@code subscriptions/listen} notification filter: how to read it from the request,
 * which part of it the listener may have, and what the ack echoes back. Core registers its own
 * topics next to the feature handlers; an extension registers its topics with {@link
 * SubscriptionRegistry#register} while it installs, and pushes to them with {@link
 * SubscriptionRegistry#publish}.
 *
 * @param filterKey the key in the wire filter and the ack, e.g. {@code toolsListChanged}
 * @param decoder reads the requested value; {@code null} when the listener did not ask for it
 * @param authorizer narrows the requested value to what the listener may follow
 * @param acknowledged the value echoed in {@code notifications/subscriptions/acknowledged}
 * @param <F> the decoded filter value
 */
@InternalApi
public record SubscriptionTopic<F>(
        String filterKey, Decoder<F> decoder, Authorizer<F> authorizer, Function<F, Object> acknowledged) {

    /** Reads a topic's requested value from the raw filter. */
    @FunctionalInterface
    public interface Decoder<F> {

        /**
         * Returns the requested value, or {@code null} when the filter does not ask for this topic.
         * Cheap and non-blocking. May throw {@link dev.tachyonmcp.core.protocol.RequestMappingException}
         * to refuse the whole request.
         */
        @Nullable
        F decode(DispatchContext context, JsonObject filter);
    }

    /** Narrows a requested value to what the listener may follow. */
    @FunctionalInterface
    public interface Authorizer<F> {

        /**
         * Returns the honored value, or {@code null} to drop the topic from the subscription and its
         * ack. Runs on the handler executor and may block.
         */
        @Nullable
        F authorize(DispatchContext context, F requested);
    }

    public SubscriptionTopic {
        Objects.requireNonNull(filterKey, "filterKey");
        if (filterKey.isBlank()) throw new IllegalArgumentException("filterKey must not be blank");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(authorizer, "authorizer");
        Objects.requireNonNull(acknowledged, "acknowledged");
    }

    /**
     * A boolean opt-in such as {@code toolsListChanged}: requested only when {@code true}, echoed as
     * {@code true}.
     */
    public static SubscriptionTopic<Boolean> flag(String filterKey) {
        return new SubscriptionTopic<>(
                filterKey,
                (context, filter) -> filter.boolOr(filterKey, false) ? Boolean.TRUE : null,
                (context, requested) -> requested,
                requested -> Boolean.TRUE);
    }

    /**
     * A string allowlist such as {@code resourceSubscriptions}: requested only when non-empty (null
     * entries skipped), echoed as a list.
     */
    public static SubscriptionTopic<Set<String>> strings(String filterKey) {
        return new SubscriptionTopic<>(
                filterKey,
                (context, filter) -> readStrings(filter, filterKey),
                (context, requested) -> requested,
                List::copyOf);
    }

    /** Returns this topic with {@code authorizer} deciding the honored value. */
    public SubscriptionTopic<F> withAuthorizer(Authorizer<F> authorizer) {
        return new SubscriptionTopic<>(filterKey, decoder, authorizer, acknowledged);
    }

    /** Returns this topic with {@code decoder} reading the requested value. */
    public SubscriptionTopic<F> withDecoder(Decoder<F> decoder) {
        return new SubscriptionTopic<>(filterKey, decoder, authorizer, acknowledged);
    }

    private static @Nullable Set<String> readStrings(JsonObject filter, String filterKey) {
        var array = filter.arrayOpt(filterKey).orElse(null);
        if (array == null || array.isEmpty()) {
            return null;
        }
        var values = new LinkedHashSet<String>(array.size());
        for (int i = 0; i < array.size(); i++) {
            array.stringOpt(i).ifPresent(values::add);
        }
        return values.isEmpty() ? null : Collections.unmodifiableSet(values);
    }
}
