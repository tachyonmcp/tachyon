/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.core.server.session.DispatchContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * Handles a single JSON-RPC method. Internal/advanced SPI — receives the rich
 * {@link DispatchContext} (server, response mapper, outbound stream).
 *
 * <p>{@link #handle} runs on a <b>virtual thread</b> per request. Handler code may block for I/O
 * — that is the intended VT contract — but must <b>never</b> use {@code synchronized}, call native
 * methods, or otherwise pin the carrier thread.
 * Use {@link java.util.concurrent.locks.ReentrantLock} over {@code synchronized} for mutual
 * exclusion. For CPU-bound work or third-party code that may synchronize, offload to
 * {@code context.engine().executor()} and join the result:
 * <pre>{@code
 * var future = CompletableFuture.supplyAsync(
 *         () -> heavyWork(), context.engine().executor());
 * return HandlerFutures.joinInterruptible(future);
 * }</pre>
 */
@InternalApi
public interface RpcMethodHandler<I, O> {

    /** Non-null decoded parameter for methods that take no parameters. */
    enum NoParams {
        /** The only no-parameters value. */
        INSTANCE
    }

    /**
     * The JSON-RPC method name this handler dispatches to.
     *
     * @return the method name
     */
    String method();

    /**
     * Decodes the raw JSON-RPC {@code params} (a {@code Map}/{@code List}/scalar from the wire, or
     * {@code null}) into this handler's typed request. Invoked once by the dispatcher before {@link
     * #handle}/{@link #handleAsync}, so the decode step is a single, uniform seam the dispatcher
     * controls regardless of what each handler needs from its {@link
     * dev.tachyonmcp.core.protocol.ProtocolRequestMapper}.
     *
     * @param context   the dispatch context (for protocol/session-dependent gating before decoding)
     * @param rawParams the raw wire params, or {@code null}
     * @return the decoded, protocol-neutral request
     * @throws dev.tachyonmcp.core.protocol.RequestMappingException if {@code rawParams} cannot be
     *     mapped, or a precondition gating this method fails
     */
    I decode(DispatchContext context, @Nullable Object rawParams);

    /**
     * Handles the method and returns the result to serialize as JSON-RPC response.
     *
     * @param context the dispatch context with server and outbound stream access
     * @param params  the decoded method parameters
     * @return the result to serialize
     * @throws Exception on handler failure
     */
    O handle(DispatchContext context, I params) throws Exception;

    /**
     * Handles the method asynchronously. Default delegates to {@link #handle}.
     * Override for async handlers that return a future directly.
     *
     * @param context the dispatch context
     * @param params  the decoded method parameters
     * @return a completion stage yielding the result
     * @throws Exception on handler failure
     */
    default CompletionStage<O> handleAsync(DispatchContext context, I params) throws Exception {
        return CompletableFuture.completedStage(handle(context, params));
    }
}
