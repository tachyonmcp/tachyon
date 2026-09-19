/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.resources;

import dev.tachyonmcp.api.server.ServerFeature;
import dev.tachyonmcp.api.server.domain.UriTemplateValue;
import java.util.Collections;
import java.util.Map;
import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/**
 * Request passed to a resource function when a client invokes {@code resources/read}.
 *
 * <p>Carries the concrete resource {@link #uri()} that was read, optionally the
 * {@link #uriTemplate()} that matched, and any URI-template {@link #params()}
 * extracted during matching.
 */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public interface ResourceRequest extends ServerFeature.Request {

    /**
     * The resource URI being requested.
     */
    String uri();

    /**
     * URI-template variable values extracted during template matching, keyed by
     * variable name. Empty when the request targets a static (non-template) resource.
     */
    @Value.Default
    default Map<String, UriTemplateValue> params() {
        return Collections.emptyMap();
    }

    /**
     * The URI template that matched the request, or {@code null} when the request
     * targets a static (non-template) resource.
     */
    @Nullable
    String uriTemplate();

    /** Optional protocol extension metadata. */
    @Nullable
    @Override
    Map<String, Object> meta();

    /**
     * Returns the client responses supplied when retrying an input-required resource read.
     *
     * @return input responses, or {@code null}
     */
    @Nullable
    Map<String, Object> inputResponses();

    /**
     * Returns the opaque request state supplied when retrying an input-required resource read.
     *
     * @return request state, or {@code null}
     */
    @Nullable
    String requestState();

    /** Creates a new builder for {@link ResourceRequest}. */
    static Builder builder() {
        return DefaultResourceRequest.builder();
    }

    /** Builder for {@link ResourceRequest}. */
    interface Builder {

        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(ResourceRequest instance);

        /** Sets the resource URI being requested. */
        Builder uri(String uri);

        /** Sets the URI-template variable values extracted during template matching. */
        Builder params(Map<String, ? extends UriTemplateValue> params);

        /** Sets the URI template that matched the request. */
        Builder uriTemplate(@Nullable String uriTemplate);

        /** Sets the optional protocol extension metadata. */
        Builder meta(@Nullable Map<String, ?> entries);

        /**
         * Sets the client responses supplied when retrying an input-required resource read.
         *
         * @param inputResponses input responses, or {@code null}
         */
        Builder inputResponses(@Nullable Map<String, ?> inputResponses);

        /**
         * Sets the opaque request state supplied when retrying an input-required resource read.
         *
         * @param requestState request state, or {@code null}
         */
        Builder requestState(@Nullable String requestState);

        /** Builds the {@link ResourceRequest}. */
        ResourceRequest build();
    }
}
