/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.annotations.AnnotationInvocationSupport;
import dev.tachyonmcp.api.server.features.annotations.ResolvedParameter;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.TypeVariable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AnnotationInvocationSupportTest {

    private static final JacksonPayloadSerde SERDE = new JacksonPayloadSerde();

    static Stream<Arguments> numericTypes() {
        return Stream.of(
                Arguments.of(int.class, "integer", Integer.class),
                Arguments.of(Integer.class, "integer", Integer.class),
                Arguments.of(long.class, "integer", Long.class),
                Arguments.of(Long.class, "integer", Long.class),
                Arguments.of(short.class, "integer", Short.class),
                Arguments.of(Short.class, "integer", Short.class),
                Arguments.of(byte.class, "integer", Byte.class),
                Arguments.of(Byte.class, "integer", Byte.class),
                Arguments.of(double.class, "number", Double.class),
                Arguments.of(Double.class, "number", Double.class),
                Arguments.of(float.class, "number", Float.class),
                Arguments.of(Float.class, "number", Float.class));
    }

    @ParameterizedTest
    @MethodSource("numericTypes")
    void schemaTypeAndCoercionAgreeForEveryNumericType(
            Class<?> type, String expectedJsonType, Class<?> expectedWrapper) {
        assertThat(AnnotationInvocationSupport.jsonSchemaType(type)).isEqualTo(expectedJsonType);
        assertThat(AnnotationInvocationSupport.coerce(7, type, SERDE, SERDE)).isInstanceOf(expectedWrapper);
    }

    @Test
    void booleanAndStringUseTheirOwnJsonType() {
        assertThat(AnnotationInvocationSupport.jsonSchemaType(boolean.class)).isEqualTo("boolean");
        assertThat(AnnotationInvocationSupport.jsonSchemaType(Boolean.class)).isEqualTo("boolean");
        assertThat(AnnotationInvocationSupport.jsonSchemaType(String.class)).isEqualTo("string");
    }

    @Test
    void unsupportedTypeIsRejectedByJsonSchemaType() {
        assertThatThrownBy(() -> AnnotationInvocationSupport.jsonSchemaType(Thread.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Thread");
        assertThatThrownBy(() -> AnnotationInvocationSupport.jsonSchemaType(UUID.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUID");
    }

    @Test
    void requireBindableRejectsUnsupportedParameterTypeNamingMethodAndParameter() throws NoSuchMethodException {
        Method method = Fixture.class.getDeclaredMethod("unsupported", UUID.class);
        var param = method.getParameters()[0];

        assertThatThrownBy(() -> AnnotationInvocationSupport.requireBindable(param, method))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UUID")
                .hasMessageContaining("id")
                .hasMessageContaining("unsupported");
    }

    @Test
    void requireBindableAcceptsSupportedScalarAndOptionalParameters() throws NoSuchMethodException {
        Method method = Fixture.class.getDeclaredMethod("supported", String.class, Optional.class, OptionalInt.class);
        for (var param : method.getParameters()) {
            AnnotationInvocationSupport.requireBindable(param, method);
        }
    }

    @Test
    void coerceReturnsNullForNullInput() {
        assertThat(AnnotationInvocationSupport.coerce(null, int.class, SERDE, SERDE))
                .isNull();
    }

    @Test
    void coerceLeavesValueAlreadyMatchingTypeUnchanged() {
        String value = "already-typed";
        assertThat(AnnotationInvocationSupport.coerce(value, String.class, SERDE, SERDE))
                .isSameAs(value);
    }

    @Test
    void coerceWrapsPresentValueIntoOptionalUsingFullGenericType() throws NoSuchMethodException {
        Method method = Fixture.class.getDeclaredMethod("supported", String.class, Optional.class, OptionalInt.class);
        var optionalStringType = method.getGenericParameterTypes()[1];

        Object result = AnnotationInvocationSupport.coerce("abc", optionalStringType, SERDE, SERDE);

        assertThat(result).isEqualTo(Optional.of("abc"));
    }

    @Test
    void coerceWrapsPresentValueIntoOptionalInt() {
        Object result = AnnotationInvocationSupport.coerce(7, OptionalInt.class, SERDE, SERDE);

        assertThat(result).isEqualTo(OptionalInt.of(7));
    }

    @Test
    void isOptionalTypeRecognizesAllFourOptionalFamilyTypes() {
        assertThat(AnnotationInvocationSupport.isOptionalType(Optional.class)).isTrue();
        assertThat(AnnotationInvocationSupport.isOptionalType(OptionalInt.class))
                .isTrue();
        assertThat(AnnotationInvocationSupport.isOptionalType(java.util.OptionalLong.class))
                .isTrue();
        assertThat(AnnotationInvocationSupport.isOptionalType(java.util.OptionalDouble.class))
                .isTrue();
        assertThat(AnnotationInvocationSupport.isOptionalType(String.class)).isFalse();
    }

    @Test
    void unwrapReturnsCheckedExceptionCause() {
        var cause = new java.io.IOException("boom");
        var ite = new InvocationTargetException(cause);

        assertThat(AnnotationInvocationSupport.unwrap(ite)).isSameAs(cause);
    }

    @Test
    void unwrapThrowsErrorCauseDirectlySinceErrorIsNotAnException() {
        var cause = new StackOverflowError("boom");
        var ite = new InvocationTargetException(cause);

        assertThatThrownBy(() -> AnnotationInvocationSupport.unwrap(ite)).isSameAs(cause);
    }

    @Test
    void unwrapReturnsTheWrapperItselfWhenCauseIsMissing() {
        var ite = new InvocationTargetException(null);

        assertThat(AnnotationInvocationSupport.unwrap(ite)).isSameAs(ite);
    }

    @Test
    void inputSchemaWrapsPropertiesInAnObjectEnvelopeKeepingDeclarationOrder() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("who", Map.of("type", "string"));
        properties.put("times", Map.of("type", "integer"));

        var json = AnnotationInvocationSupport.inputSchema(properties, List.of("who"))
                .json();

        assertThat(json)
                .contains("\"type\":\"object\"")
                .contains("\"required\":[\"who\"]")
                .containsSubsequence("\"who\"", "\"times\"");
    }

    @Test
    void inputSchemaOmitsRequiredEntirelyWhenNoParameterIsRequired() {
        var json = AnnotationInvocationSupport.inputSchema(Map.of("who", Map.of("type", "string")), List.of())
                .json();

        assertThat(json).contains("\"properties\"").doesNotContain("\"required\"");
    }

    interface Runner {
        @McpTool(name = "run-object")
        String run(Object input);
    }

    static class OverloadedRunner implements Runner {
        @Override
        public String run(Object input) {
            return "object:" + input;
        }

        @McpTool(name = "run-string")
        public String run(String input) {
            return "string:" + input;
        }
    }

    /** {@code run(String)} overloads {@code run(Object)}; it does not override it, so both declarations stay. */
    @Test
    void anAnnotatedOverloadDoesNotHideAnAnnotatedInterfaceDeclaration() throws Exception {
        final var methods = AnnotationInvocationSupport.discoverMethods(OverloadedRunner.class, McpTool.class);
        final var target = new OverloadedRunner();

        assertThat(methods).hasSize(2);
        assertThat(methods)
                .extracting(m -> m.getAnnotation(McpTool.class).name())
                .containsExactlyInAnyOrder("run-object", "run-string");
        assertThat(byToolName(methods, "run-object").getDeclaringClass()).isEqualTo(Runner.class);
        assertThat(byToolName(methods, "run-string").getDeclaringClass()).isEqualTo(OverloadedRunner.class);
        assertThat(byToolName(methods, "run-object").invoke(target, "x")).isEqualTo("object:x");
        assertThat(byToolName(methods, "run-string").invoke(target, "x")).isEqualTo("string:x");
    }

    interface Operation<T> {
        @McpTool(name = "run")
        String run(T input);
    }

    static class StringOperation implements Operation<String> {
        @Override
        @McpTool(name = "run-upper")
        public String run(String input) {
            return input.toUpperCase(Locale.ROOT);
        }
    }

    static class OverloadedStringOperation implements Operation<String> {
        @Override
        public String run(String input) {
            return "string:" + input;
        }

        @McpTool(name = "run-chars")
        public String run(CharSequence input) {
            return "chars:" + input;
        }
    }

    /**
     * javac emits a bridge {@code run(Object)} on the implementation. The re-annotated {@code
     * run(String)} and the generic declaration are one method, recognised without leaning on that bridge.
     */
    @Test
    void aReannotatedOverrideOfAGenericDeclarationWinsOverIt() throws Exception {
        final var methods = AnnotationInvocationSupport.discoverMethods(StringOperation.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(StringOperation.class);
        assertThat(methods.getFirst().getAnnotation(McpTool.class).name()).isEqualTo("run-upper");
        assertThat(methods.getFirst().invoke(new StringOperation(), "ada")).isEqualTo("ADA");
    }

    @Test
    void anAnnotatedOverloadDoesNotHideAGenericInterfaceDeclarationBoundToAnotherType() throws Exception {
        final var methods = AnnotationInvocationSupport.discoverMethods(OverloadedStringOperation.class, McpTool.class);
        final var target = new OverloadedStringOperation();

        assertThat(methods).hasSize(2);
        assertThat(byToolName(methods, "run").getDeclaringClass()).isEqualTo(Operation.class);
        assertThat(byToolName(methods, "run-chars").getDeclaringClass()).isEqualTo(OverloadedStringOperation.class);
        assertThat(byToolName(methods, "run").invoke(target, "x")).isEqualTo("string:x");
        assertThat(byToolName(methods, "run-chars").invoke(target, "x")).isEqualTo("chars:x");
    }

    interface Echo<T> {
        @McpTool(name = "echo")
        String echo(T value);
    }

    abstract static class RelayedEcho<U> implements Echo<U> {}

    static class StringEcho extends RelayedEcho<String> {
        @Override
        @McpTool(name = "echo-string")
        public String echo(String value) {
            return value;
        }
    }

    @Test
    void aTypeArgumentRelayedThroughAnIntermediateClassStillResolvesTheDeclaration() {
        final var methods = AnnotationInvocationSupport.discoverMethods(StringEcho.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(StringEcho.class);
    }

    interface Batch<T> {
        @McpTool(name = "batch")
        String run(T[] items, List<T> more);
    }

    static class StringBatch implements Batch<String> {
        @Override
        @McpTool(name = "batch-strings")
        public String run(String[] items, List<String> more) {
            return items.length + ":" + more.size();
        }
    }

    @Test
    void arrayAndParameterizedParametersResolveThroughTheTypeArgument() throws Exception {
        final var methods = AnnotationInvocationSupport.discoverMethods(StringBatch.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(StringBatch.class);
        assertThat(methods.getFirst().invoke(new StringBatch(), new String[] {"a", "b"}, List.of("c")))
                .isEqualTo("2:1");
    }

    static class GenericBase<T> {
        @McpTool(name = "base-run")
        public String run(T input) {
            return "base:" + input;
        }
    }

    static class StringDerived extends GenericBase<String> {
        @Override
        @McpTool(name = "derived-run")
        public String run(String input) {
            return "derived:" + input;
        }
    }

    /** Same override rule on the superclass chain: {@code run(String)} implements {@code GenericBase<String>.run(T)}. */
    @Test
    void aReannotatedOverrideOfAGenericSuperclassMethodWinsOverIt() throws Exception {
        final var methods = AnnotationInvocationSupport.discoverMethods(StringDerived.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(StringDerived.class);
        assertThat(methods.getFirst().getAnnotation(McpTool.class).name()).isEqualTo("derived-run");
        assertThat(methods.getFirst().invoke(new StringDerived(), "x")).isEqualTo("derived:x");
    }

    static class PublicBase {
        @McpTool(name = "inherited")
        public String inherited() {
            return "base";
        }
    }

    static class PublicDerived extends PublicBase {}

    /** A public inherited method is visible to both the superclass walk and {@code Class#getMethods}. */
    @Test
    void anInheritedAnnotatedMethodIsDiscoveredOnce() throws Exception {
        final var methods = AnnotationInvocationSupport.discoverMethods(PublicDerived.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(PublicBase.class);
        assertThat(methods.getFirst().invoke(new PublicDerived())).isEqualTo("base");
    }

    interface Greeter {
        @McpTool(name = "greet")
        String greet(String name);
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
        final var methods = AnnotationInvocationSupport.discoverMethods(ReannotatedGreeter.class, McpTool.class);

        assertThat(methods).hasSize(1);
        assertThat(methods.getFirst().getDeclaringClass()).isEqualTo(ReannotatedGreeter.class);
        assertThat(methods.getFirst().getAnnotation(McpTool.class).name()).isEqualTo("greet-louder");
    }

    interface Repository<T, ID> {
        @McpTool(name = "find")
        T find(ID id, List<T> hints, T[] more);
    }

    static class StringRepository implements Repository<String, Integer> {
        @Override
        public String find(Integer id, List<String> hints, String[] more) {
            return "found";
        }
    }

    @Test
    void parametersResolveTypeVariablesAgainstTheScannedClass() throws Exception {
        final var find = Repository.class.getMethod("find", Object.class, List.class, Object[].class);

        final var parameters = AnnotationInvocationSupport.parameters(find, StringRepository.class);

        assertThat(parameters)
                .extracting(p -> p.type().getTypeName())
                .containsExactly("java.lang.Integer", "java.util.List<java.lang.String>", "java.lang.String[]");
        assertThat(parameters)
                .extracting(ResolvedParameter::rawType)
                .containsExactly(Integer.class, List.class, String[].class);
        assertThat(parameters).extracting(p -> p.parameter().getName()).containsExactly("id", "hints", "more");
        assertThat(AnnotationInvocationSupport.returnType(find, StringRepository.class))
                .isEqualTo(String.class);
    }

    @Test
    void variablesTheScannedClassLeavesUnboundKeepTheirDeclaredBound() throws Exception {
        final var find = Repository.class.getMethod("find", Object.class, List.class, Object[].class);

        final var parameters = AnnotationInvocationSupport.parameters(find, Repository.class);

        assertThat(parameters)
                .extracting(ResolvedParameter::rawType)
                .containsExactly(Object.class, List.class, Object[].class);
        assertThat(parameters.getFirst().type()).isInstanceOf(TypeVariable.class);
    }

    private static Method byToolName(List<Method> methods, String name) {
        return methods.stream()
                .filter(m -> m.getAnnotation(McpTool.class).name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unused")
    private static final class Fixture {
        void unsupported(UUID id) {}

        void supported(String name, Optional<String> filter, OptionalInt limit) {}
    }
}
