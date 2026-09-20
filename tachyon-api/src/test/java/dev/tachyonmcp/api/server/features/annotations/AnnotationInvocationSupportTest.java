/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.api.server.features.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpTool;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

class AnnotationInvocationSupportTest {

    interface Greeter {
        @McpTool(name = "greet")
        String greet(String name);
    }

    static class PlainGreeter implements Greeter {
        @Override
        public String greet(String name) {
            return "Hello, " + name;
        }
    }

    /**
     * The whole point of walking interfaces: {@code Class#getMethods} hands back the implementing
     * class's method, which carries none of the interface's annotations, so the annotated
     * declaration is only reachable through the interface itself.
     */
    @Test
    void discoversAnAnnotationDeclaredOnAnInterfaceAndStillRunsTheOverride() throws Exception {
        var methods = AnnotationInvocationSupport.discoverMethods(PlainGreeter.class, McpTool.class);

        assertThat(methods).hasSize(1);
        Method method = methods.getFirst();
        assertThat(method.getDeclaringClass()).isEqualTo(Greeter.class);
        assertThat(method.getAnnotation(McpTool.class).name()).isEqualTo("greet");
        assertThat(method.invoke(new PlainGreeter(), "Ada"))
                .as("Method#invoke dispatches virtually, so the interface's Method runs the implementation")
                .isEqualTo("Hello, Ada");
    }

    static class ReannotatedGreeter implements Greeter {
        @Override
        @McpTool(name = "greet-louder")
        public String greet(String name) {
            return "HELLO, " + name;
        }
    }

    @Test
    void aReannotatedOverrideWinsOverTheInterfaceDeclaration() {
        var methods = AnnotationInvocationSupport.discoverMethods(ReannotatedGreeter.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(ReannotatedGreeter.class);
        assertThat(methods.getFirst().getAnnotation(McpTool.class).name()).isEqualTo("greet-louder");
    }

    interface Operation<T> {
        @McpTool(name = "run")
        String run(T input);
    }

    static class StringOperation implements Operation<String> {
        @Override
        public String run(String input) {
            return input.toUpperCase(java.util.Locale.ROOT);
        }
    }

    /**
     * The compiler emits a bridge {@code run(Object)} on the implementation. Keying discovery on the
     * erased signature alone would register the generic declaration and the narrower override as two
     * separate tools named {@code run}.
     */
    @Test
    void aGenericInterfaceDoesNotYieldTheSameToolTwice() throws Exception {
        var methods = AnnotationInvocationSupport.discoverMethods(StringOperation.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(Operation.class);
        assertThat(methods.getFirst().invoke(new StringOperation(), "ada")).isEqualTo("ADA");
    }

    abstract static class Base {
        @McpTool(name = "inherited")
        String inherited() {
            return "base";
        }
    }

    static class Derived extends Base {}

    @Test
    void stillWalksTheSuperclassChain() {
        var methods = AnnotationInvocationSupport.discoverMethods(Derived.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(Base.class);
    }

    interface Mixed {
        @McpTool(name = "tool")
        String tool();

        @McpPrompt(name = "prompt")
        String prompt();

        String notAFeature();
    }

    static class MixedImpl implements Mixed {
        @Override
        public String tool() {
            return "t";
        }

        @Override
        public String prompt() {
            return "p";
        }

        @Override
        public String notAFeature() {
            return "n";
        }
    }

    @Test
    void collectsEveryRequestedAnnotationAndNothingElse() {
        var methods = AnnotationInvocationSupport.discoverMethods(MixedImpl.class, McpTool.class, McpPrompt.class);

        assertThat(methods).extracting(Method::getName).containsExactlyInAnyOrder("tool", "prompt");
    }

    private interface PrivateGreeter {
        @McpTool(name = "hidden")
        String hidden();
    }

    static class PrivatelyDeclared implements PrivateGreeter {
        @Override
        public String hidden() {
            return "h";
        }
    }

    /** The private-declaration policy applies to inherited declarations too, not just declared ones. */
    @Test
    void rejectsAnAnnotationDeclaredInAPrivateInterface() {
        assertThatThrownBy(() -> AnnotationInvocationSupport.discoverMethods(PrivatelyDeclared.class, McpTool.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be private");
    }
}
