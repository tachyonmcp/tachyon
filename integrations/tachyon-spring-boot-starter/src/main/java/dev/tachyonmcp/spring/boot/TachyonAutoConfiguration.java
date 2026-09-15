/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.TachyonServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.autoconfigure.contributor.ConditionalOnEnabledHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds a {@link TachyonServer} from {@link TachyonProperties}, every singleton bean declaring
 * {@code @McpTool}/{@code @McpResource}/{@code @McpPrompt}/{@code @McpCompletion} methods, every {@link ServerExtension}
 * bean, and every {@link TachyonServerCustomizer} bean; {@link TachyonServerLifecycle} starts it.
 *
 * <p>With Spring Boot health on the classpath, contributes {@link TachyonHealthIndicator} as
 * {@code tachyon} (disable with {@code management.health.tachyon.enabled=false}). With Micrometer
 * and a {@code MeterRegistry} bean, times operations of the starter-built server as
 * {@code mcp.server.operations} and gauges registered tools, prompts, and resources.
 */
@ExperimentalApi
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
            "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration"
        })
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class HealthConfiguration {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnEnabledHealthIndicator("tachyon")
        static class EnabledHealthConfiguration {

            @Bean
            @ConditionalOnMissingBean(name = "tachyonHealthIndicator")
            TachyonHealthIndicator tachyonHealthIndicator(TachyonServer server, TachyonServerLifecycle lifecycle) {
                return new TachyonHealthIndicator(server, lifecycle);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
    static class MetricsConfiguration {

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnBean(MeterRegistry.class)
        static class RegistryMetricsConfiguration {

            @Bean
            TachyonServerCustomizer tachyonMetricsCustomizer(MeterRegistry registry) {
                return builder -> builder.observability(o -> o.listener(new TachyonMetricsListener(registry)));
            }

            @Bean
            MeterBinder tachyonMeterBinder(TachyonServer server) {
                return new TachyonMeterBinder(server);
            }
        }
    }
}
