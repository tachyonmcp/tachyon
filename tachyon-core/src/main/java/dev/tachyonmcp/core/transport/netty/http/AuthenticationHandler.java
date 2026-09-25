/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils.getInteractionContext;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.security.AuthenticationException;
import dev.tachyonmcp.api.server.security.AuthenticationProvider;
import dev.tachyonmcp.api.server.security.SecurityContext;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Authenticates every aggregated HTTP request with the configured {@link AuthenticationProvider}
 * and records the result as the channel's current {@link SecurityContext}, which each dispatch
 * context copies. The provider runs on the handler executor, never on the event loop; the request
 * continues down the pipeline on the event loop once it returns.
 *
 * <p>A rejected request is answered per RFC 6750 §3.1 and never reaches dispatch:
 * {@link AuthenticationException.Reason#MISSING_CREDENTIALS} ⇒ {@code 401} with a bare
 * {@code Bearer} challenge, {@code INVALID_TOKEN} ⇒ {@code 401 error="invalid_token"},
 * {@code INVALID_REQUEST} ⇒ {@code 400 error="invalid_request"}. Any other failure means credentials
 * could not be checked and answers {@code 500}, so clients keep a token that may be valid.
 *
 * <p>Relies on {@link HttpPipeliningGate} admitting one request per channel at a time: the current
 * security context cannot be overwritten while a request is in flight. One instance per channel.
 */
@InternalApi
public final class AuthenticationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AuthenticationHandler.class);

    private final AuthenticationProvider<? super HttpRequest> provider;
    private final Executor executor;

    public AuthenticationHandler(AuthenticationProvider<? super HttpRequest> provider, Executor executor) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        final var outcome = new CompletableFuture<SecurityContext>();
        try {
            executor.execute(() -> authenticate(request, outcome));
        } catch (RejectedExecutionException e) {
            outcome.completeExceptionally(e);
        }
        outcome.whenComplete((securityContext, failure) -> {
            try {
                ctx.executor().execute(() -> resume(ctx, request, securityContext, failure));
            } catch (RejectedExecutionException e) {
                ReferenceCountUtil.release(request);
            }
        });
    }

    private void authenticate(HttpRequest request, CompletableFuture<SecurityContext> outcome) {
        try {
            outcome.complete(Objects.requireNonNull(provider.authenticate(request), "provider returned null"));
        } catch (Throwable t) {
            outcome.completeExceptionally(t);
        }
    }

    private static void resume(
            ChannelHandlerContext ctx,
            FullHttpRequest request,
            @Nullable SecurityContext securityContext,
            @Nullable Throwable failure) {
        if (!ctx.channel().isActive()) {
            ReferenceCountUtil.release(request);
            return;
        }
        if (securityContext != null) {
            final var interaction = getInteractionContext(ctx);
            if (interaction != null) {
                interaction.setSecurityContext(securityContext);
            }
            ctx.fireChannelRead(request);
            return;
        }
        reject(ctx, request, failure);
    }

    private static void reject(ChannelHandlerContext ctx, FullHttpRequest request, @Nullable Throwable failure) {
        final HttpResponseStatus status;
        final @Nullable String challenge;
        if (failure instanceof AuthenticationException e) {
            logger.debug("Rejected {} {}: {} ({})", request.method(), path(request), e.reason(), e.getMessage());
            status = e.reason() == AuthenticationException.Reason.INVALID_REQUEST
                    ? HttpResponseStatus.BAD_REQUEST
                    : HttpResponseStatus.UNAUTHORIZED;
            challenge = switch (e.reason()) {
                case MISSING_CREDENTIALS -> "Bearer";
                case INVALID_TOKEN -> "Bearer error=\"invalid_token\"";
                case INVALID_REQUEST -> "Bearer error=\"invalid_request\"";
            };
        } else {
            logger.warn("Authentication provider failed for {} {}", request.method(), path(request), failure);
            status = HttpResponseStatus.INTERNAL_SERVER_ERROR;
            challenge = null;
        }
        final var cors = TachyonCorsHandler.decide(ctx, request);
        ReferenceCountUtil.release(request);
        final var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        if (challenge != null) {
            response.headers().set(HttpHeaderNames.WWW_AUTHENTICATE, challenge);
        }
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        cors.applyTo(response);
        HttpUtil.setKeepAlive(response, false);
        ctx.writeAndFlush(response);
    }

    /** The query string may carry a token ({@code access_token}); never log it. */
    private static String path(HttpRequest request) {
        return new QueryStringDecoder(request.uri()).path();
    }
}
