/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

/**
 * Registers every singleton bean whose runtime class declares Tachyon feature annotations with the
 * starter-built server, once all singletons exist.
 *
 * <p>Not a Spring {@link org.springframework.beans.factory.BeanRegistrar}: nothing is added to the
 * context here, beans are read out of it.
 *
 * <p>Type lookup never triggers eager initialization, so a bean produced by a {@code FactoryBean}
 * whose object type cannot be determined without creating it is skipped. Declare such a feature bean
 * directly, or register it on the server through a {@link TachyonServerCustomizer}.
 */
final class TachyonFeatureRegistrar implements SmartInitializingSingleton {
    private final TachyonServer server;
    private final ConfigurableListableBeanFactory beanFactory;

    TachyonFeatureRegistrar(TachyonServer server, ConfigurableListableBeanFactory beanFactory) {
        this.server = server;
        this.beanFactory = beanFactory;
    }

    @Override
    public void afterSingletonsInstantiated() {
        for (final var name : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            if (!beanFactory.containsSingleton(name)) {
                final var declaredType = beanFactory.getType(name, false);
                if (declaredType == null || !TachyonAnnotationProvider.declaresFeatures(declaredType)) continue;
            }
            final var bean = beanFactory.getBean(name);
            final var targetType = AopProxyUtils.ultimateTargetClass(bean);
            if (!TachyonAnnotationProvider.declaresFeatures(targetType)) continue;
            server.annotations(annotations -> annotations
                    .withProvider((instance, context) ->
                            TachyonAnnotationProvider.instance().register(instance, targetType, context))
                    .register(bean));
        }
    }
}
