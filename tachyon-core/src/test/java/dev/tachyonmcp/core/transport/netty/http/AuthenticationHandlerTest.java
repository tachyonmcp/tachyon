/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.transport.netty.http;

import static dev.tachyonmcp.core.test.TestUtils.newEngine;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.tachyonmcp.api.server.security.SecurityContext;
import dev.tachyonmcp.core.server.config.SecurityConfig;
import dev.tachyonmcp.core.server.internal.ServerEngine;
import dev.tachyonmcp.core.server.security.AccessPolicy;
import dev.tachyonmcp.core.server.security.AuthenticationChallenge;
import dev.tachyonmcp.core.server.security.AuthenticationException;
import dev.tachyonmcp.core.server.security.AuthenticationRequest;
import dev.tachyonmcp.core.transport.netty.ChannelHandlerUtils;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Authentication on the request head: what is held while the provider runs, what is replayed, and
 * that nothing a request carried is ever left unreleased. The provider runs on a queue the test
 * drains, so every ordering of "provider answers", "timeout fires" and "channel closes" is exact.
 */
class AuthenticationHandlerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** The server's one {@code Retry-After}, unlike {@link #TIMEOUT} so neither can pass for the other. */
    private static final Duration RETRY_AFTER = Duration.ofSeconds(7);

    private final Queue<Runnable> handlerExecutor = new ArrayDeque<>();
    private final List<AuthenticationRequest> seenByProvider = new ArrayList<>();
    private @Nullable SecurityContext accepted;
    private @Nullable AuthenticationException refused;
    private @Nullable RuntimeException providerBug;
    private @Nullable Consumer<AuthenticationRequest> tampering;

    private final HttpPipeliningGate gate = new HttpPipeliningGate(16);
    private final Semaphore permits = new Semaphore(1);
    private final Sink sink = new Sink();
    private final ReadCounter reads = new ReadCounter();
    private final EmbeddedChannel channel = new EmbeddedChannel(
            reads,
            gate,
            new AuthenticationHandler(
                    new SecurityConfig(
                            new SecurityConfig.Authentication.Custom(request -> {
                                seenByProvider.add(request);
                                if (tampering != null) {
                                    tampering.accept(request);
                                }
                                if (refused != null) {
                                    throw refused;
                                }
                                if (providerBug != null) {
                                    throw providerBug;
                                }
                                return accepted;
                            }),
                            AccessPolicy.permitAll(),
                            TIMEOUT,
                            SecurityConfig.DEFAULT_MAX_CONCURRENT_AUTHENTICATIONS),
                    handlerExecutor::add,
                    gate,
                    List.of(),
                    permits,
                    RETRY_AFTER),
            sink);
    private final int readsAtStart = reads.count;

    /** A server that authenticates, which is what decides that a missing caller is a failure. */
    private final ServerEngine engine = newEngine(b -> b.security(s ->
            s.accessPolicy(AccessPolicy.permitAll()).authenticationProvider(request -> SecurityContext.anonymous())));

    @AfterEach
    void releaseEverything() {
        sink.messages.forEach(ReferenceCountUtil::release);
        channel.finishAndReleaseAll();
        engine.close();
    }

    @Test
    void holdsACompleteRequestAndReplaysItInOrderWithoutPausingReads() {
        final var head = head();
        final var first = content("hello");
        final var last = last("!");

        channel.writeInbound(head, first, last);

        assertThat(sink.messages).as("nothing moves on while the provider runs").isEmpty();
        assertThat(sink.readCompletes)
                .as("a read that delivered nothing is not completed")
                .isZero();
        assertThat(first.refCnt()).isEqualTo(1);
        assertThat(channel.config().isAutoRead())
                .as("head and last content arrived together: there is nothing more to read")
                .isTrue();
        final var readsAfterTheRead = readsSinceStart();

        accepted = SecurityContext.authenticated(() -> "alice", List.of());
        runProvider();

        assertThat(seenByProvider).hasSize(1);
        final var view = seenByProvider.getFirst();
        assertThat(view.method()).isEqualTo("POST");
        assertThat(view.uri()).isEqualTo("/mcp");
        assertThat(view.headers("x-test")).as("every value, whatever the case").containsExactly("a", "b");
        assertThat(sink.messages).containsExactly(head, first, last);
        assertThat(ChannelHandlerUtils.takeCaller(channel.pipeline().lastContext(), engine))
                .as("recorded for the handler that processes the request, and taken once")
                .isSameAs(accepted);
        assertThat(ChannelHandlerUtils.takeCaller(channel.pipeline().lastContext(), engine))
                .as("nothing is left behind for the next request")
                .isNull();
        assertThat(sink.readCompletes).isEqualTo(1);
        assertThat(readsSinceStart())
                .as("autoRead never flipped, so accepting forced no read beyond the usual re-arm")
                .isEqualTo(readsAfterTheRead);
    }

    @Test
    void aPartialBodyPausesReadsAtTheEndOfTheReadAndResumesOnceAccepted() {
        final var head = head();
        final var first = content("hello");

        channel.writeInbound(head, first);

        assertThat(sink.messages).isEmpty();
        assertThat(channel.config().isAutoRead())
                .as("the body is not complete, so reads stop when this one ends")
                .isFalse();
        assertThat(sink.readCompletes)
                .as("a read that delivered nothing is not completed")
                .isZero();
        assertThat(readsSinceStart()).as("a paused channel is not re-armed").isZero();

        accepted = SecurityContext.authenticated(() -> "alice", List.of());
        runProvider();

        assertThat(sink.messages).containsExactly(head, first);
        assertThat(channel.config().isAutoRead()).isTrue();
        assertThat(readsSinceStart()).as("resuming forces one read").isEqualTo(1);

        final var last = last("!");
        channel.writeInbound(last);
        assertThat(sink.messages).as("the rest of the body flows through live").endsWith(last);
    }

    @Test
    void moreThanTheHoldLimitPausesReadsBeforeTheReadEnds() {
        final var small = content("hello");
        final var big = new DefaultHttpContent(Unpooled.wrappedBuffer(new byte[AuthenticationHandler.HOLD_LIMIT]));

        channel.pipeline().fireChannelRead(head());
        channel.pipeline().fireChannelRead(small);
        assertThat(channel.config().isAutoRead()).as("still within the limit").isTrue();

        channel.pipeline().fireChannelRead(big);

        assertThat(channel.config().isAutoRead())
                .as("a read loop that keeps delivering must not fill memory for an unauthenticated caller")
                .isFalse();
    }

    @Test
    void aRefusalReleasesTheHeldBodyAndClosesAfterTheAnswer() {
        final var first = content("hello");
        final var last = last("!");
        channel.writeInbound(head(), first, last);

        refused = AuthenticationException.bearerInvalidToken("bad token");
        runProvider();

        final FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(response.headers().get(HttpHeaderNames.WWW_AUTHENTICATE))
                .isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(HttpUtil.isKeepAlive(response)).as("the body was never read").isFalse();
        assertThat(first.refCnt()).isZero();
        assertThat(last.refCnt()).isZero();
        assertThat(sink.messages).isEmpty();
        response.release();
    }

    @Test
    void aRejectionIsAnsweredWithTheChallengeOfItsOwnSchemeAndNeverBearer() {
        channel.writeInbound(head(), last(""));

        refused = AuthenticationException.unauthorized(
                "No API key", AuthenticationChallenge.of("ApiKey").withParam("realm", "orders"));
        runProvider();

        final FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.UNAUTHORIZED);
        assertThat(response.headers().getAll(HttpHeaderNames.WWW_AUTHENTICATE))
                .containsExactly("ApiKey realm=\"orders\"");
        response.release();
    }

    /**
     * K7: a verifier or an HTTP client can put the token in an exception, two causes deep. The WARN names
     * the class; the message and the causes are at TRACE only, where raw
     * request data belongs. This logger runs at DEBUG in tests ({@code simplelogger.properties}), and
     * slf4j-simple writes to {@code System.err} at the time of the call, so both lines are captured here.
     */
    @Test
    void aProviderFailureNeverLogsWhatTheExceptionSays() {
        final var token = "eyJ-super-secret-token";
        final var saved = System.err;
        final var captured = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            channel.writeInbound(head(), last(""));
            providerBug = new IllegalStateException(
                    "introspection failed", new IllegalArgumentException("Invalid token: " + token));
            runProvider();
        } finally {
            System.setErr(saved);
        }

        final FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        response.release();
        final var log = captured.toString(StandardCharsets.UTF_8);
        assertThat(log).contains("WARN", "Authentication provider failed", "java.lang.IllegalStateException");
        assertThat(LoggerFactory.getLogger(AuthenticationHandler.class).isDebugEnabled())
                .as("DEBUG is on, so its silence below is proof")
                .isTrue();
        assertThat(log)
                .as("neither the message nor any cause reaches WARN or DEBUG")
                .doesNotContain(token, "introspection failed");
    }

    /** At capacity the request is turned away before the provider is called, and no permit is taken. */
    @Test
    void aRequestOverTheCapacityIs503BeforeTheProviderRunsAndThePermitComesBack() {
        permits.drainPermits();
        channel.writeInbound(head(), last(""));

        final FullHttpResponse refused = channel.readOutbound();
        assertThat(refused.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(refused.headers().get(HttpHeaderNames.RETRY_AFTER))
                .as("the server's one Retry-After")
                .isEqualTo("7");
        assertThat(refused.headers().contains(HttpHeaderNames.WWW_AUTHENTICATE)).isFalse();
        assertThat(HttpUtil.isKeepAlive(refused)).isFalse();
        assertThat(seenByProvider).as("the provider is never asked").isEmpty();
        assertThat(handlerExecutor).isEmpty();
        assertThat(permits.availablePermits()).as("nothing was taken").isZero();
        refused.release();
    }

    @Test
    void aPermitIsHeldWhileTheProviderRunsAndGivenBackWhenTheRequestMovesOn() {
        channel.writeInbound(head(), last(""));
        assertThat(permits.availablePermits())
                .as("held while the provider runs")
                .isZero();

        accepted = SecurityContext.anonymous();
        runProvider();

        assertThat(sink.messages).hasSize(2);
        assertThat(permits.availablePermits())
                .as("given back once the request moved on")
                .isEqualTo(1);
    }

    /** A server shutting down cannot run the provider: that is no provider failure, and no permit stays taken. */
    @Test
    void anExecutorThatRefusesTheCallAnswers503AndKeepsNoPermit() {
        final var refusing = otherChannel(
                task -> {
                    throw new RejectedExecutionException("shutting down");
                },
                TIMEOUT,
                RETRY_AFTER);
        final var body = last("!");

        refusing.writeInbound(head(), body);
        refusing.runPendingTasks();

        final FullHttpResponse response = refusing.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get(HttpHeaderNames.RETRY_AFTER)).isEqualTo("7");
        assertThat(response.headers().contains(HttpHeaderNames.WWW_AUTHENTICATE))
                .as("no challenge: the client keeps a token that may be valid")
                .isFalse();
        assertThat(HttpUtil.isKeepAlive(response)).isFalse();
        assertThat(body.refCnt()).isZero();
        assertThat(permits.availablePermits()).isEqualTo(1);
        response.release();
        refusing.finishAndReleaseAll();
    }

    /** Anything else thrown on the way to the provider is a failure like the provider's own, and no permit stays taken. */
    @Test
    void anExecutorThatFailsAnswers500AndKeepsNoPermit() {
        final var failing = otherChannel(
                task -> {
                    throw new IllegalStateException("broken executor");
                },
                TIMEOUT,
                RETRY_AFTER);
        final var body = last("!");

        failing.writeInbound(head(), body);
        failing.runPendingTasks();

        final FullHttpResponse response = failing.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.INTERNAL_SERVER_ERROR);
        assertThat(HttpUtil.isKeepAlive(response)).isFalse();
        assertThat(body.refCnt()).isZero();
        assertThat(permits.availablePermits()).isEqualTo(1);
        response.release();
        failing.finishAndReleaseAll();
    }

    /** A timeout too long to count in nanoseconds waits as long as one can: no request fails on it. */
    @Test
    void anEndlessTimeoutAuthenticatesAndGivesThePermitBack() {
        final var endless = otherChannel(handlerExecutor::add, ChronoUnit.FOREVER.getDuration(), null);

        endless.writeInbound(head(), last(""));
        handlerExecutor.remove().run();
        endless.runPendingTasks();

        assertThat((Object) endless.readInbound()).isInstanceOf(HttpRequest.class);
        assertThat(permits.availablePermits()).isEqualTo(1);

        permits.drainPermits();
        endless.writeInbound(head(), last(""));

        final FullHttpResponse atCapacity = endless.readOutbound();
        assertThat(atCapacity.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(atCapacity.headers().contains(HttpHeaderNames.RETRY_AFTER))
                .as("none configured, none sent")
                .isFalse();
        atCapacity.release();
        endless.finishAndReleaseAll();
    }

    private EmbeddedChannel otherChannel(Executor executor, Duration timeout, @Nullable Duration retryAfter) {
        return new EmbeddedChannel(new AuthenticationHandler(
                channelSecurity(timeout), executor, new HttpPipeliningGate(16), List.of(), permits, retryAfter));
    }

    private static SecurityConfig channelSecurity(Duration timeout) {
        return new SecurityConfig(
                new SecurityConfig.Authentication.Custom(
                        request -> SecurityContext.authenticated(() -> "alice", List.of())),
                AccessPolicy.permitAll(),
                timeout,
                SecurityConfig.DEFAULT_MAX_CONCURRENT_AUTHENTICATIONS);
    }

    /** A verifier whose authorization server is down: keep the token, wait as the server says, retry. */
    @Test
    void aProviderThatCannotCheckRightNowAnswers503WithTheServersRetryAfterAndNoChallenge() {
        channel.writeInbound(head(), last(""));

        refused = AuthenticationException.unavailable("introspection is down");
        runProvider();

        final FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().get(HttpHeaderNames.RETRY_AFTER)).isEqualTo("7");
        assertThat(response.headers().contains(HttpHeaderNames.WWW_AUTHENTICATE))
                .isFalse();
        assertThat(HttpUtil.isKeepAlive(response)).isFalse();
        assertThat(sink.messages).isEmpty();
        response.release();
    }

    @Test
    void aBadRequestWithoutAChallengeSendsNone() {
        channel.writeInbound(head(), last(""));

        refused = AuthenticationException.badRequest("Two keys");
        runProvider();

        final FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.BAD_REQUEST);
        assertThat(response.headers().contains(HttpHeaderNames.WWW_AUTHENTICATE))
                .isFalse();
        response.release();
    }

    @Test
    void aProviderCannotChangeTheHeadItIsGiven() {
        final var head = head();
        final var attempts = new ArrayList<Throwable>();
        tampering = request -> {
            attempts.add(catchThrowable(() -> request.headers("X-Test").add("c")));
            attempts.add(catchThrowable(() -> request.headers("X-Test").clear()));
        };
        channel.writeInbound(head, last(""));

        accepted = SecurityContext.anonymous();
        runProvider();

        assertThat(attempts)
                .hasSize(2)
                .allSatisfy(e -> assertThat(e).isInstanceOf(UnsupportedOperationException.class));
        assertThat(head.headers().getAll("X-Test"))
                .as("the head the pipeline carries on with is untouched")
                .containsExactly("a", "b");
        assertThat(sink.messages).first().isSameAs(head);
    }

    /**
     * The provider is not interrupted, so the call cut off at the timeout keeps its permit until it
     * returns: a stuck provider is not called again past the limit.
     */
    @Test
    void aTimeoutAnswers503ReleasesTheBodyAndIgnoresALateAcceptance() {
        channel.freezeTime();
        final var first = content("hello");
        final var last = last("!");
        channel.writeInbound(head(), first, last);

        channel.advanceTimeBy(TIMEOUT.toMillis() - 1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertThat((Object) channel.readOutbound()).as("not yet").isNull();

        channel.advanceTimeBy(1, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        final FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.SERVICE_UNAVAILABLE);
        assertThat(response.headers().contains(HttpHeaderNames.RETRY_AFTER))
                .as("a timeout never sends one: a retry reaches the provider or is refused at capacity")
                .isFalse();
        assertThat(response.headers().contains(HttpHeaderNames.WWW_AUTHENTICATE))
                .as("no challenge: the client keeps a token that may be valid")
                .isFalse();
        assertThat(HttpUtil.isKeepAlive(response)).isFalse();
        assertThat(first.refCnt()).isZero();
        assertThat(last.refCnt()).isZero();
        assertThat(permits.availablePermits())
                .as("answered at the timeout, but the provider still runs")
                .isZero();
        response.release();

        accepted = SecurityContext.authenticated(() -> "alice", List.of());
        runProvider();

        assertThat(sink.messages).as("a late result changes nothing").isEmpty();
        assertThat((Object) channel.readOutbound()).isNull();
        assertThat(permits.availablePermits())
                .as("given back once the cut-off call returned")
                .isEqualTo(1);
    }

    @Test
    void anAnswerInTimeCancelsTheTimeout() {
        channel.freezeTime();
        channel.writeInbound(head(), last(""));
        accepted = SecurityContext.anonymous();
        runProvider();

        channel.advanceTimeBy(TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        assertThat((Object) channel.readOutbound())
                .as("no 503 for a request already admitted")
                .isNull();
        assertThat(sink.messages).hasSize(2);
    }

    @Test
    void closingTheChannelWhileAuthenticatingReleasesWhatIsHeld() {
        final var first = content("hello");
        final var last = last("!");
        channel.writeInbound(head(), first, last);

        channel.close();

        assertThat(first.refCnt()).isZero();
        assertThat(last.refCnt()).isZero();
        assertThat(permits.availablePermits())
                .as("the channel closed, but the provider still runs")
                .isZero();

        accepted = SecurityContext.anonymous();
        runProvider();

        assertThat(sink.messages)
                .as("a late result for a closed channel does nothing")
                .isEmpty();
        assertThat(permits.availablePermits())
                .as("given back once the orphaned call returned")
                .isEqualTo(1);
    }

    @Test
    void removingTheHandlerWhileAuthenticatingReleasesWhatIsHeld() {
        final var first = content("hello");
        channel.writeInbound(head(), first);

        channel.pipeline().remove(AuthenticationHandler.class);

        assertThat(first.refCnt()).isZero();
    }

    /** Turning {@code autoRead} back on makes the channel read, so a pause that was lifted shows up here. */
    private int readsSinceStart() {
        return reads.count - readsAtStart;
    }

    private void runProvider() {
        handlerExecutor.remove().run();
        channel.runPendingTasks();
    }

    private static HttpRequest head() {
        final var head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/mcp");
        head.headers().add("X-Test", "a").add("x-test", "b");
        return head;
    }

    private static HttpContent content(String text) {
        return new DefaultHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    private static LastHttpContent last(String text) {
        return new DefaultLastHttpContent(Unpooled.copiedBuffer(text, StandardCharsets.UTF_8));
    }

    /** First in the pipeline: counts every {@code read()} the channel is asked to perform. */
    private static final class ReadCounter extends ChannelOutboundHandlerAdapter {
        int count;

        @Override
        public void read(ChannelHandlerContext ctx) {
            count++;
            ctx.read();
        }
    }

    private static final class Sink extends ChannelInboundHandlerAdapter {
        final List<Object> messages = new ArrayList<>();
        int readCompletes;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            messages.add(msg);
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            readCompletes++;
        }
    }
}
