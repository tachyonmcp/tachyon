/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.aot.generate.GeneratedMethods;
import org.springframework.aot.generate.MethodReference;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.aot.test.generate.TestGenerationContext;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
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

    private RuntimeHints process(Class<?>... beanTypes) {
        final var beanFactory = new DefaultListableBeanFactory();
        for (final var type : beanTypes) {
            beanFactory.registerBeanDefinition(type.getSimpleName(), new RootBeanDefinition(type));
        }
        final var contribution = new TachyonAotProcessor().processAheadOfTime(beanFactory);
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

    private static DefaultListableBeanFactory beanFactoryWith(Class<?> type) {
        final var beanFactory = new DefaultListableBeanFactory();
        beanFactory.registerBeanDefinition(type.getSimpleName(), new RootBeanDefinition(type));
        return beanFactory;
    }
}
