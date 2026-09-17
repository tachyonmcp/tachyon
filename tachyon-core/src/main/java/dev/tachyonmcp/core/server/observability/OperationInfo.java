/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.observability;

import dev.tachyonmcp.api.annotations.InternalApi;
import dev.tachyonmcp.api.server.domain.RequestId;
import org.jspecify.annotations.Nullable;

/**
 * Identity facts for one inbound MCP operation (request, notification, or {@code initialize}),
 * independent of JSON-RPC id so repeated ids across sessions never collide.
 *
 * <p>Created once, as early as {@code method} (and {@code id}, if any) are known. {@link
 * #sessionId(String)} and {@link #traceparent(String)} are set at most once, as that information
 * becomes available, and are safe to read from a different thread once set: every write here
 * happens-before the {@link ObservationListener#complete} call that follows it, via the same
 * {@code CompletableFuture} chain the dispatcher already uses. The independently written streaming
 * establishment timestamp is volatile so shutdown completion also observes it.
 */
@InternalApi
public final class OperationInfo {

    private final OperationKind kind;
    private final String method;
    private final @Nullable RequestId requestId;

    private @Nullable String sessionId;
    private @Nullable String traceParent;
    private @Nullable String protocolVersion;
    private @Nullable String serverAddress;
    private @Nullable Integer serverPort;
    private @Nullable CapturedPayload requestPayload;
    private @Nullable CapturedPayload responsePayload;
    private @Nullable String target;
    private @Nullable Throwable exceptionCause;
    private volatile @Nullable Long establishmentNanos;

    public OperationInfo(OperationKind kind, String method, @Nullable RequestId requestId) {
        this.kind = kind;
        this.method = method;
        this.requestId = requestId;
    }

    /** A builder covering the fields known up front, at construction time. */
    public static Builder builder(OperationKind kind, String method, @Nullable RequestId requestId) {
        return new Builder(kind, method, requestId);
    }

    public OperationKind kind() {
        return kind;
    }

    public String method() {
        return method;
    }

    public @Nullable RequestId requestId() {
        return requestId;
    }

    public @Nullable String sessionId() {
        return sessionId;
    }

    public void sessionId(@Nullable String sessionId) {
        this.sessionId = sessionId;
    }

    public @Nullable String traceparent() {
        return traceParent;
    }

    public void traceparent(@Nullable String traceparent) {
        this.traceParent = traceparent;
    }

    /** The MCP protocol version negotiated for the channel this operation arrived on, if known. */
    public @Nullable String protocolVersion() {
        return protocolVersion;
    }

    public void protocolVersion(@Nullable String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    /** The server's bound host, or {@code null} when the server hadn't started yet at dispatch time. */
    public @Nullable String serverAddress() {
        return serverAddress;
    }

    public void serverAddress(@Nullable String serverAddress) {
        this.serverAddress = serverAddress;
    }

    /** The server's bound port, or {@code null} when the server hadn't started yet at dispatch time. */
    public @Nullable Integer serverPort() {
        return serverPort;
    }

    public void serverPort(@Nullable Integer serverPort) {
        this.serverPort = serverPort;
    }

    public @Nullable CapturedPayload requestPayload() {
        return requestPayload;
    }

    public void requestPayload(@Nullable CapturedPayload requestPayload) {
        this.requestPayload = requestPayload;
    }

    public @Nullable CapturedPayload responsePayload() {
        return responsePayload;
    }

    public void responsePayload(@Nullable CapturedPayload responsePayload) {
        this.responsePayload = responsePayload;
    }

    /**
     * The resolved target name for {@code tools/call}/{@code prompts/get} (a {@link
     * dev.tachyonmcp.api.server.features.tools.ToolDescriptor}/{@link
     * dev.tachyonmcp.api.server.features.prompts.PromptDescriptor} name), or {@code null} when
     * not applicable or not yet resolved. Always the resolved descriptor's own name — never the
     * raw wire parameter — so a client cannot pump arbitrary, unbounded values into it.
     */
    public @Nullable String target() {
        return target;
    }

    public void target(@Nullable String target) {
        this.target = target;
    }

    /**
     * The throwable a feature handler (tool/resource/prompt/completion) converted into a {@link
     * dev.tachyonmcp.api.server.domain.ServerError} value rather than letting propagate, captured
     * only when the server's opt-in exception-detail capture policy is enabled -- that conversion
     * is otherwise the one place the original throwable is lost before an {@code HandlerFailed}
     * outcome is built.
     */
    public @Nullable Throwable exceptionCause() {
        return exceptionCause;
    }

    public void exceptionCause(@Nullable Throwable exceptionCause) {
        this.exceptionCause = exceptionCause;
    }

    /**
     * The {@link System#nanoTime()} reading at which a streaming operation's own synchronous
     * establishment (e.g. {@code subscriptions/listen}'s ack) finished, or {@code null} for an
     * ordinary request/notification. Lets a duration-recording listener measure just that latency
     * at {@link ObservationListener#complete} instead of the operation's full — potentially very
     * long — lifetime through to its terminal outcome, which is what the completion timestamp would
     * otherwise measure once completion is deferred past establishment.
     */
    public @Nullable Long establishmentNanos() {
        return establishmentNanos;
    }

    public void establishmentNanos(long establishmentNanos) {
        this.establishmentNanos = establishmentNanos;
    }

    /**
     * Builder for the fields known up front, at construction time -- {@link #target}, the captured
     * payloads, and (usually) {@link #sessionId} are only resolved later in the dispatch lifecycle
     * and stay direct setters on the built {@link OperationInfo}.
     */
    public static final class Builder {

        private final OperationKind kind;
        private final String method;
        private final @Nullable RequestId requestId;

        private @Nullable String sessionId;
        private @Nullable String traceParent;
        private @Nullable String protocolVersion;
        private @Nullable String serverAddress;
        private @Nullable Integer serverPort;

        private Builder(OperationKind kind, String method, @Nullable RequestId requestId) {
            this.kind = kind;
            this.method = method;
            this.requestId = requestId;
        }

        public Builder sessionId(@Nullable String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder traceparent(@Nullable String traceparent) {
            this.traceParent = traceparent;
            return this;
        }

        public Builder protocolVersion(@Nullable String protocolVersion) {
            this.protocolVersion = protocolVersion;
            return this;
        }

        public Builder serverAddress(@Nullable String serverAddress) {
            this.serverAddress = serverAddress;
            return this;
        }

        public Builder serverPort(@Nullable Integer serverPort) {
            this.serverPort = serverPort;
            return this;
        }

        public OperationInfo build() {
            var info = new OperationInfo(kind, method, requestId);
            info.sessionId = sessionId;
            info.traceParent = traceParent;
            info.protocolVersion = protocolVersion;
            info.serverAddress = serverAddress;
            info.serverPort = serverPort;
            return info;
        }
    }
}
