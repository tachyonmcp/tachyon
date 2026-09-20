/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpPrompt;
import dev.tachyonmcp.api.annotations.McpResource;
import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.json.PayloadSerializer;
import dev.tachyonmcp.api.server.features.tools.ToolDescriptor;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.core.server.json.JacksonPayloadSerde;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

class TachyonBeanRegistrationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TachyonAutoConfiguration.class))
            .withPropertyValues("tachyon.port=0");

    public interface Service {
        String hello(String input);

        String readme();

        String prompt();
    }

    public static class ServiceImpl implements Service {
        @Override
        @McpTool
        public String hello(String name) {
            return "hello " + name;
        }

        @Override
        @McpResource(uri = "test://readme")
        public String readme() {
            return "readme";
        }

        @Override
        @McpPrompt
        public String prompt() {
            return "prompt";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProxyConfig {
        @Bean
        AtomicInteger intercepted() {
            return new AtomicInteger();
        }

        @Bean
        Service service(AtomicInteger intercepted) {
            final var factory = new ProxyFactory(new ServiceImpl());
            factory.addAdvice((MethodInterceptor) invocation -> {
                intercepted.incrementAndGet();
                return invocation.proceed() + " advised";
            });
            return (Service) factory.getProxy();
        }
    }

    public static class ServerAwareService {
        private final TachyonServer server;

        ServerAwareService(TachyonServer server) {
            this.server = server;
        }

        @McpTool
        public int port() {
            return server.port();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ServerAwareConfig {
        @Bean
        @Lazy
        ServerAwareService service(TachyonServer server) {
            return new ServerAwareService(server);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserServerConfig {
        @Bean
        TachyonServer server() {
            return TachyonServer.builder()
                    .port(0)
                    .withTools(tools ->
                            tools.register(tool -> tool.name("owned"), (ctx, request) -> ToolResult.text("mine")))
                    .build();
        }

        @Bean
        ServiceImpl service() {
            return new ServiceImpl();
        }
    }

    public interface Announcer {
        @McpTool
        String announce(String message);
    }

    public static class LoudAnnouncer implements Announcer {
        @Override
        public String announce(String message) {
            return message.toUpperCase(Locale.ROOT);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InterfaceDeclaredConfig {
        @Bean
        LoudAnnouncer announcer() {
            return new LoudAnnouncer();
        }
    }

    public record Payload(String value) {}

    public static class CodecService {
        @McpTool
        public Payload echo(Payload payload) {
            return payload;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CodecConfig {
        @Bean
        CodecService codecService() {
            return new CodecService();
        }

        @Bean
        TachyonServerCustomizer codecs() {
            return builder -> builder.json(json -> json.serializer(new PayloadSerializer() {
                @Override
                public <T> String serialize(T value) {
                    return JacksonPayloadSerde.INSTANCE.serialize(
                            value instanceof Payload(String value1) ? new Payload(value1 + " encoded") : value);
                }
            }));
        }

        @Bean
        @Lazy
        Object unrelatedLazyBean() {
            throw new AssertionError("Unrelated lazy bean was instantiated");
        }
    }

    @Test
    void jdkProxyUsesTargetAnnotationsAndPreservesAdviceForEveryFeature() {
        runner.withUserConfiguration(ProxyConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            final var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                // language=json
                assertThat(client.sendRpc("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"hello","arguments":{"name":"Ada"}}}
                        """)).isSuccess().hasId(1).hasResult("""
                        {"content":[{"type":"text","text":"hello Ada advised"}],"resultType":"complete"}
                        """);
                // language=json
                assertThat(client.sendRpc("""
                        {"jsonrpc":"2.0","id":2,"method":"resources/read","params":{"uri":"test://readme"}}
                        """)).isSuccess().hasId(2).hasResult("""
                        {"contents":[{"uri":"test://readme","text":"readme advised"}],
                         "resultType":"complete","ttlMs":0,"cacheScope":"public"}
                        """);
                // language=json
                assertThat(client.sendRpc("""
                        {"jsonrpc":"2.0","id":3,"method":"prompts/get","params":{"name":"prompt"}}
                        """)).isSuccess().hasId(3).hasResult("""
                        {"messages":[{"role":"user","content":{"type":"text","text":"prompt advised"}}],
                         "resultType":"complete"}
                        """);
            }
            assertThat(context.getBean(AtomicInteger.class)).hasValue(3);
        });
    }

    @Test
    void featureDeclaredOnAnImplementedInterfaceIsRegisteredAndInvoked() {
        runner.withUserConfiguration(InterfaceDeclaredConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            final var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                // language=json
                assertThat(client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"announce","arguments":{"message":"hi"}}}
                    """)).isSuccess().hasId(1).hasResult("""
                    {"content":[{"type":"text","text":"HI"}],"resultType":"complete"}
                    """);
            }
        });
    }

    @Test
    void annotatedBeanCanInjectServerBeforeTransportStarts() {
        runner.withUserConfiguration(ServerAwareConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            final var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                // language=json
                assertThat(client.sendRpc("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"port","arguments":{}}}
                        """)).isSuccess().hasId(1).hasResult("""
                        {"content":[{"type":"text","text":"%s"}],"resultType":"complete"}
                        """.formatted(server.port()));
            }
        });
    }

    @Test
    void userProvidedServerKeepsItsOwnRegistrations() {
        runner.withUserConfiguration(UserServerConfig.class).run(context -> {
            assertThat(context).hasSingleBean(TachyonServer.class).doesNotHaveBean(TachyonFeatureRegistrar.class);
            final var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                // language=json
                assertThat(client.sendRpc("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"owned","arguments":{}}}
                        """)).isSuccess().hasId(1).hasResult("""
                        {"content":[{"type":"text","text":"mine"}],"resultType":"complete"}
                        """);
            }
            assertThat(server.tools().descriptors())
                    .extracting(ToolDescriptor::name)
                    .containsExactly("owned");
        });
    }

    @Test
    void deferredRegistrationUsesConfiguredCodecWithoutCreatingUnrelatedLazyBeans() {
        runner.withUserConfiguration(CodecConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            final var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                // language=json
                assertThat(client.sendRpc("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/call",
                         "params":{"name":"echo","arguments":{"value":"hello"}}}
                        """)).isSuccess().hasId(1).hasResult("""
                        {"content":[{"type":"text","text":"{\\"value\\":\\"hello encoded\\"}"}],
                         "structuredContent":{"value":"hello encoded"},"resultType":"complete"}
                        """);
            }
        });
    }
}
