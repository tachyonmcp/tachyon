/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code tachyon.network/session/runtime} exist so that an operator can tune a deployment from
 * {@code application.yaml} instead of writing a {@link TachyonServerCustomizer}. These tests bind
 * the keys the way Boot binds them and read the values back off the built server.
 */
class TachyonPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TachyonAutoConfiguration.class))
            .withPropertyValues("tachyon.port=0");

    @Test
    void unsetGroupsLeaveTachyonsOwnDefaultsAlone() {
        runner.run(context -> {
            var config = context.getBean(TachyonServer.class).config();

            assertThat(config.network().host()).isEqualTo(NetworkConfig.DEFAULT_HOST);
            assertThat(config.network().endpointPath()).isEqualTo(NetworkConfig.DEFAULT_ENDPOINT_PATH);
            assertThat(config.network().readerIdleTimeout()).isEqualTo(NetworkConfig.DEFAULT_READER_IDLE_TIMEOUT);
            assertThat(config.network().writerIdleTimeout()).isEqualTo(NetworkConfig.DEFAULT_WRITER_IDLE_TIMEOUT);
            assertThat(config.network().heartbeatInterval()).isEqualTo(NetworkConfig.DEFAULT_HEARTBEAT_INTERVAL);
            assertThat(config.network().maxContentLength()).isEqualTo(McpChannelInitializer.DEFAULT_MAX_CONTENT_LENGTH);
            assertThat(config.network().allowedOrigins()).isNull();
            assertThat(config.network().allowedHosts()).isNull();
            assertThat(config.network().ioEngine()).isEqualTo(NettyIoEngine.AUTO);
            assertThat(config.session()).isEqualTo(SessionConfig.STATELESS);
            assertThat(config.runtime().requestTimeout()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.runtime().shutdownGracePeriod()).isEqualTo(Duration.ofSeconds(5));
        });
    }

    @Test
    void networkGroupBindsIncludingYamlListsAndDataSizes() {
        runner.withPropertyValues(
                        "tachyon.network.endpoint-path=/rpc",
                        "tachyon.network.reader-idle-timeout=90s",
                        "tachyon.network.writer-idle-timeout=2m",
                        "tachyon.network.heartbeat-interval=5s",
                        "tachyon.network.max-content-length=2MB",
                        "tachyon.network.allowed-origins[0]=https://app.example.com",
                        "tachyon.network.allowed-origins[1]=https://admin.example.com",
                        "tachyon.network.allowed-headers[0]=X-Trace-Id",
                        "tachyon.network.allowed-hosts[0]=mcp.example.com:8096",
                        "tachyon.network.allow-null-origin=true",
                        "tachyon.network.allow-private-networks=true",
                        "tachyon.network.io-engine=nio")
                .run(context -> {
                    var network = context.getBean(TachyonServer.class).config().network();

                    assertThat(network.endpointPath()).isEqualTo("/rpc");
                    assertThat(network.readerIdleTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(network.writerIdleTimeout()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(network.heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(network.maxContentLength()).isEqualTo(2 * 1024 * 1024);
                    assertThat(network.allowedOrigins())
                            .containsExactly("https://app.example.com", "https://admin.example.com");
                    assertThat(network.allowedHeaders()).containsExactly("X-Trace-Id");
                    assertThat(network.allowedHosts()).containsExactly("mcp.example.com:8096");
                    assertThat(network.allowNullOrigin()).isTrue();
                    assertThat(network.allowPrivateNetworks()).isTrue();
                    assertThat(network.ioEngine()).isEqualTo(NettyIoEngine.NIO);
                });
    }

    @Test
    void aSingleCommaSeparatedValueBindsToTheSameListProperty() {
        runner.withPropertyValues("tachyon.network.allowed-origins=https://a.example.com,https://b.example.com")
                .run(context -> assertThat(context.getBean(TachyonServer.class)
                                .config()
                                .network()
                                .allowedOrigins())
                        .containsExactly("https://a.example.com", "https://b.example.com"));
    }

    @Test
    void sessionOptionAloneTurnsSessionsOnAndFalseKeepsTheServerStateless() {
        runner.withPropertyValues("tachyon.session.session-ttl=90s", "tachyon.session.janitor-interval=7s")
                .run(context -> {
                    var session = context.getBean(TachyonServer.class).config().session();

                    assertThat(session.enabled()).isTrue();
                    assertThat(session.sessionTtl()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(session.janitorInterval()).isEqualTo(Duration.ofSeconds(7));
                });

        runner.withPropertyValues("tachyon.session.enabled=true").run(context -> {
            var session = context.getBean(TachyonServer.class).config().session();

            assertThat(session.enabled()).isTrue();
            assertThat(session.sessionTtl()).isEqualTo(SessionConfig.DEFAULT_SESSION_TTL);
            assertThat(session.janitorInterval()).isEqualTo(SessionConfig.DEFAULT_JANITOR_INTERVAL);
        });

        runner.withPropertyValues("tachyon.session.enabled=false")
                .run(context -> assertThat(
                                context.getBean(TachyonServer.class).config().session())
                        .isEqualTo(SessionConfig.STATELESS));
    }

    @Test
    void runtimeGroupBinds() {
        runner.withPropertyValues("tachyon.runtime.request-timeout=15s", "tachyon.runtime.shutdown-grace-period=0s")
                .run(context -> {
                    var runtime = context.getBean(TachyonServer.class).config().runtime();

                    assertThat(runtime.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
                    assertThat(runtime.shutdownGracePeriod()).isEqualTo(Duration.ZERO);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class EndpointPathCustomizer {
        @Bean
        TachyonServerCustomizer customizer() {
            return builder -> builder.network(network -> network.endpointPath("/from-customizer"));
        }
    }

    @Test
    void customizerStillWinsOverProperties() {
        runner.withPropertyValues("tachyon.network.endpoint-path=/from-properties")
                .withUserConfiguration(EndpointPathCustomizer.class)
                .run(context -> assertThat(context.getBean(TachyonServer.class)
                                .config()
                                .network()
                                .endpointPath())
                        .isEqualTo("/from-customizer"));
    }
}
