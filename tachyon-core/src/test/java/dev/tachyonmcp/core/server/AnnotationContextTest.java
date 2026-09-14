/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import dev.tachyonmcp.api.server.features.annotations.AnnotationProvider;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AnnotationContext}'s DSL contract: the active {@link AnnotationProvider} at the
 * time {@link AnnotationContext#register} is called is the one each registration dispatches
 * through, and misuse (registering before a provider is set) fails fast.
 */
class AnnotationContextTest {

    private static final AnnotationProvider NOOP_PROVIDER = (instance, context) -> {};

    @Test
    void registerWithoutProviderUsesTachyonAnnotations() {
        var instance = new Object();

        var context = new AnnotationContext().register(instance);

        assertThat(context.registrations())
                .containsExactly(new AnnotationContext.Registration(TachyonAnnotationProvider.instance(), instance));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void nullProviderRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AnnotationContext().withProvider(null));
    }

    @SuppressWarnings("DataFlowIssue")
    @Test
    void nullInstanceRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        new AnnotationContext().withProvider(NOOP_PROVIDER).register(null));
    }

    @Test
    void eachRegistrationDispatchesToTheProviderActiveAtItsOwnCall() {
        AnnotationProvider first = (instance, context) -> {};
        AnnotationProvider second = (instance, context) -> {};
        var a = new Object();
        var b = new Object();
        var c = new Object();

        var context = new AnnotationContext()
                .withProvider(first)
                .register(a)
                .withProvider(second)
                .register(b)
                .withProvider(first)
                .register(c);

        assertThat(context.registrations())
                .containsExactly(
                        new AnnotationContext.Registration(first, a),
                        new AnnotationContext.Registration(second, b),
                        new AnnotationContext.Registration(first, c));
    }
}
