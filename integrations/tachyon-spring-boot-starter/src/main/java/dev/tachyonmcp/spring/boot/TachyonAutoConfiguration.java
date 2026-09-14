/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.annotations.TachyonAnnotationProvider;
import java.util.ArrayList;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

/**
 * Builds a {@link TachyonServer} from {@link TachyonProperties}, every singleton bean declaring
 * {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt} methods, every {@link ServerExtension}
 * bean, and every {@link TachyonServerCustomizer} bean; {@link TachyonServerLifecycle} starts it.
 */
@ExperimentalApi
@AutoConfiguration
@EnableConfigurationProperties(TachyonProperties.class)
@ConditionalOnProperty(prefix = "tachyon", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TachyonAutoConfiguration {

    /** Creates the auto-configuration. */
    public TachyonAutoConfiguration() {}

    /**
     * Builds the server without binding its transport.
     *
     * @param properties  the {@code tachyon.*} properties
     * @param beanFactory the bean factory scanned for annotated beans
     * @param extensions  extension beans
     * @param customizers customizer beans
     * @return the built server
     */
    @Bean
    @ConditionalOnMissingBean
    public TachyonServer tachyonServer(
            TachyonProperties properties,
            ListableBeanFactory beanFactory,
            ObjectProvider<ServerExtension> extensions,
            ObjectProvider<TachyonServerCustomizer> customizers) {
        var builder = TachyonServer.builder().port(properties.port());
        if (properties.name() != null) builder.name(properties.name());
        if (properties.version() != null) builder.version(properties.version());
        if (properties.host() != null) builder.host(properties.host());
        builder.withExtensions(extensions.orderedStream().toArray(ServerExtension[]::new));
        var services = new ArrayList<>();
        for (String name : beanFactory.getBeanNamesForType(Object.class, false, false)) {
            var type = beanFactory.getType(name, false);
            if (type != null && TachyonAnnotationProvider.declaresFeatures(ClassUtils.getUserClass(type))) {
                services.add(beanFactory.getBean(name));
            }
        }
        builder.annotations(a -> services.forEach(a::register));
        customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
        return builder.build();
    }

    /**
     * Starts and stops the server with the application context.
     *
     * @param server the server
     * @return the lifecycle
     */
    @Bean
    @ConditionalOnMissingBean
    public TachyonServerLifecycle tachyonServerLifecycle(TachyonServer server) {
        return new TachyonServerLifecycle(server);
    }
}
