/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.json.JsonSchema;
import dev.tachyonmcp.api.runtime.AttributeKey;
import dev.tachyonmcp.api.runtime.ContextNotifications;
import dev.tachyonmcp.api.runtime.ElicitationRequest;
import dev.tachyonmcp.api.runtime.ElicitationResult;
import dev.tachyonmcp.api.runtime.InteractionContext;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class WireClientContextTest {

    private static WireClientContext contextReturning(String rawJsonResponse) {
        return new WireClientContext(new StubInteractionContext(rawJsonResponse));
    }

    private static CompletableFuture<ElicitationResult> create(WireClientContext ctx) {
        return ctx.elicitation()
                .create(ElicitationRequest.builder()
                        .message("please fill this in")
                        .requestedSchema(JsonSchema.objectSchema())
                        .build());
    }

    @Test
    void acceptWithObjectContent_returnsElicitationResultWithContent() throws Exception {
        var ctx = contextReturning("""
            {"action":"accept","content":{"name":"Ada"}}""");

        var result = create(ctx).get();

        assertThat(result.action()).isEqualTo(ElicitationResult.Action.ACCEPT);
        assertThat(result.content()).isNotNull();
        assertThat(result.content().asMap()).containsEntry("name", "Ada");
    }

    @Test
    void acceptWithMissingContent_failsInsteadOfReturningNullContent() {
        var ctx = contextReturning("""
            {"action":"accept"}""");

        assertThatThrownBy(() -> create(ctx).get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @Test
    void acceptWithNullContent_failsInsteadOfReturningNullContent() {
        var ctx = contextReturning("""
            {"action":"accept","content":null}""");

        assertThatThrownBy(() -> create(ctx).get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @Test
    void acceptWithNonObjectContent_failsInsteadOfReturningNullContent() {
        var ctx = contextReturning("""
            {"action":"accept","content":"not-an-object"}""");

        assertThatThrownBy(() -> create(ctx).get())
                .isInstanceOf(ExecutionException.class)
                .cause()
                .isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    @Test
    void decline_returnsResultWithNullContentWithoutValidation() throws Exception {
        var ctx = contextReturning("""
            {"action":"decline"}""");

        var result = create(ctx).get();

        assertThat(result.action()).isEqualTo(ElicitationResult.Action.DECLINE);
        assertThat(result.content()).isNull();
    }

    @Test
    void cancel_returnsResultWithNullContentWithoutValidation() throws Exception {
        var ctx = contextReturning("""
            {"action":"cancel"}""");

        var result = create(ctx).get();

        assertThat(result.action()).isEqualTo(ElicitationResult.Action.CANCEL);
        assertThat(result.content()).isNull();
    }

    private static final class StubInteractionContext implements InteractionContext {
        private final String response;

        StubInteractionContext(String response) {
            this.response = response;
        }

        @Override
        public String protocolVersion() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Lifecycle lifecycle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String sessionId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isExtensionEnabled(String extensionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContextNotifications notifications() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.tachyonmcp.api.runtime.ClientContext client() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<String> sendRequest(String method, Object params) {
            return CompletableFuture.completedFuture(response);
        }

        @Override
        public <T> Optional<T> get(AttributeKey<T> key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> void set(AttributeKey<T> key, T value) {
            throw new UnsupportedOperationException();
        }
    }
}
