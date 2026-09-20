/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.server.features.annotations.ReflectionUtils;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aot.generate.GenerationContext;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotContribution;
import org.springframework.beans.factory.aot.BeanFactoryInitializationAotProcessor;
import org.springframework.beans.factory.aot.BeanFactoryInitializationCode;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.util.ClassUtils;

/**
 * Registers reflection hints for beans whose methods carry Tachyon feature annotations.
 *
 * <p>{@link TachyonBeanRegistrar} finds and invokes those methods reflectively at runtime. In a
 * native image nothing else reaches them, so without these hints a GraalVM build starts a server
 * with no tools, resources, prompts or completions registered — and reports no error.
 *
 * <p>Hints come from each bean definition's resolved type; {@link TachyonBeanRegistrar} reads the
 * runtime target class. The two differ when a {@code @Bean} method returns an <em>interface</em> and
 * builds the implementation in its body — that class does not exist during AOT processing. Those
 * definitions are logged by {@link #warnAboutUninspectableBeans}.
 *
 * <p>Tool input and output types are bound by the JSON layer and are not discovered here. Register
 * both cases with a {@code RuntimeHintsRegistrar} or {@code @RegisterReflectionForBinding}.
 *
 * <p>Registered through {@code META-INF/spring/aot.factories}.
 */
final class TachyonAotProcessor implements BeanFactoryInitializationAotProcessor {

    private static final Logger logger = LoggerFactory.getLogger(TachyonAotProcessor.class);

    private static final String SPRING_PACKAGE = "org.springframework.";

    /**
     * The starter's own configuration, whose {@code @Bean} methods never build feature beans; without
     * this every build warns on {@code tachyonServer}. Only this class and its nested configurations
     * count — an application is free to ship its own configuration under {@code dev.tachyonmcp.*}, and
     * that configuration must be scanned like any other.
     */
    private static final String STARTER_CONFIGURATION = TachyonAutoConfiguration.class.getName();

    @Override
    public @Nullable BeanFactoryInitializationAotContribution processAheadOfTime(
            ConfigurableListableBeanFactory beanFactory) {
        warnAboutUninspectableBeans(beanFactory);
        final var featureTypes = featureTypes(beanFactory);
        if (featureTypes.isEmpty()) return null;
        return new FeatureReflectionContribution(featureTypes);
    }

    private static Set<Class<?>> featureTypes(ConfigurableListableBeanFactory beanFactory) {
        final Set<Class<?>> types = new LinkedHashSet<>();
        for (final var name : beanFactory.getBeanDefinitionNames()) {
            final var type = typeOf(beanFactory, name);
            if (type != null && TachyonAnnotationProvider.declaresFeatures(type)) {
                types.add(type);
                types.addAll(featureInterfaces(type));
            }
        }
        return types;
    }

    /**
     * Interfaces of {@code type} that declare a feature themselves: discovery reads their
     * annotations off the interface's own {@code Method}, so the native image needs them hinted too.
     */
    private static Set<Class<?>> featureInterfaces(Class<?> type) {
        final Set<Class<?>> interfaces = new LinkedHashSet<>();
        for (final var iface : ReflectionUtils.interfacesOf(type)) {
            if (TachyonAnnotationProvider.declaresFeatures(iface)) interfaces.add(iface);
        }
        return interfaces;
    }

    /** Logs every application {@code @Bean} method returning an interface. */
    private static void warnAboutUninspectableBeans(ConfigurableListableBeanFactory beanFactory) {
        if (!logger.isWarnEnabled()) return;
        uninspectableInterfaceBeans(beanFactory)
                .forEach((name, declaration) -> logger.warn(
                        "Tachyon AOT: bean '{}' is declared as interface {} by {}. Reflection hints cannot be"
                                + " inferred from an interface, so if its implementation declares @McpTool,"
                                + " @McpResource, @McpPrompt or @McpCompletion methods, a native image will start"
                                + " with those features missing and report no error. Register the implementation"
                                + " class with a RuntimeHintsRegistrar, or return the concrete type from the @Bean"
                                + " method.",
                        name,
                        declaration.interfaceType(),
                        declaration.declaringType()));
    }

    /** Bean name to declaration, for every application {@code @Bean} method returning an interface. */
    static Map<String, InterfaceBeanDeclaration> uninspectableInterfaceBeans(
            ConfigurableListableBeanFactory beanFactory) {
        final Map<String, InterfaceBeanDeclaration> found = new LinkedHashMap<>();
        for (final var name : beanFactory.getBeanDefinitionNames()) {
            final var type = typeOf(beanFactory, name);
            if (type == null || !type.isInterface()) continue;

            final var declaringType = declaringType(beanFactory, beanFactory.getBeanDefinition(name));
            if (declaringType == null || isInfrastructure(declaringType)) continue;

            found.put(name, new InterfaceBeanDeclaration(type.getName(), declaringType));
        }
        return found;
    }

    /** An interface-typed bean whose implementation AOT cannot see. */
    record InterfaceBeanDeclaration(String interfaceType, String declaringType) {}

    private static @Nullable Class<?> typeOf(ConfigurableListableBeanFactory beanFactory, String name) {
        try {
            return beanFactory.getType(name, false);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /**
     * The class declaring the {@code @Bean} method behind a definition, or {@code null} when the
     * definition is class-based — a component-scanned bean resolves to its concrete class.
     */
    private static @Nullable String declaringType(
            ConfigurableListableBeanFactory beanFactory, BeanDefinition definition) {
        if (definition.getFactoryMethodName() == null) return null;

        final var factoryBeanName = definition.getFactoryBeanName();
        if (factoryBeanName == null) return definition.getBeanClassName();

        final var factoryType = typeOf(beanFactory, factoryBeanName);
        return factoryType != null ? ClassUtils.getUserClass(factoryType).getName() : null;
    }

    private static boolean isInfrastructure(String className) {
        return className.startsWith(SPRING_PACKAGE)
                || className.equals(STARTER_CONFIGURATION)
                || className.startsWith(STARTER_CONFIGURATION + "$");
    }

    private record FeatureReflectionContribution(Set<Class<?>> featureTypes)
            implements BeanFactoryInitializationAotContribution {

        @Override
        public void applyTo(GenerationContext generationContext, BeanFactoryInitializationCode code) {
            final RuntimeHints hints = generationContext.getRuntimeHints();
            for (final var type : featureTypes) {
                // Methods only: the annotation layer reaches features through Method#setAccessible and
                // never reads a field, so a field hint would widen the native image's reflective
                // surface over every user feature bean for nothing.
                hints.reflection()
                        .registerType(
                                type, MemberCategory.INVOKE_DECLARED_METHODS, MemberCategory.INVOKE_PUBLIC_METHODS);
            }
        }
    }
}
