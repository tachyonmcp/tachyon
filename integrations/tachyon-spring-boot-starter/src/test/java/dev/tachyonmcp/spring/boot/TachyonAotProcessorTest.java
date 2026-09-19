/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.example.aot.NamespacedBeanConfig;
import dev.tachyonmcp.example.aot.NamespacedBeanConfig.Farewell;
import example.aot.InterfaceBeanConfig;
import example.aot.InterfaceBeanConfig.Greeter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.GeneratedMethods;
import org.springframework.aot.generate.MethodReference;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.javapoet.ClassName;

class TachyonAotProcessorTest {

    static class WeatherService {
        @McpTool(name = "get-weather", description = "Weather for a city")
        ToolResult getWeather(String city) {
            return ToolResult.text(city);
        }
    }

    static class PlainService {
        String notATool() {
            return "";
        }
    }

    private static RuntimeHints process(Class<?>... beanTypes) {
        final var contribution = new TachyonAotProcessor().processAheadOfTime(beanFactoryWith(beanTypes));
        final var generationContext = new TestGenerationContext();
        if (contribution != null) {
            contribution.applyTo(generationContext, new NoOpInitializationCode(generationContext));
        }
        return generationContext.getRuntimeHints();
    }

    @Test
    void registersReflectionForAnnotatedBeanMethods() {
        final var hints = process(WeatherService.class, PlainService.class);

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(WeatherService.class)
                        .test(hints))
                .as("the type declaring @McpTool needs reflection hints")
                .isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                        .onMethodInvocation(WeatherService.class, "getWeather")
                        .test(hints))
                .as("TachyonBeanRegistrar invokes the annotated method reflectively")
                .isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(PlainService.class)
                        .test(hints))
                .as("a bean with no Tachyon annotations must not be registered")
                .isFalse();
    }

    /**
     * Nothing in the annotation layer reads a field — {@code MethodInvoker} reaches features through
     * {@code Method#setAccessible}. A field hint would widen every user feature bean's reflective
     * surface in the native image for no gain.
     */
    @Test
    void doesNotRequestReflectiveFieldAccess() {
        final var hints = process(WeatherService.class);

        assertThat(RuntimeHintsPredicates.reflection()
                        .onType(WeatherService.class)
                        .withMemberCategory(MemberCategory.ACCESS_DECLARED_FIELDS)
                        .test(hints))
                .as("feature beans are invoked, never field-injected")
                .isFalse();
    }

    /**
     * A {@code @Bean} method returning an interface builds its implementation inside the method
     * body, so the annotated class does not exist during AOT processing and gets no hints — while
     * the runtime registrar finds it through {@code AopUtils.getTargetClass} and registers it. On
     * the JVM that gap is invisible; in a native image the tool is silently missing. The build
     * warning is the only signal a user gets.
     */
    @Test
    void reportsInterfaceTypedBeansWhoseImplementationIsInvisible() {
        final var reported = uninspectableBeans(InterfaceBeanConfig.class);

        assertThat(reported).containsKey("greeter");
        assertThat(reported.get("greeter").interfaceType()).isEqualTo(Greeter.class.getName());
        assertThat(reported.get("greeter").declaringType()).isEqualTo(InterfaceBeanConfig.class.getName());

        assertThat(reported)
                .as("a concrete @Bean return type is fully inspectable, so warning about it is noise")
                .doesNotContainKey("visibleGreeter");
    }

    /**
     * The starter's own {@code tachyonServer} bean is declared as the {@code TachyonServer}
     * <em>interface</em>, and Boot contributes further interface-typed beans of its own, so an
     * unfiltered scan would warn on every build of every application. Asserting alongside a real
     * user bean keeps this honest: detection is live in this very context, and only the
     * application's bean is reported.
     */
    @Test
    void doesNotReportItsOwnOrSpringsInfrastructureBeans() {
        assertThat(uninspectableBeans(TachyonAutoConfiguration.class, InterfaceBeanConfig.class))
                .containsOnlyKeys("greeter");
    }

    /**
     * Infrastructure is the starter's own auto-configuration, not the {@code dev.tachyonmcp}
     * namespace. An application shipping configuration under that namespace still earns the warning
     * its interface-typed bean deserves — silencing it would hide a silently featureless native image.
     */
    @Test
    void reportsApplicationBeansDeclaredUnderTheTachyonNamespace() {
        final var reported = uninspectableBeans(TachyonAutoConfiguration.class, NamespacedBeanConfig.class);

        assertThat(reported).containsOnlyKeys("farewell");
        assertThat(reported.get("farewell").interfaceType()).isEqualTo(Farewell.class.getName());
        assertThat(reported.get("farewell").declaringType()).isEqualTo(NamespacedBeanConfig.class.getName());
    }

    /**
     * Mirrors what the AOT engine does: post-process the bean definitions without instantiating a
     * single bean. That distinction is the whole point — once a bean exists, its type resolves to the
     * concrete proxy class and the gap this warning covers becomes invisible.
     */
    private static Map<String, TachyonAotProcessor.InterfaceBeanDeclaration> uninspectableBeans(
            Class<?>... configurations) {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(configurations);
            context.refreshForAotProcessing(new RuntimeHints());
            return TachyonAotProcessor.uninspectableInterfaceBeans(context.getBeanFactory());
        }
    }

    @Test
    void contributesNothingWhenNoBeanDeclaresFeatures() {
        assertThat(new TachyonAotProcessor().processAheadOfTime(beanFactoryWith(PlainService.class)))
                .as("no contribution means no generated code for applications that do not use annotations")
                .isNull();
    }

    /** The contribution only writes hints, so the generated-code sink can stay inert. */
    private record NoOpInitializationCode(TestGenerationContext context) implements BeanFactoryInitializationCode {
        @Override
        public GeneratedMethods getMethods() {
            return context.getGeneratedClasses()
                    .addForFeature("TestInitialization", builder -> {})
                    .getMethods();
        }

        @Override
        public ClassName getClassName() {
            return ClassName.get("dev.tachyonmcp.spring.boot", "TestInitialization");
        }

        @Override
        public void addInitializer(MethodReference methodReference) {}
    }

    private static DefaultListableBeanFactory beanFactoryWith(Class<?>... types) {
        final var beanFactory = new DefaultListableBeanFactory();
        for (final var type : types) {
            beanFactory.registerBeanDefinition(type.getSimpleName(), new RootBeanDefinition(type));
        }
        return beanFactory;
    }
}
