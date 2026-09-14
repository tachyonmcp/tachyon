/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.spring.boot;

import static dev.tachyonmcp.testkit.JsonRpcResponseAssert.assertThat;
import static net.javacrumbs.jsonunit.assertj.JsonAssertions.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;

import dev.tachyonmcp.api.annotations.McpTool;
import dev.tachyonmcp.api.server.features.tools.ToolResult;
import dev.tachyonmcp.core.server.TachyonServer;
import dev.tachyonmcp.testkit.McpTestClients;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Boots the starter in a real Spring context and calls the discovered beans over MCP.
 */
class TachyonAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TachyonAutoConfiguration.class))
            .withPropertyValues("tachyon.port=0", "tachyon.name=spring-mcp");

    record Greeting(String message) {}

    @SuppressWarnings("unused")
    public static class GreetingService {
        @McpTool(description = "Greets by name")
        public Greeting greet(String name) {
            return new Greeting("Hello, " + name + "!");
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class GreetingConfig {
        @Bean
        GreetingService greetingService() {
            return new GreetingService();
        }
    }

    static final AtomicInteger INTERCEPTED = new AtomicInteger();

    @Configuration(proxyBeanMethods = false)
    static class ProxiedGreetingConfig {
        @Bean
        GreetingService greetingService() {
            var factory = new ProxyFactory(new GreetingService());
            factory.setProxyTargetClass(true);
            factory.addAdvice((MethodInterceptor) invocation -> {
                INTERCEPTED.incrementAndGet();
                return invocation.proceed();
            });
            return (GreetingService) factory.getProxy();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomizerConfig {
        @Bean
        TachyonServerCustomizer pingTool() {
            return builder -> builder.withTools(
                    tools -> tools.register(tool -> tool.name("ping"), (ctx, req) -> ToolResult.text("pong")));
        }
    }

    @Test
    void annotatedBeanIsServedOverMcpOnceContextStarts() {
        runner.withUserConfiguration(GreetingConfig.class).run(context -> {
            var server = context.getBean(TachyonServer.class);
            assertThat(server.config().identity().name()).isEqualTo("spring-mcp");

            try (var client = McpTestClients.latest(server.port())) {
                var list = client.sendRpc("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                        """);
                var call = client.sendRpc("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call",
                         "params":{"name":"greet","arguments":{"name":"Ada"}}}
                        """);

                // language=json
                var expectedList = """
                        {"jsonrpc":"2.0","id":1,"result":{
                          "tools":[{"name":"greet","description":"Greets by name",
                            "inputSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]},
                            "outputSchema":{"type":"object","properties":{"message":{"type":"string"}},"required":["message"]}}],
                          "resultType":"complete","ttlMs":0,"cacheScope":"public"}}
                        """;
                assertThatJson(list.body()).isEqualTo(expectedList);
                assertThat(call).isSuccess().hasId(2).hasResult("""
                        {"content":[{"type":"text","text":"{\\"message\\":\\"Hello, Ada!\\"}"}],
                         "structuredContent":{"message":"Hello, Ada!"},
                         "resultType":"complete"}
                        """);
            }
        });
    }

    @Test
    void classProxiedBeanIsDiscoveredAndCalledThroughItsProxy() {
        INTERCEPTED.set(0);
        runner.withUserConfiguration(ProxiedGreetingConfig.class).run(context -> {
            var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                var call = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call",
                     "params":{"name":"greet","arguments":{"name":"Bob"}}}
                    """);

                assertThat(call).isSuccess().hasId(1).hasStructuredContent("""
                    {"message":"Hello, Bob!"}
                    """);
                assertThat(INTERCEPTED).hasValue(1);
            }
        });
    }

    @Test
    void customizerBeanAdjustsTheBuilder() {
        runner.withUserConfiguration(CustomizerConfig.class).run(context -> {
            var server = context.getBean(TachyonServer.class);
            try (var client = McpTestClients.latest(server.port())) {
                var call = client.sendRpc("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ping","arguments":{}}}
                    """);

                assertThat(call).isSuccess().hasId(1).hasResult("""
                    {"content":[{"type":"text","text":"pong"}],"resultType":"complete"}
                    """);
            }
        });
    }

    @Test
    void disabledPropertySkipsTheServer() {
        runner.withPropertyValues("tachyon.enabled=false")
                .withUserConfiguration(GreetingConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean(TachyonServer.class));
    }
}
