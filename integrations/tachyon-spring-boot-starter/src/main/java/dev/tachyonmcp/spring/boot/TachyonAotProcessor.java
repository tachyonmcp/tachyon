/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Registers reflection hints for beans whose methods carry Tachyon feature annotations.
 *
 * <p>{@link TachyonBeanRegistrar} finds and invokes those methods reflectively at runtime. In a
 * native image nothing else reaches them, so without these hints a GraalVM build starts a server
 * with no tools, resources, prompts or completions registered — and reports no error.
 *
 * <p>Hints cover the declaring types' methods. Types used as tool inputs and outputs are bound by
 * the JSON layer and are not discovered here; register those with a {@code RuntimeHintsRegistrar} of
 * your own, or with {@code @RegisterReflectionForBinding}.
 *
 * <p>Registered through {@code META-INF/spring/aot.factories}.
 */
final class TachyonAotProcessor implements BeanFactoryInitializationAotProcessor {

    @Override
    public @Nullable BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {
        final var featureTypes = featureTypes(beanFactory);
        if (featureTypes.isEmpty()) return null;
        return new FeatureReflectionContribution(featureTypes);
    }

    private static Set<Class<?>> featureTypes(ConfigurableListableBeanFactory beanFactory) {
        final Set<Class<?>> types = new LinkedHashSet<>();
        for (final var name : beanFactory.getBeanDefinitionNames()) {
            final Class<?> type;
            try {
                type = beanFactory.getType(name, false);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (type != null && TachyonAnnotationProvider.declaresFeatures(type)) {
                types.add(type);
            }
        }
        return types;
    }

    private record FeatureReflectionContribution(Set<Class<?>> featureTypes)
            implements BeanFactoryInitializationAotContribution {

        @Override
        public void applyTo(GenerationContext generationContext, BeanFactoryInitializationCode code) {
            final RuntimeHints hints = generationContext.getRuntimeHints();
            for (final var type : featureTypes) {
                hints.reflection()
                        .registerType(
                                type,
                                MemberCategory.INVOKE_DECLARED_METHODS,
                                MemberCategory.INVOKE_PUBLIC_METHODS,
                                MemberCategory.ACCESS_DECLARED_FIELDS);
            }
        }
    }
}
