/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

final class TachyonBeanRegistrar implements SmartInitializingSingleton {
    private final TachyonServer server;
    private final ConfigurableListableBeanFactory beanFactory;

    TachyonBeanRegistrar(TachyonServer server, ConfigurableListableBeanFactory beanFactory) {
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
            final var targetType = AopUtils.getTargetClass(bean);
            if (!TachyonAnnotationProvider.declaresFeatures(targetType)) continue;
            server.annotations(annotations -> annotations
                    .withProvider((instance, context) ->
                            TachyonAnnotationProvider.instance().register(instance, targetType, context))
                    .register(bean));
        }
    }
}
