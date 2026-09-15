/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.TachyonServer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds a {@link TachyonServer} from {@link TachyonProperties}, every singleton bean declaring
 * {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt}/{@code @McpCompletion} methods, every {@link ServerExtension}
 * bean, and every {@link TachyonServerCustomizer} bean; {@link TachyonServerLifecycle} starts it.
 */
@ExperimentalApi
@AutoConfiguration
@EnableConfigurationProperties(TachyonProperties.class)
@ConditionalOnProperty(prefix = "tachyon", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TachyonAutoConfiguration {

    /** Creates the auto-configuration. */
    public TachyonAutoConfiguration() {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(TachyonServer.class)
    static class ServerConfiguration {

        /**
         * Builds the server without binding its transport.
         *
         * @param properties  the {@code tachyon.*} properties
         * @param extensions  extension beans
         * @param customizers customizer beans
         * @return the built server
         */
        @Bean
        @ConditionalOnMissingBean
        public TachyonServer tachyonServer(
                TachyonProperties properties,
                ObjectProvider<ServerExtension> extensions,
                ObjectProvider<TachyonServerCustomizer> customizers) {
            var builder = TachyonServer.builder().port(properties.port());
            if (properties.name() != null) builder.name(properties.name());
            if (properties.version() != null) builder.version(properties.version());
            if (properties.host() != null) builder.host(properties.host());
            builder.withExtensions(extensions.orderedStream().toArray(ServerExtension[]::new));
            customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
            return builder.build();
        }

        @Bean
        TachyonBeanRegistrar tachyonBeanRegistrar(TachyonServer server, ConfigurableListableBeanFactory beanFactory) {
            return new TachyonBeanRegistrar(server, beanFactory);
        }
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
