/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.domain;

import org.immutables.value.Value;
import org.jspecify.annotations.Nullable;

/** Requests user input by invoking another RPC method. */
@Value.Immutable
@Value.Style(allParameters = true, visibilityString = "PACKAGE", typeImmutable = "Default*")
public non-sealed interface RpcMethodRequest extends InputRequest {

    /** The RPC method to invoke for input. */
    String method();

    /** Optional parameters for the RPC method. */
    @Nullable
    Object params();

    @Value.Check
    default void check() {
        if (method().isBlank()) throw new IllegalArgumentException("method must not be blank");
    }

    static Builder builder() {
        return DefaultRpcMethodRequest.builder();
    }

    static RpcMethodRequest of(String method, @Nullable Object params) {
        return DefaultRpcMethodRequest.of(method, params);
    }

    interface Builder {
        /** Fills this builder with the attribute values from {@code instance}. */
        Builder from(RpcMethodRequest instance);

        Builder method(String method);

        Builder params(@Nullable Object params);

        RpcMethodRequest build();
    }
}
