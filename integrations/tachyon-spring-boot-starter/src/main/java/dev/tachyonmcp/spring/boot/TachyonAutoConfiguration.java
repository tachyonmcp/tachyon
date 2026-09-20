/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import dev.tachyonmcp.api.annotations.ExperimentalApi;
import dev.tachyonmcp.api.server.extensions.ServerExtension;
import dev.tachyonmcp.core.server.TachyonServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
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
@ConditionalOnBooleanProperty(name = "tachyon.enabled", matchIfMissing = true)
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
        public TachyonServer tachyonServer(
                TachyonProperties properties,
                ObjectProvider<ServerExtension> extensions,
                ObjectProvider<TachyonServerCustomizer> customizers) {
            final var builder = TachyonServer.builder();
            TachyonPropertiesApplier.apply(properties, builder);
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

            /** By name, never by type: {@link TachyonServerCustomizer} is plural, so a type condition
             * would let any application customizer switch MCP metrics off. */
            @Bean
            @ConditionalOnMissingBean(name = "tachyonMetricsCustomizer")
            TachyonServerCustomizer tachyonMetricsCustomizer(MeterRegistry registry) {
                return builder -> builder.observability(o -> o.listener(new TachyonMetricsListener(registry)));
            }

            @Bean
            @ConditionalOnMissingBean
            TachyonMeterBinder tachyonMeterBinder(TachyonServer server, MeterRegistry registry) {
                return new TachyonMeterBinder(server, registry);
            }
        }
    }
}
