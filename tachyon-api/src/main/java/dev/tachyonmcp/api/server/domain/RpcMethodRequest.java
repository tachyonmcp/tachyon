/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Requests user input by invoking another RPC method. */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface RpcMethodRequest extends InputRequest {

    /**
     * The RPC method to invoke for input.
     *
     * @return the method
     */
    String method();

    /**
     * Optional parameters for the RPC method.
     *
     * @return the params
     */
    @Nullable
    Object params();

    /**
     * Validates the value invariants.
     */
    @Value.Check
    default void check() {
        if (method().isBlank()) throw new IllegalArgumentException("method must not be blank");
    }

    /**
     * Creates a new builder.
     *
     * @return a new builder
     */
    static Builder builder() {
        return DefaultRpcMethodRequest.builder();
    }

    /**
     * Creates a {@link RpcMethodRequest} from the supplied values.
     *
     * @param method the method
     * @param params the params
     * @return the rpc method request
     */
    static RpcMethodRequest of(String method, @Nullable Object params) {
        return DefaultRpcMethodRequest.of(method, params);
    }

    /**
     * Builder for the enclosing type.
     */
    interface Builder {
        /**
         * Fills this builder with the attribute values from {@code instance}.
         *
         * @param instance the instance to copy
         * @return this builder
         */
        Builder from(RpcMethodRequest instance);

        /**
         * Sets the method.
         *
         * @param method the method
         * @return this builder
         */
        Builder method(String method);

        /**
         * Sets the params.
         *
         * @param params the params
         * @return this builder
         */
        Builder params(@Nullable Object params);

        /**
         * Builds the configured value.
         *
         * @return the configured value
         */
        RpcMethodRequest build();
    }
}
