/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.observability.Observation;
import dev.tachyonmcp.core.server.observability.ObservationListener;
import dev.tachyonmcp.core.server.observability.OperationInfo;
import dev.tachyonmcp.core.server.observability.OperationKind;
import dev.tachyonmcp.core.server.observability.OperationOutcome;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.AuthenticationProvider;
import dev.tachyonmcp.core.server.security.AuthenticationRequest;
import dev.tachyonmcp.core.server.security.SecurityRejection;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticates every HTTP request on its head, before its body is requested or read, with the
 * configured {@link AuthenticationProvider}. The provider sees the method, URI and headers, never a
 * body, and runs on the handler executor, never on the event loop.
 *
 * <p>While it runs, the request's later {@link HttpContent} is held here. If the request still has body
 * to come when a read ends, or more than {@link #HOLD_LIMIT} bytes of it are held before the read ends
 * (a read loop can deliver several buffers before it completes), the socket is not read any further:
 * reads are paused through the channel's {@link HttpPipeliningGate}, which stays the one owner of
 * {@code autoRead}. What is held is therefore bounded by that limit plus one buffer, never by
 * {@code maxContentLength}. A request that arrives whole in one read, which is every request without a
 * body and every small one, pauses nothing: there is nothing more to read, and flipping {@code autoRead}
 * twice would cost two interest-set changes on a real transport. When the provider accepts, the head
 * and the held content are replayed in order, reads resume if they were paused, and the rest of the
 * body flows through live. Because the aggregator sits downstream, it answers {@code 100 Continue} only
 * to an authenticated request, and a request without a token is never buffered.
 *
 * <p>The provider sees a read-only {@link AuthenticationRequest} over the head, not the Netty object:
 * it can read the method, the URI and the headers, and change nothing.
 *
 * <p>A request rejected with an {@link AuthenticationException} is answered with the status and the
 * {@code WWW-Authenticate} challenge {@link SecurityRejection} maps it to, the same mapping the
 * dispatcher uses for a refusal raised later, and the connection closes, since the body was never
 * read. This handler holds no scheme of its own. Every refusal here, timeouts and provider failures
 * included, is reported to the server's observers as one {@link OperationKind#AUTHENTICATION}
 * operation: no dispatch exists yet to report it. A provider that cannot check credentials right now
 * says so with {@link AuthenticationException#unavailable}, a {@code 503} with {@code Retry-After}
 * and no challenge, so clients keep a token that may be valid; any other failure is a bug and answers
 * {@code 500}. A provider still silent after the configured timeout is answered the same {@code 503};
 * it is not interrupted, and its late result is ignored. At most {@link
 * SecurityConfig#maxPendingAuthentications()} requests wait for the provider at once, across every
 * connection of the server; one more is answered the same {@code 503} before the provider is called,
 * so a slow provider cannot pile up threads and held heads without limit. Timeouts and refusals at
 * capacity are counted, and one in {@link #WARN_SAMPLE} is logged at {@code WARN}, the rest at
 * {@code DEBUG}: a slow provider under load must not flood the log; the observers see every one. A principal
 * without a name is a provider failure: sessions are bound to the name, and a nameless principal could
 * not be told apart from an anonymous caller.
 *
 * <p>The result is recorded with {@link ChannelHandlerUtils#recordCaller} and taken, once, by the
 * handler that processes the request, which passes it down explicitly: no per-connection object holds
 * identity. Held content is released on rejection, timeout, {@code channelInactive} and handler removal.
 *
 * <p>Relies on {@link HttpPipeliningGate} admitting one request per channel at a time, so there is
 * never more than one attempt in flight. One instance per channel, since it keeps the request being
 * authenticated; it is event-loop confined, and only the provider call leaves the loop.
 */
@InternalApi
public final class AuthenticationHandler extends ChannelInboundHandlerAdapter {

    /** Body bytes held for one request before reads pause even mid-read; see the class comment. */
    static final int HOLD_LIMIT = 64 * 1024;

    /** One timeout or capacity refusal in this many is logged at {@code WARN}. */
    static final int WARN_SAMPLE = 100;

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationHandler.class);
    private static final AtomicLong timeouts = new AtomicLong();
    private static final AtomicLong atCapacity = new AtomicLong();

    private final AuthenticationProvider provider;
    private final SecurityConfig.Authentication authentication;
    private final Executor executor;
    private final Duration timeout;
    private final HttpPipeliningGate gate;
    private final List<ObservationListener> listeners;
    private final Semaphore permits;

    private @Nullable Attempt attempt;

    /** The request waiting for its authentication, and what arrived behind its head. */
    private static final class Attempt {
        final HttpRequest head;
        final ArrayDeque<HttpContent> held = new ArrayDeque<>(4);
        int heldBytes;
        /** Its last content has arrived, so there is nothing more to read for it. */
        boolean complete;
        /** Reads are paused through the gate on its behalf, so it must release them. */
        boolean holding;
        /** It holds one of the server's permits until it is settled. */
        boolean admitted;

        @Nullable
        ScheduledFuture<?> deadline;

        Attempt(HttpRequest head) {
            this.head = head;
            this.complete = head instanceof LastHttpContent;
        }
    }

    /**
     * @param security how the server authenticates, and how long it waits for the provider
     * @param executor runs the provider off the event loop
     * @param gate this channel's pipelining gate, which owns {@code autoRead}
     * @param listeners told of every request this handler rejects, as an {@link
     *     OperationKind#AUTHENTICATION} operation; none for a server without observers
     * @param permits the server's one pool of {@link SecurityConfig#maxPendingAuthentications}
     *     permits, shared by every channel
     * @throws IllegalArgumentException if {@code security} authenticates nobody
     */
    public AuthenticationHandler(
            SecurityConfig security,
            Executor executor,
            HttpPipeliningGate gate,
            List<ObservationListener> listeners,
            Semaphore permits) {
        this.provider = Objects.requireNonNull(security.authenticationProvider(), "the server authenticates nobody");
        this.authentication = security.authentication();
        this.timeout = security.authenticationTimeout();
        this.executor = Objects.requireNonNull(executor, "executor");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        this.permits = Objects.requireNonNull(permits, "permits");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof HttpRequest head) {
            if (attempt != null) {
                ReferenceCountUtil.release(head);
                abandon();
                ctx.close();
                return;
            }
            begin(ctx, head);
        } else if (msg instanceof HttpContent content && attempt != null) {
            final var pending = attempt;
            pending.held.add(content);
            pending.heldBytes += content.content().readableBytes();
            pending.complete |= content instanceof LastHttpContent;
            if (pending.heldBytes > HOLD_LIMIT) {
                hold(ctx, pending);
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        final var pending = attempt;
        if (pending == null) {
            ctx.fireChannelReadComplete();
        } else {
            hold(ctx, pending);
        }
    }

    /**
     * Pauses reads for {@code pending} unless it needs no more of them. A request whose last content is
     * already held asks for nothing further, which is every request without a body and every small one:
     * pausing then would only flip {@code autoRead} twice.
     */
    private void hold(ChannelHandlerContext ctx, Attempt pending) {
        if (!pending.complete && !pending.holding) {
            pending.holding = true;
            gate.holdReads(ctx.channel());
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        abandon();
        ctx.fireChannelInactive();
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        abandon();
    }

    private void begin(ChannelHandlerContext ctx, HttpRequest head) {
        final var current = new Attempt(head);
        attempt = current;
        if (!permits.tryAcquire()) {
            attempt = null;
            sampled(atCapacity, "Authentication refused at capacity for {} {}", head);
            observe(new OperationOutcome.Rejected(null, 503, 0));
            respond(ctx, current, HttpResponseStatus.SERVICE_UNAVAILABLE, null, retryAfterSeconds());
            return;
        }
        current.admitted = true;
        final var outcome = new CompletableFuture<SecurityContext>();
        try {
            current.deadline =
                    ctx.executor().schedule(() -> expire(ctx, current), timeout.toNanos(), TimeUnit.NANOSECONDS);
            final var view = new AuthenticationRequest(
                    head.method().name(), head.uri(), name -> head.headers().getAll(name));
            executor.execute(() -> authenticate(view, outcome));
        } catch (RejectedExecutionException e) {
            outcome.completeExceptionally(e);
        }
        outcome.whenComplete((securityContext, failure) -> {
            try {
                ctx.executor().execute(() -> resume(ctx, current, securityContext, failure));
            } catch (RejectedExecutionException e) {
                logger.debug("Event loop is gone, the pending request is released with the channel");
            }
        });
    }

    private void authenticate(AuthenticationRequest request, CompletableFuture<SecurityContext> outcome) {
        try {
            final var securityContext =
                    Objects.requireNonNull(provider.authenticate(request), "provider returned null");
            final var principal = securityContext.principal();
            if (principal != null
                    && (principal.getName() == null || principal.getName().isBlank())) {
                throw new IllegalStateException("provider returned a principal without a name");
            }
            outcome.complete(securityContext);
        } catch (Throwable t) {
            outcome.completeExceptionally(t);
        }
    }

    private void resume(
            ChannelHandlerContext ctx,
            Attempt completed,
            @Nullable SecurityContext securityContext,
            @Nullable Throwable failure) {
        if (attempt != completed) {
            return;
        }
        attempt = null;
        if (!ctx.channel().isActive()) {
            discard(completed);
            return;
        }
        cancel(completed);
        if (securityContext == null) {
            reject(ctx, completed, failure);
            return;
        }
        ChannelHandlerUtils.recordCaller(ctx.channel(), securityContext);
        ctx.fireChannelRead(completed.head);
        HttpContent content;
        while ((content = completed.held.poll()) != null) {
            if (!ChannelHandlerUtils.dropIfRejected(ctx, content)) {
                ctx.fireChannelRead(content);
            }
        }
        if (completed.holding) {
            gate.releaseReads(ctx.channel());
        }
        ctx.fireChannelReadComplete();
    }

    private void expire(ChannelHandlerContext ctx, Attempt expired) {
        if (attempt != expired) {
            return;
        }
        attempt = null;
        sampled(timeouts, "Authentication of {} {} timed out", expired.head);
        observe(new OperationOutcome.Rejected(null, 503, 0));
        respond(ctx, expired, HttpResponseStatus.SERVICE_UNAVAILABLE, null, retryAfterSeconds());
    }

    private void reject(ChannelHandlerContext ctx, Attempt rejected, @Nullable Throwable failure) {
        final var head = rejected.head;
        final var rejection = failure == null ? null : SecurityRejection.of(failure, authentication);
        if (rejection != null && failure != null) {
            logger.debug("Rejected {} {}: {}", head.method(), path(head), rejection.status());
            // The message is the provider's own: at TRACE only, where raw request data belongs.
            logger.trace("Rejected {} {}: {}", head.method(), path(head), failure.getMessage());
            observe(rejection.outcome());
            respond(
                    ctx,
                    rejected,
                    HttpResponseStatus.valueOf(rejection.status()),
                    rejection.wwwAuthenticate(),
                    rejection.retryAfter());
        } else {
            // A verifier or an HTTP client can put the token in an exception message or a cause; at WARN
            // only the class is named. The whole exception is for DEBUG, which stays off in production.
            logger.warn(
                    "Authentication provider failed for {} {}: {}",
                    head.method(),
                    path(head),
                    failure == null ? "null" : failure.getClass().getName());
            logger.debug("Authentication provider failed for {} {}", head.method(), path(head), failure);
            observe(new OperationOutcome.Rejected(null, 500, 0));
            respond(ctx, rejected, HttpResponseStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    /**
     * Tells the observers a request was refused on its head, where no dispatch exists to report it:
     * one {@link OperationKind#AUTHENTICATION} operation, started and completed here with the status.
     * It carries no header and no URI, so no token can reach a listener.
     */
    private void observe(OperationOutcome outcome) {
        if (listeners.isEmpty()) {
            return;
        }
        final var observation = Observation.start(
                listeners,
                OperationInfo.builder(OperationKind.AUTHENTICATION, OperationInfo.AUTHENTICATION_METHOD, null)
                        .build());
        observation.closeStart();
        observation.complete(outcome);
    }

    /** Answers the request with its own CORS decision and closes: its body was never read. */
    private void respond(
            ChannelHandlerContext ctx,
            Attempt refused,
            HttpResponseStatus status,
            @Nullable String challenge,
            @Nullable String retryAfter) {
        final var cors = TachyonCorsHandler.decide(ctx, refused.head);
        final var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        if (challenge != null) {
            response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, challenge);
        }
        if (retryAfter != null) {
            response.headers().set(HttpHeaderNames.RETRY_AFTER, retryAfter);
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        cors.applyTo(response);
        HttpUtil.setKeepAlive(response, false);
        cancel(refused);
        release(refused);
        ChannelHandlerUtils.markRejected(ctx, refused.head);
        ctx.writeAndFlush(response);
    }

    /** One line in {@link #WARN_SAMPLE} at WARN, with the running count; the rest at DEBUG. */
    private static void sampled(AtomicLong counter, String event, HttpRequest head) {
        final var count = counter.incrementAndGet();
        if (count % WARN_SAMPLE == 1) {
            logger.warn(event + " ({} so far)", head.method(), path(head), count);
        } else {
            logger.debug(event, head.method(), path(head));
        }
    }

    private String retryAfterSeconds() {
        return Long.toString(Math.max(1, Math.ceilDiv(timeout.toMillis(), 1000)));
    }

    private void abandon() {
        final var pending = attempt;
        if (pending != null) {
            attempt = null;
            discard(pending);
        }
    }

    private void discard(Attempt dropped) {
        cancel(dropped);
        release(dropped);
        ReferenceCountUtil.release(dropped.head);
    }

    /** Settles the attempt: drops its deadline and gives its permit back, once. */
    private void cancel(Attempt finished) {
        final var deadline = finished.deadline;
        if (deadline != null) {
            deadline.cancel(false);
        }
        if (finished.admitted) {
            finished.admitted = false;
            permits.release();
        }
    }

    private static void release(Attempt finished) {
        HttpContent content;
        while ((content = finished.held.poll()) != null) {
            ReferenceCountUtil.release(content);
        }
    }

    /** The query string may carry a token ({@code access_token}); never log it. */
    private static String path(HttpRequest request) {
        return new QueryStringDecoder(request.uri()).path();
    }
}
