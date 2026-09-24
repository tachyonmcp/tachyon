/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.throwable;

import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.config.NetworkConfig;
import dev.tachyonmcp.core.server.config.SessionConfig;
import dev.tachyonmcp.core.transport.netty.McpChannelInitializer;
import dev.tachyonmcp.core.transport.netty.NettyIoEngine;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
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
            assertThat(config.network().maxPipelinedRequests()).isEqualTo(NetworkConfig.DEFAULT_MAX_PIPELINED_REQUESTS);
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
                        "tachyon.network.max-pipelined-requests=4",
                        "tachyon.network.allowed-origins[0]=https://app.example.com",
                        "tachyon.network.allowed-origins[1]=https://admin.example.com",
                        "tachyon.network.allowed-headers[0]=X-Trace-Id",
                        "tachyon.network.allowed-hosts[0]=mcp.example.com:8096",
                        "tachyon.network.allow-private-networks=true",
                        "tachyon.network.io-engine=nio")
                .run(context -> {
                    var network = context.getBean(TachyonServer.class).config().network();

                    assertThat(network.endpointPath()).isEqualTo("/rpc");
                    assertThat(network.readerIdleTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(network.writerIdleTimeout()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(network.heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(network.maxContentLength()).isEqualTo(2 * 1024 * 1024);
                    assertThat(network.maxPipelinedRequests()).isEqualTo(4);
                    assertThat(network.allowedOrigins())
                            .containsExactly("https://app.example.com", "https://admin.example.com");
                    assertThat(network.allowedHeaders()).containsExactly("X-Trace-Id");
                    assertThat(network.allowedHosts()).containsExactly("mcp.example.com:8096");
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

    /**
     * A stateless server has no session, so a TTL set beside {@code enabled: false} is a
     * contradiction. Honouring one key and dropping the other silently is the failure mode worth
     * guarding: the combination must fail, and the failure must name the keys the application wrote
     * rather than the core's {@code call enabled()}, which no one editing YAML can act on.
     */
    @ParameterizedTest
    @MethodSource("contradictorySessionOptions")
    void sessionOptionsOnADisabledSessionAreRejectedByName(List<String> properties, List<String> expectedKeys) {
        runner.withPropertyValues(properties.toArray(String[]::new)).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                    .asInstanceOf(throwable(InvalidConfigurationPropertyValueException.class))
                    .satisfies(failure -> {
                        assertThat(failure.getName()).isEqualTo("tachyon.session.enabled");
                        assertThat(failure.getValue()).isEqualTo(false);
                        assertThat(failure.getReason())
                                .contains(expectedKeys)
                                .contains("Remove them, or set tachyon.session.enabled to true");
                    });
        });
    }

    static Stream<Arguments> contradictorySessionOptions() {
        return Stream.of(
                Arguments.of(
                        List.of("tachyon.session.enabled=false", "tachyon.session.session-ttl=90s"),
                        List.of("tachyon.session.session-ttl")),
                Arguments.of(
                        List.of("tachyon.session.enabled=false", "tachyon.session.janitor-interval=7s"),
                        List.of("tachyon.session.janitor-interval")),
                Arguments.of(
                        List.of(
                                "tachyon.session.enabled=false",
                                "tachyon.session.session-ttl=90s",
                                "tachyon.session.janitor-interval=7s"),
                        List.of("tachyon.session.session-ttl", "tachyon.session.janitor-interval")));
    }

    /**
     * The core takes the body limit as a positive {@code int}, so both edges have to be refused
     * before narrowing — otherwise an oversized value dies as an {@code ArithmeticException} that
     * names no property.
     */
    @ParameterizedTest
    @ValueSource(strings = {"3GB", "0", "-1B"})
    void unrepresentableMaxContentLengthIsRejectedByName(String value) {
        runner.withPropertyValues("tachyon.network.max-content-length=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                    .asInstanceOf(throwable(InvalidConfigurationPropertyValueException.class))
                    .satisfies(
                            failure -> assertThat(failure.getName()).isEqualTo("tachyon.network.max-content-length"));
        });
    }

    @Test
    void negativeMaxPipelinedRequestsIsRejectedByName() {
        runner.withPropertyValues("tachyon.network.max-pipelined-requests=-1").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                    .asInstanceOf(throwable(InvalidConfigurationPropertyValueException.class))
                    .satisfies(failure ->
                            assertThat(failure.getName()).isEqualTo("tachyon.network.max-pipelined-requests"));
        });
    }

    /** An {@code Origin} is {@code http(s)://host[:port]}; anything else never matches a browser. */
    @ParameterizedTest
    @ValueSource(strings = {"https://app.example.com/", "*", "null", "ftp://app.example.com"})
    void malformedAllowedOriginIsRejectedByName(String value) {
        runner.withPropertyValues("tachyon.network.allowed-origins[0]=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .rootCause()
                    .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                    .asInstanceOf(throwable(InvalidConfigurationPropertyValueException.class))
                    .satisfies(failure -> assertThat(failure.getName()).isEqualTo("tachyon.network.allowed-origins"));
        });
    }

    @Test
    void allowedOriginsBindCanonical() {
        runner.withPropertyValues("tachyon.network.allowed-origins[0]=HTTPS://App.Example.com:443")
                .run(context -> assertThat(context.getBean(TachyonServer.class)
                                .config()
                                .network()
                                .allowedOrigins())
                        .containsExactly("https://app.example.com"));
    }

    @Test
    void theLargestRepresentableMaxContentLengthStillBinds() {
        runner.withPropertyValues("tachyon.network.max-content-length=2047MB").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TachyonServer.class).config().network().maxContentLength())
                    .isEqualTo(2047 * 1024 * 1024);
        });
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

    /**
     * Boot binds a suffixless {@code Duration} as milliseconds unless {@code @DurationUnit} says
     * otherwise, so without it {@code reader-idle-timeout: 90} meant 90ms — a timeout three orders of
     * magnitude tighter than the documented "seconds will be used", reaping every connection. Each of
     * the seven durations gets a distinct value so a misplaced annotation cannot pass.
     */
    @Test
    void suffixlessDurationsBindAsSecondsAcrossEveryGroup() {
        runner.withPropertyValues(
                        "tachyon.network.reader-idle-timeout=90",
                        "tachyon.network.writer-idle-timeout=120",
                        "tachyon.network.heartbeat-interval=5",
                        "tachyon.session.session-ttl=30",
                        "tachyon.session.janitor-interval=7",
                        "tachyon.runtime.shutdown-grace-period=3",
                        "tachyon.runtime.request-timeout=45")
                .run(context -> {
                    var config = context.getBean(TachyonServer.class).config();

                    assertThat(config.network().readerIdleTimeout()).isEqualTo(Duration.ofSeconds(90));
                    assertThat(config.network().writerIdleTimeout()).isEqualTo(Duration.ofSeconds(120));
                    assertThat(config.network().heartbeatInterval()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(config.session().sessionTtl()).isEqualTo(Duration.ofSeconds(30));
                    assertThat(config.session().janitorInterval()).isEqualTo(Duration.ofSeconds(7));
                    assertThat(config.runtime().shutdownGracePeriod()).isEqualTo(Duration.ofSeconds(3));
                    assertThat(config.runtime().requestTimeout()).isEqualTo(Duration.ofSeconds(45));
                });
    }

    /** {@code @DurationUnit} sets the default unit; an explicit suffix still decides. */
    @Test
    void anExplicitSuffixOverridesTheDefaultUnit() {
        runner.withPropertyValues("tachyon.network.reader-idle-timeout=500ms", "tachyon.runtime.request-timeout=2m")
                .run(context -> {
                    var config = context.getBean(TachyonServer.class).config();

                    assertThat(config.network().readerIdleTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(config.runtime().requestTimeout()).isEqualTo(Duration.ofMinutes(2));
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
